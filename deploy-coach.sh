set -e  # Exit on any error

# Production deploy for the AI Coach Ktor service. Mirrors deploy.sh but builds
# the coach image (-PtargetService=coach) and deploys cloud-run-config.coach.yaml.
PROJECT_ID="fitzenio"
REGION="europe-north1"
SERVICE_NAME="fitzenia-coach-prod"
IMAGE_NAME="gcr.io/$PROJECT_ID/$SERVICE_NAME"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

if [ -z "$PROJECT_ID" ]; then
    print_error "Please set the PROJECT_ID variable at the top of this script"
    exit 1
fi

print_status "Starting PRODUCTION deployment for Fitzenia AI Coach..."
print_warning "This is a PRODUCTION deployment!"
print_status "Project ID: $PROJECT_ID"
print_status "Region: $REGION"
print_status "Service Name: $SERVICE_NAME"

print_status "Building and pushing Docker image for production using Jib..."

TIMESTAMP=$(date +%s%N | cut -c1-13)
print_status "Using timestamp: $TIMESTAMP"

TIMESTAMP=$TIMESTAMP ./gradlew clean jib -Pprod -PtargetService=coach --no-build-cache
print_success "Coach Docker image built and pushed successfully"

print_status "Deploying to Cloud Run..."

sed -e "s|gcr.io/PROJECT_ID/|gcr.io/$PROJECT_ID/|g" \
    -e "s|@PROJECT_ID\.|@$PROJECT_ID.|g" \
    -e "s|IMAGE_TAG|$TIMESTAMP|g" \
    cloud-run-config.coach.yaml > cloud-run-config-coach-deployed.yaml

gcloud run services replace cloud-run-config-coach-deployed.yaml \
    --platform managed \
    --region $REGION \
    --project $PROJECT_ID

rm cloud-run-config-coach-deployed.yaml

# Public access at the Cloud Run layer (idempotent). `services replace` does NOT set IAM,
# so a brand-new service defaults to authenticated-only → /health returns a front-end 403.
# The app still enforces the Supabase JWT + PremiumGate on every /api/coach/** route; only
# /health is meant to be anonymous. Mirrors fitzenia-api-prod's allUsers→run.invoker binding.
print_status "Granting public invoker (allUsers) — app-level JWT auth still applies"
gcloud run services add-iam-policy-binding $SERVICE_NAME \
    --platform managed \
    --region $REGION \
    --project $PROJECT_ID \
    --member=allUsers \
    --role=roles/run.invoker

print_success "Deployment completed successfully!"

SERVICE_URL=$(gcloud run services describe $SERVICE_NAME --platform managed --region $REGION --project $PROJECT_ID --format 'value(status.url)')

print_success "Service is available at: $SERVICE_URL"
print_status "Health check: $SERVICE_URL/health"

print_status "Testing deployment..."
if curl -f "$SERVICE_URL/health" > /dev/null 2>&1; then
    print_success "Health check passed!"
else
    print_warning "Health check failed. Service might still be starting up."
fi

print_success "Deployment script completed!"
