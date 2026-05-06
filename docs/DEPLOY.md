# DEPLOY.md — Cloud Run Deployment Guide

## Project structure

| Environment | GCP Project | Cloud Run Service | Config file | Deploy script |
|---|---|---|---|---|
| Dev | `fitzenio-debug` | `fitzenia-api-dev` | `cloud-run-config.dev.yaml` | `./deploy-dev.sh` |
| Production | `fitzenio` | `fitzenia-api-prod` | `cloud-run-config.yaml` | `./deploy.sh` |

Secrets are stored in **Secret Manager** in each GCP project and referenced by name in the YAML config.
The Cloud Run service account must have `roles/secretmanager.secretAccessor` for each secret it reads.

---

## Regular deploy

```bash
# Optional safety check:
./check-cloud-run-env.sh all .env.example

# Optional but recommended before deploy:
ENV_FILE=.env.dev ./sync-secrets.sh dev
ENV_FILE=.env.prod ./sync-secrets.sh prod

# Dev
./deploy-dev.sh

# Production
./deploy.sh
```

Both scripts: build with Jib → push image to GCR → `gcloud run services replace <config>.yaml`.

> **Important:** `gcloud run services replace` uses the YAML as the full desired state — any env vars set
> manually in the Console will be wiped on the next deploy. Always make env changes in the YAML, not the UI.

---

## Adding a new API key / secret

Do this checklist for **each environment** you're adding the key to.

### Step 1 — Add the env entry to the Cloud Run YAML

**Dev** (`cloud-run-config.dev.yaml`):
```yaml
- name: MY_NEW_KEY
  valueFrom:
    secretKeyRef:
      name: MY_NEW_KEY
      key: latest
```

**Production** (`cloud-run-config.yaml`) — same snippet.

For plain (non-secret) env vars:
```yaml
- name: MY_FEATURE_FLAG
  value: "true"
```

### Step 2 — Add the value to your local env file

Put the real value in the local file for that environment:

```bash
# Dev
cp .env.example .env.dev

# Production
cp .env.example ..env.prod
```

Then add:

```bash
MY_NEW_KEY=your_actual_value
```

### Step 3 — Add to `.env.example`

```
MY_NEW_KEY=your_value_here
```

### Step 4 — Validate env vs Cloud Run YAML

```bash
./check-cloud-run-env.sh all .env.example
```

For environment-specific files:

```bash
./check-cloud-run-env.sh dev .env.dev
./check-cloud-run-env.sh prod ..env.prod
```

By default the script ignores `PORT`, since Cloud Run injects that automatically.
You can ignore extra local-only keys like this:

```bash
IGNORE_KEYS=PORT,MY_LOCAL_ONLY_KEY ./check-cloud-run-env.sh
```

### Step 5 — Sync secrets and IAM

```bash
# Dev
ENV_FILE=.env.dev ./sync-secrets.sh dev

# Production
ENV_FILE=..env.prod ./sync-secrets.sh prod
```

This script now does both jobs:
- creates the secret if it does not exist
- adds a new secret version if it already exists
- grants the Cloud Run compute service account `roles/secretmanager.secretAccessor`

If you only need to re-grant IAM access without changing values:

```bash
./grant-secrets.sh dev
./grant-secrets.sh prod
```

### Step 6 — Deploy

```bash
./deploy-dev.sh   # dev
./deploy.sh       # production
```

---

## Current secrets

| Secret name | Dev (`fitzenio-debug`) | Prod (`fitzenio`) |
|---|---|---|
| `FATSECRET_CLIENT_ID` | yes | yes |
| `FATSECRET_CLIENT_SECRET` | yes | yes |
| `USDA_API_KEY` | yes | yes |
| `OPENAI_API_KEY` | yes | yes |
| `GEMINI_API_KEY` | yes | yes |
| `SUPABASE_URL` | yes | yes |
| `SUPABASE_PUBLISHABLE_KEY` | yes | yes |
| `SUPABASE_SERVICE_ROLE_KEY` | yes | yes |

