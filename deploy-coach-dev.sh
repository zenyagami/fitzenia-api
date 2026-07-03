#!/usr/bin/env bash
set -e  # Exit on any error

PROJECT_ID="fitzenio-debug"
REGION="europe-north1"
SERVICE_NAME="fitzenia-coach-dev"
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

default_env_file() {
    local candidate
    for candidate in .env.dev .env.development .env; do
        if [ -f "$candidate" ]; then
            printf '%s' "$candidate"
            return 0
        fi
    done
    return 1
}

if [ -z "$PROJECT_ID" ]; then
    print_error "Please set the PROJECT_ID variable at the top of this script"
    exit 1
fi

ENV_FILE="${ENV_FILE:-}"
if [ -z "$ENV_FILE" ]; then
    ENV_FILE="$(default_env_file || true)"
fi

if [ -n "$ENV_FILE" ]; then
    print_status "Loading optional dev deploy env from: $ENV_FILE"
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
else
    print_warning "No .env.dev, .env.development, or .env found; using default coach dev bypass values."
fi

COACH_DEV_PREMIUM_ALL="${COACH_DEV_PREMIUM_ALL:-false}"
COACH_DEV_PREMIUM_USER_IDS="${COACH_DEV_PREMIUM_USER_IDS:-}"

print_status "Starting DEVELOPMENT deployment for Fitzenia AI Coach..."
print_status "Project ID: $PROJECT_ID"
print_status "Region: $REGION"
print_status "Service Name: $SERVICE_NAME"
print_warning "This is a DEVELOPMENT deployment."

print_status "Building and pushing Docker image for development using Jib..."

TIMESTAMP=$(date +%s%N | cut -c1-13)
print_status "Using timestamp: $TIMESTAMP"

TIMESTAMP=$TIMESTAMP ./gradlew clean jib -PtargetService=coach --no-build-cache
print_success "Coach development Docker image built and pushed successfully"

print_status "Deploying to Cloud Run (Development)..."

sed -e "s|gcr.io/PROJECT_ID/|gcr.io/$PROJECT_ID/|g" \
    -e "s|@PROJECT_ID\.|@$PROJECT_ID.|g" \
    -e "s|IMAGE_TAG|$TIMESTAMP|g" \
    cloud-run-config.coach.dev.yaml > cloud-run-config-coach-dev-deployed.yaml

gcloud run services replace cloud-run-config-coach-dev-deployed.yaml \
    --platform managed \
    --region $REGION \
    --project $PROJECT_ID

rm cloud-run-config-coach-dev-deployed.yaml

print_status "Allowing public Cloud Run access (app-level Supabase JWT auth still applies)..."

gcloud run services update $SERVICE_NAME \
    --no-invoker-iam-check \
    --region $REGION \
    --project $PROJECT_ID >/dev/null

print_status "Applying coach dev premium bypass env vars..."

gcloud run services update $SERVICE_NAME \
    --region $REGION \
    --project $PROJECT_ID \
    --update-env-vars "^|^COACH_DEV_PREMIUM_ALL=$COACH_DEV_PREMIUM_ALL|COACH_DEV_PREMIUM_USER_IDS=$COACH_DEV_PREMIUM_USER_IDS" >/dev/null

print_success "Development deployment completed successfully!"

SERVICE_URL=$(gcloud run services describe $SERVICE_NAME --platform managed --region $REGION --project $PROJECT_ID --format 'value(status.url)')

print_success "Service is available at: $SERVICE_URL"
print_status "Health check: $SERVICE_URL/health"

print_status "Testing deployment..."
if curl -f "$SERVICE_URL/health" > /dev/null 2>&1; then
    print_success "Health check passed!"
else
    print_warning "Health check failed. Service might still be starting up."
fi

print_warning "Development Configuration:"
print_warning "- Cloud project: fitzenio-debug"
print_warning "- Service: fitzenia-coach-dev"
print_warning "- APP_ENVIRONMENT: DEVELOPMENT"
print_warning "- COACH_DEV_PREMIUM_ALL: $COACH_DEV_PREMIUM_ALL"
if [ -n "$COACH_DEV_PREMIUM_USER_IDS" ]; then
    print_warning "- COACH_DEV_PREMIUM_USER_IDS: configured"
else
    print_warning "- COACH_DEV_PREMIUM_USER_IDS: empty"
fi
print_warning "- RevenueCat webhook is still on fitzenia-api-dev, not this coach service."

print_success "Development deployment script completed!"
