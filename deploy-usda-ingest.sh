#!/bin/bash

# Fitzenia USDA Mirror Ingest — Cloud Run Job Deployment Script
# Builds the USDA ingest image (separate Jib mainClass) and replaces the prod Job.
# Production project ONLY — there is no dev counterpart for this Job.

set -e

PROJECT_ID="fitzenio"
REGION="europe-north1"
JOB_NAME="usda-ingest"
IMAGE_NAME="gcr.io/$PROJECT_ID/fitzenio-usda-ingest"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_status()  { echo -e "${BLUE}[INFO]${NC} $1"; }
print_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
print_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
print_error()   { echo -e "${RED}[ERROR]${NC} $1"; }

print_status "Starting USDA mirror ingest Job deployment"
print_warning "This is a PRODUCTION deployment (project=$PROJECT_ID region=$REGION)"
print_status "Project ID:  $PROJECT_ID"
print_status "Region:      $REGION"
print_status "Job name:    $JOB_NAME"

# Step 1: Build & push the ingest image (separate mainClass + image name).
TIMESTAMP=$(date +%s%N | cut -c1-13)
print_status "Image tag (timestamp): $TIMESTAMP"

TIMESTAMP=$TIMESTAMP ./gradlew clean jib -Pprod -PtargetService=usda-ingest --no-build-cache
print_success "USDA ingest Docker image built and pushed: $IMAGE_NAME:$TIMESTAMP"

# Step 2: Materialize the deployable manifest (substitute project + tag).
sed -e "s|gcr.io/PROJECT_ID/|gcr.io/$PROJECT_ID/|g" \
    -e "s|IMAGE_TAG|$TIMESTAMP|g" \
    cloud-run-job-usda-ingest.yaml > cloud-run-job-usda-ingest-deployed.yaml

# Step 3: Replace the Job. `gcloud run jobs replace` works for both create and update.
gcloud run jobs replace cloud-run-job-usda-ingest-deployed.yaml \
    --region $REGION \
    --project $PROJECT_ID

rm cloud-run-job-usda-ingest-deployed.yaml

print_success "Cloud Run Job '$JOB_NAME' deployed."
print_status "Manual smoke test:"
print_status "  gcloud run jobs execute $JOB_NAME --args=--kind=full,--dry-run --region=$REGION --project=$PROJECT_ID --wait"
print_status "Scheduler (create once via DEPLOY.md guidance):"
print_status "  usda-mirror-monthly  '37 4 1 * *'  --kind=full"
