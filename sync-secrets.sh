#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./sync-secrets.sh [dev|prod]

Reads values from .env and upserts supported secrets into Secret Manager.
For dev, it also maintains the legacy SUPABASE_DEV_* secret names so older
Cloud Run revisions can still start while the service config is being cleaned up.

Environment overrides:
  ENV_FILE=.env.local
  SERVICE_ACCOUNT_EMAIL=my-sa@project.iam.gserviceaccount.com
EOF
}

TARGET="${1:-dev}"
ENV_FILE="${ENV_FILE:-.env}"

if [[ "$TARGET" == "-h" || "$TARGET" == "--help" ]]; then
  usage
  exit 0
fi

case "$TARGET" in
  dev)
    PROJECT_ID="fitzenio-debug"
    ;;
  prod)
    PROJECT_ID="fitzenio"
    ;;
  *)
    usage
    exit 1
    ;;
esac

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

first_non_empty() {
  local value
  for value in "$@"; do
    if [[ -n "${value:-}" ]]; then
      printf '%s' "$value"
      return 0
    fi
  done
  return 1
}

upsert_secret() {
  local name="$1"
  local value="$2"

  if gcloud secrets describe "$name" --project="$PROJECT_ID" >/dev/null 2>&1; then
    printf '%s' "$value" | gcloud secrets versions add "$name" \
      --data-file=- \
      --project="$PROJECT_ID" >/dev/null
    echo "[OK] Updated secret version: $name"
  else
    printf '%s' "$value" | gcloud secrets create "$name" \
      --data-file=- \
      --replication-policy=automatic \
      --project="$PROJECT_ID" >/dev/null
    echo "[OK] Created secret: $name"
  fi
}

grant_secret() {
  local name="$1"

  gcloud secrets add-iam-policy-binding "$name" \
    --member="$SERVICE_ACCOUNT" \
    --role="roles/secretmanager.secretAccessor" \
    --project="$PROJECT_ID" >/dev/null

  echo "[OK] Granted accessor on $name"
}

sync_secret_if_present() {
  local secret_name="$1"
  local secret_value="$2"

  if [[ -z "${secret_value:-}" ]]; then
    echo "[SKIP] $secret_name is not set in $ENV_FILE"
    return 0
  fi

  upsert_secret "$secret_name" "$secret_value"
  grant_secret "$secret_name"
}

PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
SERVICE_ACCOUNT_EMAIL="${SERVICE_ACCOUNT_EMAIL:-${PROJECT_NUMBER}-compute@developer.gserviceaccount.com}"
SERVICE_ACCOUNT="serviceAccount:${SERVICE_ACCOUNT_EMAIL}"

echo "[INFO] Target: $TARGET"
echo "[INFO] Project: $PROJECT_ID"
echo "[INFO] Env file: $ENV_FILE"
echo "[INFO] Service account: $SERVICE_ACCOUNT_EMAIL"

for secret_name in \
  FATSECRET_CLIENT_ID \
  FATSECRET_CLIENT_SECRET \
  USDA_API_KEY \
  OPENAI_API_KEY \
  GEMINI_API_KEY
do
  sync_secret_if_present "$secret_name" "${!secret_name-}"
done

SUPABASE_URL_VALUE="$(first_non_empty "${SUPABASE_URL-}" "${SUPABASE_DEV_URL-}" || true)"
SUPABASE_PUBLISHABLE_KEY_VALUE="$(first_non_empty "${SUPABASE_PUBLISHABLE_KEY-}" "${SUPABASE_DEV_PUBLISHABLE_KEY-}" || true)"
SUPABASE_ANON_KEY_VALUE="$(first_non_empty "${SUPABASE_ANON_KEY-}" "${SUPABASE_DEV_ANON_KEY-}" || true)"

sync_secret_if_present "SUPABASE_URL" "$SUPABASE_URL_VALUE"
sync_secret_if_present "SUPABASE_PUBLISHABLE_KEY" "$SUPABASE_PUBLISHABLE_KEY_VALUE"
sync_secret_if_present "SUPABASE_ANON_KEY" "$SUPABASE_ANON_KEY_VALUE"

if [[ "$TARGET" == "dev" ]]; then
  sync_secret_if_present "SUPABASE_DEV_URL" "$SUPABASE_URL_VALUE"
  sync_secret_if_present "SUPABASE_DEV_PUBLISHABLE_KEY" "$SUPABASE_PUBLISHABLE_KEY_VALUE"
  sync_secret_if_present "SUPABASE_DEV_ANON_KEY" "$SUPABASE_ANON_KEY_VALUE"
fi

echo "[INFO] Plain env vars still come from the Cloud Run YAML:"
echo "       APP_ENVIRONMENT, USE_GEMINI, SUPABASE_JWT_VERIFICATION_MODE"
echo "[INFO] Next step: ./deploy-dev.sh or ./deploy.sh"
