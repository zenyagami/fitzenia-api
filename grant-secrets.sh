#!/usr/bin/env bash

set -euo pipefail

PROJECT="${1:-fitzenio-debug}"
PROJECT_NUMBER="$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')"
SA="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"

SECRETS=(
  FATSECRET_CLIENT_ID
  FATSECRET_CLIENT_SECRET
  USDA_API_KEY
  OPENAI_API_KEY
  GEMINI_API_KEY
  SUPABASE_URL
  SUPABASE_PUBLISHABLE_KEY
  SUPABASE_ANON_KEY
)

echo "[INFO] Project: $PROJECT"
echo "[INFO] Service account: ${SA#serviceAccount:}"

for SECRET in "${SECRETS[@]}"; do
  if ! gcloud secrets describe "$SECRET" --project="$PROJECT" >/dev/null 2>&1; then
    echo "[SKIP] Secret does not exist: $SECRET"
    continue
  fi

  gcloud secrets add-iam-policy-binding "$SECRET" \
    --member="$SA" \
    --role="roles/secretmanager.secretAccessor" \
    --project="$PROJECT" >/dev/null

  echo "[OK] Granted accessor on $SECRET"
done

echo "[OK] Done"
