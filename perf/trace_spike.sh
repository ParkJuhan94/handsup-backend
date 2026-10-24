#!/usr/bin/env bash
# 튀는 실행(p99 급등)의 원인 추적용 반복 측정. 사용: perf/trace_spike.sh [RATE] [REPS]
# 앱 GC 로그(wall-clock 시각 포함), 요청별 k6 CSV, MySQL 컨테이너 CPU 샘플(1초)을 같은 시간축으로 남겨 튄 시각을 대조한다.
set -euo pipefail
cd "$(dirname "$0")/.."
RATE="${1:-10}"; REPS="${2:-15}"; OUT=perf/results/trace
set -a; . ./.env; set +a
perf/run.sh stop
EXTRA_JAVA_OPTS="-Xlog:gc*:file=$OUT/gc.log:time,uptime" perf/run.sh start none
( while true; do echo "$(date +%s) $(docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' handsup-mysql)"; sleep 1; done ) > $OUT/mysql-cpu.log 2>&1 &
SAMPLER=$!
trap 'kill $SAMPLER 2>/dev/null || true; perf/run.sh stop' EXIT
for r in $(seq "$REPS"); do
  echo "run $r start $(date +%s)" >> $OUT/runs.log
  K6_OUT="csv=$OUT/after-r$r.csv" CACHE_MODE=none WARMUP=30s perf/run.sh test "trace-after-r$r" "$RATE" | grep '^\[trace' || true
  echo "run $r end $(date +%s)" >> $OUT/runs.log
done
