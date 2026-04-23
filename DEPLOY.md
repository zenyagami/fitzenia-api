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
