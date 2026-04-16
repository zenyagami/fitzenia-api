#!/bin/bash

# Fitzenio API - Google Cloud Run DEVELOPMENT Deployment Script
# This script automates the development deployment process

set -e  # Exit on any error

# Configuration
PROJECT_ID="fitzenio-debug"
REGION="europe-north1"
SERVICE_NAME="fitzenia-api-dev"
IMAGE_NAME="gcr.io/$PROJECT_ID/$SERVICE_NAME"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
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

# Check if PROJECT_ID is set
if [ -z "$PROJECT_ID" ]; then
    print_error "Please set the PROJECT_ID variable at the top of this script"
    exit 1
fi

print_status "Starting DEVELOPMENT deployment for Fitzenia API..."
print_status "Project ID: $PROJECT_ID"
print_status "Region: $REGION"
print_status "Service Name: $SERVICE_NAME"
print_warning "This is a DEVELOPMENT deployment with debug mode enabled!"

# Step 1: Build and push Docker image using Jib (with correct platform)
print_status "Building and pushing Docker image for development using Jib..."

# Generate timestamp for unique image tag
TIMESTAMP=$(date +%s%N | cut -c1-13)
print_status "Using timestamp: $TIMESTAMP"

TIMESTAMP=$TIMESTAMP ./gradlew jib
print_success "Development Docker image built and pushed successfully with correct platform"

# Step 4: Deploy to Cloud Run
print_status "Deploying to Cloud Run (Development)..."

# Replace PROJECT_ID and IMAGE_TAG in config file
sed -e "s|gcr.io/PROJECT_ID/|gcr.io/$PROJECT_ID/|g" \
    -e "s|@PROJECT_ID\.|@$PROJECT_ID.|g" \
    -e "s|IMAGE_TAG|$TIMESTAMP|g" \
    cloud-run-config.dev.yaml > cloud-run-config-dev-deployed.yaml

gcloud run services replace cloud-run-config-dev-deployed.yaml \
    --platform managed \
    --region $REGION \
    --project $PROJECT_ID

# Clean up temporary config file
rm cloud-run-config-dev-deployed.yaml

print_success "Development deployment completed successfully!"

# Get service URL
SERVICE_URL=$(gcloud run services describe $SERVICE_NAME --platform managed --region $REGION --project $PROJECT_ID --format 'value(status.url)')

print_success "Development service is available at: $SERVICE_URL"
print_status "Health check: $SERVICE_URL/health"

# Test the deployment
print_status "Testing development deployment..."
if curl -f "$SERVICE_URL/health" > /dev/null 2>&1; then
    print_success "Health check passed!"
else
    print_warning "Health check failed. Service might still be starting up."
fi

print_warning "Development Configuration:"
print_warning "- Firebase Project: fitzenia-dev"
print_warning "- Google Play Debug Mode: ENABLED"
print_warning "- Resource Limits: 0.5 CPU, 512Mi Memory"
print_warning "- Max Scale: 3 instances"

print_success "Development deployment script completed!"
