#!/bin/bash
# monitor.sh — Real-time Racer performance dashboard
#
# Polls key metrics from Prometheus, Redis, and the JVM every 5 seconds
# and prints a summary to stdout.
#
# Usage:
#   chmod +x monitor.sh
#   ./monitor.sh                         # stream to terminal
#   ./monitor.sh > metrics.log &         # run in background during perf test
#
# Prerequisites:
#   - NotifyHub (or any Racer-powered app) running on localhost:8080
#   - Redis running on localhost:6379 (redis-cli available in PATH)
#   - curl, grep available in PATH

ACTUATOR_URL="${ACTUATOR_URL:-http://localhost:8080/actuator/prometheus}"
STREAM_KEY="${STREAM_KEY:-racer:orders}"
CONSUMER_GROUP="${CONSUMER_GROUP:-racer:orders-group}"

while true; do
  clear
  echo "=== Racer Perf Test Dashboard  $(date) ==="
  echo ""

  echo "--- Published Messages ---"
  curl -sf "${ACTUATOR_URL}" | grep -E "^racer_publisher_published_total" || echo "  (no data)"

  echo ""
  echo "--- Processed / Failed ---"
  curl -sf "${ACTUATOR_URL}" | grep -E "^racer_listener_(processed|failed)_total" || echo "  (no data)"

  echo ""
  echo "--- Back-pressure drops ---"
  curl -sf "${ACTUATOR_URL}" | grep -E "^racer_back_pressure_drops_total" || echo "  (no data)"

  echo ""
  echo "--- Circuit Breaker State ---"
  curl -sf "${ACTUATOR_URL}" | grep -E "^racer_circuit_breaker_state" || echo "  (no data)"

  echo ""
  echo "--- Dedup Duplicates ---"
  curl -sf "${ACTUATOR_URL}" | grep -E "^racer_dedup_duplicates_total" || echo "  (no data)"

  echo ""
  echo "--- Thread Pool ---"
  curl -sf "${ACTUATOR_URL}" | grep -E "^racer_thread_pool_(queue_depth|active_count)" || echo "  (no data)"

  echo ""
  echo "--- Stream Consumer Lag ---"
  redis-cli XINFO STREAM "${STREAM_KEY}" 2>/dev/null | grep -E "length|last-generated-id" || echo "  (redis-cli unavailable)"
  redis-cli XPENDING "${STREAM_KEY}" "${CONSUMER_GROUP}" - + 10 2>/dev/null || true

  echo ""
  echo "--- Redis Command Stats ---"
  redis-cli INFO commandstats 2>/dev/null | grep -E "cmd=(publish|xadd|xreadgroup|set|get)" | head -10 || echo "  (redis-cli unavailable)"

  sleep 5
done
