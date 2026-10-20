#!/usr/bin/env bash
# Rough latency numbers against a running stack (docker compose up).
# Usage: bench/bench.sh [base-url] [accounts]
set -euo pipefail

BASE=${1:-http://localhost:8080}
ACCOUNTS=${2:-24}
RUNS=${RUNS:-5}

median() { sort -n | awk '{a[NR]=$1} END {print (NR % 2) ? a[(NR+1)/2] : (a[NR/2] + a[NR/2+1]) / 2}'; }
p95() { sort -n | awk '{a[NR]=$1} END {i=int(NR*0.95+0.999); if (i>NR) i=NR; print a[i]}'; }

echo "CPU: $(LC_ALL=C lscpu | sed -n 's/^Model name: *//p' | head -1), $(nproc) threads"
echo

echo "Single statement PDF (GET /api/reports/statement/3), 5 warm-up + 30 measured requests"
for _ in $(seq 5); do curl -sf -o /dev/null "$BASE/api/reports/statement/3"; done
times=$(for _ in $(seq 30); do curl -sf -o /dev/null -w '%{time_total}\n' "$BASE/api/reports/statement/3"; done)
echo "  median $(echo "$times" | median) s, p95 $(echo "$times" | p95) s"
echo

echo "Combined report: $ACCOUNTS statements + 1 summary, median of $RUNS runs (server-side elapsedMillis)"
echo "| concurrency | median ms | runs (ms) |"
echo "|---|---|---|"
curl -sfN "$BASE/api/combined-reports/stream?accounts=4" > /dev/null # warm-up
for c in 1 2 4 6 8; do
  runs=()
  for _ in $(seq "$RUNS"); do
    ms=$(curl -sfN "$BASE/api/combined-reports/stream?accounts=$ACCOUNTS&concurrency=$c" \
      | sed -n 's/.*"elapsedMillis":\([0-9]*\).*/\1/p')
    runs+=("$ms")
  done
  echo "| $c | $(printf '%s\n' "${runs[@]}" | median) | ${runs[*]} |"
done

echo
echo "Mean Gotenberg call time since app start (from /actuator/metrics/gotenberg.requests)"
for op in convert-html merge; do
  curl -sf "$BASE/actuator/metrics/gotenberg.requests?tag=operation:$op&tag=outcome:success" \
    | sed -n 's/.*"COUNT","value":\([0-9.E]*\)}.*"TOTAL_TIME","value":\([0-9.E-]*\)}.*/\1 \2/p' \
    | awk -v op="$op" '{printf "  %s: %d calls, mean %.0f ms\n", op, $1, $2 / $1 * 1000}'
done
