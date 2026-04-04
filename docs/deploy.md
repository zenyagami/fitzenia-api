# 1) make sure gcloud is authenticated
gcloud auth login
gcloud config set project fitzenio-debug
# 2) load your local .env values into shell
set -a
source .env
set +a
# 3) paste helper function
upsert_secret () {
PROJECT="$1"
NAME="$2"
VALUE="$3"

if gcloud secrets describe "$NAME" --project="$PROJECT" >/dev/null 2>&1; then
printf %s "$VALUE" | gcloud secrets versions add "$NAME" --data-file=- --project="$PROJECT"
else
printf %s "$VALUE" | gcloud secrets create "$NAME" --data-file=- --replication-policy=automatic --project="$PROJECT"
fi
}

# 4) push Supabase secrets to GCP
upsert_secret fitzenio-debug SUPABASE_DEV_URL "$SUPABASE_DEV_URL"
upsert_secret fitzenio-debug SUPABASE_DEV_SERVICE_ROLE_KEY "$SUPABASE_DEV_SERVICE_ROLE_KEY"
upsert_secret fitzenio SUPABASE_URL "$SUPABASE_URL"
upsert_secret fitzenio SUPABASE_SERVICE_ROLE_KEY "$SUPABASE_SERVICE_ROLE_KEY"


PROJECT="fitzenio-debug"
SA="serviceAccount:693026538457-compute@developer.gserviceaccount.com"

gcloud secrets add-iam-policy-binding SUPABASE_DEV_URL \
--project="$PROJECT" \
--member="$SA" \
--role="roles/secretmanager.secretAccessor"

gcloud secrets get-iam-policy SUPABASE_DEV_URL --project="$PROJECT"
gcloud secrets get-iam-policy SUPABASE_DEV_SERVICE_ROLE_KEY --project="$PROJECT"


