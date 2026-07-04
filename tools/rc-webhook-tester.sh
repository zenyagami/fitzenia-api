#!/usr/bin/env bash
# RevenueCat webhook tester — POSTs fixture events to POST /webhooks/revenuecat.
#
# The webhook ALWAYS reconciles against RevenueCat's GET /v1/subscribers/{app_user_id} as the
# source of truth (never the event body). So to see user_entitlement.active flip true/false in
# dev, the server's REVENUECAT_REST_API_KEY must point at a RevenueCat (sandbox) project where
# APP_USER_ID is a real subscriber with/without the "premium" entitlement. TEST events short-
# circuit before any RC fetch. Identity model: app_user_id == Supabase auth.users.id.
#
# Usage:
#   BASE_URL=http://localhost:8080 \
#   AUTH='<REVENUECAT_WEBHOOK_AUTH value>' \
#   APP_USER_ID='<supabase-auth-uuid>' \
#   ./tools/rc-webhook-tester.sh [initial|expiration|test|all]
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
AUTH="${AUTH:?set AUTH to the REVENUECAT_WEBHOOK_AUTH value the server expects}"
APP_USER_ID="${APP_USER_ID:?set APP_USER_ID to the Supabase auth user uuid}"
ENV="${ENV:-SANDBOX}"            # SANDBOX | PRODUCTION
WHICH="${1:-all}"
URL="$BASE_URL/webhooks/revenuecat"

# Fresh event id per run so the idempotency table doesn't short-circuit a replay as
# already_processed. Re-POST the SAME id to verify the duplicate → 200 no-op path.
STAMP="$(date +%s)"

post() {
  local type="$1" body="$2"
  echo "── $type ──────────────────────────────────────────────"
  curl -sS -o /dev/null -w "HTTP %{http_code}\n" \
    -X POST "$URL" \
    -H "Authorization: $AUTH" \
    -H "Content-Type: application/json" \
    -d "$body"
}

initial() {
  post "INITIAL_PURCHASE" "$(cat <<JSON
{"api_version":"1.0","event":{"id":"evt_initial_${STAMP}","type":"INITIAL_PURCHASE","app_user_id":"${APP_USER_ID}","aliases":["${APP_USER_ID}"],"environment":"${ENV}","entitlement_ids":["premium"]}}
JSON
)"
}

expiration() {
  post "EXPIRATION" "$(cat <<JSON
{"api_version":"1.0","event":{"id":"evt_expire_${STAMP}","type":"EXPIRATION","app_user_id":"${APP_USER_ID}","aliases":["${APP_USER_ID}"],"environment":"${ENV}","entitlement_ids":["premium"]}}
JSON
)"
}

# TEST carries no resolvable identity intent — it must 200 and mark the row last_error='test_event'
# without any RevenueCat subscriber fetch.
test_event() {
  post "TEST" "$(cat <<JSON
{"api_version":"1.0","event":{"id":"evt_test_${STAMP}","type":"TEST","environment":"${ENV}"}}
JSON
)"
}

# Credit top-up consumable (coach_credits_5m → 5M credits). Like every other event, this only
# triggers a snapshot sync — credits are granted ONLY if the RC sandbox subscriber actually has
# the non_subscription purchase. For a pure draw-path test without a sandbox purchase, insert a
# coach_credit_topup row in dev SQL instead (see docs/AI_COACH.md manual test).
topup() {
  post "NON_RENEWING_PURCHASE" "$(cat <<JSON
{"api_version":"1.0","event":{"id":"evt_topup_${STAMP}","type":"NON_RENEWING_PURCHASE","app_user_id":"${APP_USER_ID}","aliases":["${APP_USER_ID}"],"environment":"${ENV}","product_id":"coach_credits_5m"}}
JSON
)"
}

case "$WHICH" in
  initial)    initial ;;
  expiration) expiration ;;
  test)       test_event ;;
  topup)      topup ;;
  all)        initial; expiration; test_event ;;
  *) echo "unknown selector '$WHICH' (initial|expiration|test|topup|all)"; exit 1 ;;
esac
