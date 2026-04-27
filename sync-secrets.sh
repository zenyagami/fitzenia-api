#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./sync-secrets.sh [dev|prod] [env-file]

Reads values from a local env file and syncs every Secret Manager key referenced
by the target Cloud Run YAML:
  dev  -> cloud-run-config.dev.yaml
  prod -> cloud-run-config.yaml

Default env files:
  dev  -> .env.dev, then .env.development, then .env
  prod -> .env.prod, then .env.production, then .env

If a secret is referenced by the YAML but missing from the env file, the script
keeps the existing Secret Manager value if it already exists, and still grants
the Cloud Run service account access. If the secret does not already exist, the
script exits with an error so deploy issues are caught early.

Environment overrides:
  ENV_FILE=.env.prod
  SERVICE_ACCOUNT_EMAIL=my-sa@project.iam.gserviceaccount.com
  EXTRA_SECRETS=SECRET_ONE,SECRET_TWO
  DRY_RUN=1
EOF
}

resolve_target() {
  case "$1" in
    dev|development|fitzenio-debug)
      TARGET="dev"
      PROJECT_ID="fitzenio-debug"
      CONFIG_FILE="cloud-run-config.dev.yaml"
      ;;
    prod|production|fitzenio)
      TARGET="prod"
      PROJECT_ID="fitzenio"
      CONFIG_FILE="cloud-run-config.yaml"
      ;;
    *)
      usage
      exit 1
      ;;
  esac
}

default_env_file() {
  local target="$1"
  local candidate
  if [[ "$target" == "prod" ]]; then
    for candidate in .env.prod .env.production .env; do
      if [[ -f "$candidate" ]]; then
        printf '%s' "$candidate"
        return 0
      fi
    done
  else
    for candidate in .env.dev .env.development .env; do
      if [[ -f "$candidate" ]]; then
        printf '%s' "$candidate"
        return 0
      fi
    done
  fi
  printf '%s' ".env"
}

discover_secret_names() {
  {
    awk '
      /secretKeyRef:/ { in_secret_ref = 1; next }
      in_secret_ref && /^[[:space:]]*name:[[:space:]]*/ {
        value = $0
        sub(/^[[:space:]]*name:[[:space:]]*/, "", value)
        gsub(/["'"'"'[:space:]]/, "", value)
        print value
        in_secret_ref = 0
      }
    ' "$CONFIG_FILE"

    if [[ -n "${EXTRA_SECRETS:-}" ]]; then
      printf '%s\n' "$EXTRA_SECRETS" | tr ', ' '\n\n' | sed '/^$/d'
    fi
  } | sort -u
}

secret_exists() {
  local name="$1"
  gcloud secrets describe "$name" --project="$PROJECT_ID" >/dev/null 2>&1
}

TARGET_INPUT="${1:-dev}"
if [[ "$TARGET_INPUT" == "-h" || "$TARGET_INPUT" == "--help" ]]; then
  usage
  exit 0
fi

resolve_target "$TARGET_INPUT"

ENV_FILE="${2:-${ENV_FILE:-$(default_env_file "$TARGET")}}"
DRY_RUN="${DRY_RUN:-0}"

if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "[ERROR] Missing config file: $CONFIG_FILE" >&2
  exit 1
fi
if [[ ! -f "$ENV_FILE" ]]; then
  echo "[ERROR] Missing env file: $ENV_FILE" >&2
  exit 1
fi

if ! command -v gcloud >/dev/null 2>&1; then
  echo "[ERROR] gcloud is not installed or not on PATH" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

upsert_secret() {
  local name="$1"
  local value="$2"

  if secret_exists "$name"; then
    if [[ "$DRY_RUN" == "1" ]]; then
      echo "[DRY-RUN] Would add a new secret version for: $name"
    else
      printf '%s' "$value" | gcloud secrets versions add "$name" \
        --data-file=- \
        --project="$PROJECT_ID" >/dev/null
      echo "[OK] Updated secret version: $name"
    fi
  else
    if [[ "$DRY_RUN" == "1" ]]; then
      echo "[DRY-RUN] Would create secret: $name"
    else
      printf '%s' "$value" | gcloud secrets create "$name" \
        --data-file=- \
        --replication-policy=automatic \
        --project="$PROJECT_ID" >/dev/null
      echo "[OK] Created secret: $name"
    fi
  fi
}

grant_secret() {
  local name="$1"

  if [[ "$DRY_RUN" == "1" ]]; then
    echo "[DRY-RUN] Would grant roles/secretmanager.secretAccessor on $name to $SERVICE_ACCOUNT_EMAIL"
  else
    gcloud secrets add-iam-policy-binding "$name" \
      --member="$SERVICE_ACCOUNT" \
      --role="roles/secretmanager.secretAccessor" \
      --project="$PROJECT_ID" >/dev/null
    echo "[OK] Granted accessor on $name"
  fi
}

PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
SERVICE_ACCOUNT_EMAIL="${SERVICE_ACCOUNT_EMAIL:-${PROJECT_NUMBER}-compute@developer.gserviceaccount.com}"
SERVICE_ACCOUNT="serviceAccount:${SERVICE_ACCOUNT_EMAIL}"

echo "[INFO] Target: $TARGET"
echo "[INFO] Project: $PROJECT_ID"
echo "[INFO] Config file: $CONFIG_FILE"
echo "[INFO] Env file: $ENV_FILE"
echo "[INFO] Service account: $SERVICE_ACCOUNT_EMAIL"
if [[ "$DRY_RUN" == "1" ]]; then
  echo "[INFO] Dry run: enabled"
fi

SECRET_NAMES=()
while IFS= read -r secret_name; do
  SECRET_NAMES+=("$secret_name")
done < <(discover_secret_names)
if [[ "${#SECRET_NAMES[@]}" -eq 0 ]]; then
  echo "[ERROR] No secretKeyRef names found in $CONFIG_FILE" >&2
  exit 1
fi

missing_secrets=()
for secret_name in "${SECRET_NAMES[@]}"; do
  secret_value="${!secret_name-}"
  if [[ -n "${secret_value:-}" ]]; then
    upsert_secret "$secret_name" "$secret_value"
    grant_secret "$secret_name"
    continue
  fi

  if secret_exists "$secret_name"; then
    echo "[INFO] $secret_name is not set in $ENV_FILE; keeping existing Secret Manager value"
    grant_secret "$secret_name"
    continue
  fi

  echo "[ERROR] $secret_name is referenced by $CONFIG_FILE but missing from $ENV_FILE and Secret Manager" >&2
  missing_secrets+=("$secret_name")
done

if [[ "${#missing_secrets[@]}" -gt 0 ]]; then
  echo "[ERROR] Missing required secrets: ${missing_secrets[*]}" >&2
  exit 1
fi

echo "[INFO] Plain env vars still come from the Cloud Run YAML:"
echo "       APP_ENVIRONMENT, USE_GEMINI, SUPABASE_JWT_VERIFICATION_MODE"
echo "[INFO] Next step: ./deploy-dev.sh or ./deploy.sh"
