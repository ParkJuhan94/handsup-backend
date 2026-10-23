#!/usr/bin/env bash
# 캐시 계층 3종 x 반복 N회를 같은 부하(RATE)로 순서대로 측정한다. 사용: perf/matrix.sh [RATE] [REPS]
set -euo pipefail
cd "$(dirname "$0")/.."
RATE="${1:-300}"; REPS="${2:-3}"
for mode in none redis both; do
  perf/run.sh stop; perf/run.sh start "$mode"
  for r in $(seq "$REPS"); do CACHE_MODE=$mode perf/run.sh test "cache-$mode-r$r" "$RATE" | grep '^\[cache'; done
done
perf/run.sh stop
