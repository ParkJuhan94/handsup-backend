#!/usr/bin/env bash
# 추천 API 캐시의 콜드 스타트와 TTL 만료 시점(Caffeine 5분, Redis 10분)에서 지연과 DB 조회가 어떻게 변하는지 잰다.
# 앱을 새로 띄워 캐시가 빈 상태에서 RATE로 DURATION초 동안 요청하고, 요청별 k6 CSV와 MySQL Com_select·Redis 적중 수를 1초 단위로 남긴다.
# 사용: perf/trace_ttl.sh [RATE=100] [DURATION=780] [REPS=3]   분석: python3 perf/analyze_ttl.py perf/results/ttl
set -euo pipefail
cd "$(dirname "$0")/.."
RATE="${1:-100}"; DURATION="${2:-780}"; REPS="${3:-3}"; OUT=perf/results/ttl
mkdir -p "$OUT"
set -a; . ./.env; set +a
for r in $(seq "$REPS"); do
  perf/run.sh stop
  LOGGING_LEVEL_DEV_HANDSUP_COMMON_SCHEDULER=info perf/run.sh start both
  ( while true; do
      sel=$(docker exec handsup-mysql mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW GLOBAL STATUS LIKE 'Com_select'" 2>/dev/null | awk '{print $2}')
      rd=$(docker exec handsup-redis redis-cli -a "$REDIS_HOST_PASSWORD" INFO stats 2>/dev/null | tr -d '\r' | awk -F: '/keyspace_hits|keyspace_misses/{printf "%s ", $2}')
      echo "$(date +%s) ${sel:-NA} $rd"
      sleep 1
    done ) > "$OUT/db-r$r.log" 2>&1 &
  SAMPLER=$!
  K6_OUT="csv=$OUT/ttl-r$r.csv" NO_WARMUP=1 DURATION="${DURATION}s" CACHE_MODE=both perf/run.sh test "ttl-r$r" "$RATE" | grep '^\[ttl' || true
  kill "$SAMPLER" 2>/dev/null || true
  cp perf/results/app-both.log "$OUT/app-r$r.log"
done
perf/run.sh stop
