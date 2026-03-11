#!/bin/bash
SA="serviceAccount:693026538457-compute@developer.gserviceaccount.com"
PROJECT="fitzenio-debug"

gcloud secrets add-iam-policy-binding FATSECRET_CLIENT_ID --member="$SA" --role="roles/secretmanager.secretAccessor" --project="$PROJECT"
gcloud secrets add-iam-policy-binding FATSECRET_CLIENT_SECRET --member="$SA" --role="roles/secretmanager.secretAccessor" --project="$PROJECT"
gcloud secrets add-iam-policy-binding USDA_API_KEY --member="$SA" --role="roles/secretmanager.secretAccessor" --project="$PROJECT"
gcloud secrets add-iam-policy-binding OPENAI_API_KEY --member="$SA" --role="roles/secretmanager.secretAccessor" --project="$PROJECT"

echo "Done!"
