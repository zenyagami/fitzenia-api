#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./grant-secrets.sh [dev|prod]

Grants Secret Manager accessor role on every secret referenced by the target
Cloud Run YAML:
  dev  -> cloud-run-config.dev.yaml / fitzenio-debug
  prod -> cloud-run-config.yaml     / fitzenio

Environment overrides:
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

TARGET_INPUT="${1:-dev}"
if [[ "$TARGET_INPUT" == "-h" || "$TARGET_INPUT" == "--help" ]]; then
  usage
  exit 0
fi

resolve_target "$TARGET_INPUT"
DRY_RUN="${DRY_RUN:-0}"

if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "[ERROR] Missing config file: $CONFIG_FILE" >&2
  exit 1
fi

if ! command -v gcloud >/dev/null 2>&1; then
  echo "[ERROR] gcloud is not installed or not on PATH" >&2
  exit 1
fi

PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
SERVICE_ACCOUNT_EMAIL="${SERVICE_ACCOUNT_EMAIL:-${PROJECT_NUMBER}-compute@developer.gserviceaccount.com}"
SERVICE_ACCOUNT="serviceAccount:${SERVICE_ACCOUNT_EMAIL}"

echo "[INFO] Target: $TARGET"
echo "[INFO] Project: $PROJECT_ID"
echo "[INFO] Config file: $CONFIG_FILE"
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
  if ! secret_exists "$secret_name"; then
    echo "[ERROR] Missing secret in Secret Manager: $secret_name" >&2
    missing_secrets+=("$secret_name")
    continue
  fi

  grant_secret "$secret_name"
done

if [[ "${#missing_secrets[@]}" -gt 0 ]]; then
  echo "[ERROR] Create the missing secrets first, then rerun grant-secrets.sh" >&2
  exit 1
fi

echo "[OK] Done"