---

## Production — first-time setup checklist

If standing up production from scratch in the `fitzenio` GCP project:

```bash
# 1. Authenticate
gcloud auth login
gcloud auth configure-docker

# 2. Enable required APIs
gcloud services enable run.googleapis.com secretmanager.googleapis.com \
  containerregistry.googleapis.com --project=fitzenio

# 3. Prepare your local env file with prod values
cp .env.example ..env.prod

# 4. Sync secrets + IAM grants
ENV_FILE=..env.prod ./sync-secrets.sh prod

# 5. Deploy
./deploy.sh
```

---

## OFF Mirror Ingest Job (prod only)

Open Food Facts mirror is populated by a **separate Cloud Run Job**, not the API service. It shares the repo and the Jib pipeline but builds a different image and main class.

| Field | Value |
|---|---|
| GCP project | `fitzenio` (prod only — no dev counterpart) |
| Region | `europe-north1` |
| Job name | `off-ingest` |
| Image | `gcr.io/fitzenio/fitzenio-off-ingest` |
| Manifest | `cloud-run-job-ingest.yaml` |
| Deploy script | `./deploy-ingest.sh` |
| Main class | `com.zenthek.ingest.IngestMain` |

### Deploy

```bash
./deploy-ingest.sh
```

The script builds with `-Pprod -PtargetService=ingest` (switches Jib's `mainClass` and image name), then `gcloud run jobs replace`s the manifest with a fresh timestamp tag. Same project, same region, same secrets as the API service — no separate IAM grant needed; the Job uses the same compute service account.

### Secrets

The manifest references the same secrets the API does (`SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, `SUPABASE_SERVICE_ROLE_KEY`, plus `FATSECRET_*`, `USDA_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`). The last five are required by `ConfigLoader.createProductionConfig()` at boot even though the Job itself doesn't use them — the loader fails fast on missing keys.

Two non-secret env vars are pinned ON in the manifest so an env-default change can't silently demote a run to dry-run:

```yaml
- name: OFF_MIRROR_WRITE_ENABLED
  value: "true"
- name: OFF_MIRROR_READ_ENABLED
  value: "true"
```

### Manual smoke test

```bash
# Dry-run — streams + parses + counts but writes nothing.
gcloud run jobs execute off-ingest \
  --args=--kind=delta,--dry-run \
  --region=europe-north1 --project=fitzenio --wait

# Real delta (5–15 min).
gcloud run jobs execute off-ingest \
  --args=--kind=delta \
  --region=europe-north1 --project=fitzenio --wait

# Full reconcile (multi-GB stream, 60–90 min).
gcloud run jobs execute off-ingest \
  --args=--kind=full \
  --region=europe-north1 --project=fitzenio --wait
```

After each run, audit row lands in `public.off_sync_state` with status, counters, and error_message if any.

### Cloud Scheduler

Two HTTP triggers, off-peak minutes (avoid OFF's CDN spike at :00):

```bash
SA="<cloud-run-invoker-service-account>@fitzenio.iam.gserviceaccount.com"
JOB_URI="https://europe-north1-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/fitzenio/jobs/off-ingest:run"

gcloud scheduler jobs create http off-delta-daily \
  --project=fitzenio --location=europe-north1 \
  --schedule="7 3 * * *" --time-zone=UTC \
  --uri="$JOB_URI" --http-method=POST \
  --oauth-service-account-email="$SA" \
  --message-body='{"overrides":{"containerOverrides":[{"args":["--kind=delta"]}]}}'

gcloud scheduler jobs create http off-full-weekly \
  --project=fitzenio --location=europe-north1 \
  --schedule="13 4 * * 0" --time-zone=UTC \
  --uri="$JOB_URI" --http-method=POST \
  --oauth-service-account-email="$SA" \
  --message-body='{"overrides":{"containerOverrides":[{"args":["--kind=full"]}]}}'
```

The two jobs serialize via the `off_sync_state` RUNNING-row check inside the ingest code — if delta and full overlap, the second one exits with `status=CANCELLED`.

### Ingest troubleshooting

**`22P05: unsupported Unicode escape sequence` (`\u0000 cannot be converted to text`)**
- OFF rows occasionally contain literal U+0000 NUL bytes (broken OCR/extraction on their side). Postgres rejects NUL in TEXT and JSONB, taking down the whole 500-row batch.
- Fix lives at the mapper boundary: `OffMirrorMapper.sanitize()` / `sanitizeJson()` strip the byte before serialization. Redeploy the Job after any change there.

**`42883: operator does not exist: text % text` (or `text extensions.% text`)**
- `pg_trgm` is installed in a different schema across Supabase projects. The migration sets `SET search_path = public, extensions, pg_temp` on every function so the unqualified `%` operator resolves regardless. If you ever see this on a fresh migration, check the function's `search_path` line.

**Stale `RUNNING` row in `off_sync_state` blocking new runs**
- Happens when a Job dies hard (OOM, node preemption) before it can mark its row `FAILED`. The mutual-exclusion guard treats anything ≤6h old as still running.
- Wait 6h — the next run will treat it as stale and proceed — or clean it up manually:
  ```sql
  UPDATE public.off_sync_state
     SET status = 'FAILED', finished_at = now()
   WHERE status = 'RUNNING';
  ```

**`57014: statement timeout` on `upsert_off_products` RPC**
- The writer auto-bisects the batch and retries on this code (see `OffMirrorWriter.upsertBatchInternal`). One or two splits is normal; persistent timeouts at batch size 1 mean a single row is genuinely too heavy (rare — usually a pathological `nutriments` blob) or the Supabase tier needs more headroom.

**Delta aborts with `checkpoint outside delta window`**
- The last successful run is older than ~13 days, so OFF's 14-day delta archive can't bridge the gap. Fix: run `--kind=full` once; subsequent deltas resume normally.

**`gpt-image-2`-style "verified org required"**
- Not relevant to the ingest Job — that's only in the AI progress projection feature.

**Supabase "exhausting multiple resources" banner / end-of-run `PATCH off_sync_state` timeout**
- Symptom: a multi-hour reconcile completes the upsert + soft-delete successfully, then dies on the final audit-row PATCH with `HttpRequestTimeoutException` against `/rest/v1/off_sync_state?id=eq.<uuid>`. The banner appears in the Supabase project dashboard.
- Cause: post-ingest pooler/PostgREST saturation; the tiny PATCH waits behind autovacuum + index maintenance kicked off by the bulk upsert and soft-delete.
- Mitigations applied: `OffSyncStateGateway.applyLongTimeout()` is 90 s and `finishRun` retries once on timeout. Migration `003_off_mirror_trim.sql` drops four write-only columns (`product_name_localized`, `lc`, `completeness`, `rev`) and two unused indexes (`idx_off_food_modified`, `idx_off_food_brand`) to cut steady-state pressure.
- If the banner persists after 003 + a clean reconcile: temporarily bump the Supabase compute add-on one tier for the next full run, then drop back. Don't pre-emptively buy compute — the trim alone is usually enough.

---

## Troubleshooting

**Service crashes on startup with `Missing GEMINI_API_KEY` (or similar)**
- The secret exists in Secret Manager but the service account doesn't have access → run `./grant-secrets.sh dev` or `./grant-secrets.sh prod`
- The env entry is missing from the YAML → add it and redeploy (manual Console edits are overwritten on deploy)

**`gcloud run services replace` fails with permission error**
- Make sure you're authenticated: `gcloud auth login`
- Make sure you're targeting the right project: `--project=fitzenio-debug` or `--project=fitzenio`

**Image push fails**
- Docker Desktop must be running
- Run `gcloud auth configure-docker` if you haven't yet
