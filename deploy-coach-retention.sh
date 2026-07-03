set -e

PROJECT_ID="fitzenio"
REGION="europe-north1"
JOB_NAME="coach-retention-sweeper"
IMAGE_NAME="gcr.io/$PROJECT_ID/fitzenia-coach-retention"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_status()  { echo -e "${BLUE}[INFO]${NC} $1"; }
print_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
print_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
print_error()   { echo -e "${RED}[ERROR]${NC} $1"; }

print_status "Starting Coach retention-sweeper Job deployment"
print_warning "This is a PRODUCTION deployment (project=$PROJECT_ID region=$REGION)"
print_status "Project ID:  $PROJECT_ID"
print_status "Region:      $REGION"
print_status "Job name:    $JOB_NAME"

TIMESTAMP=$(date +%s%N | cut -c1-13)
print_status "Image tag (timestamp): $TIMESTAMP"

TIMESTAMP=$TIMESTAMP ./gradlew clean jib -Pprod -PtargetService=coach-retention --no-build-cache
print_success "Coach retention Docker image built and pushed: $IMAGE_NAME:$TIMESTAMP"

sed -e "s|gcr.io/PROJECT_ID/|gcr.io/$PROJECT_ID/|g" \
    -e "s|IMAGE_TAG|$TIMESTAMP|g" \
    cloud-run-job-coach-retention.yaml > cloud-run-job-coach-retention-deployed.yaml

gcloud run jobs replace cloud-run-job-coach-retention-deployed.yaml \
    --region $REGION \
    --project $PROJECT_ID

rm cloud-run-job-coach-retention-deployed.yaml

print_success "Cloud Run Job '$JOB_NAME' deployed."
print_status "Manual smoke test:"
print_status "  gcloud run jobs execute $JOB_NAME --region=$REGION --project=$PROJECT_ID --wait"
print_status "Scheduler (create once):"
print_status "  coach-retention-daily   '23 4 * * *'   (no args)"
