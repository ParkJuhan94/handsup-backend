#!/usr/bin/env bash
# 추천 API 성능 측정 도우미. 인프라는 handsup-mysql/redis/redpanda(docker compose)를 쓰고, DB는 격리된 handsup_perf.
#   perf/run.sh infra                      mysql·redis·redpanda 기동
#   perf/run.sh seed                       대량 시드(SEED_N, 기본 5000)
#   perf/run.sh build [워크트리경로]        bootJar 빌드 (기본: 현재 backend)
#   perf/run.sh start <cache-mode> [jar]   앱 기동 (none|redis|both) - 힙·포트 고정
#   perf/run.sh test <label> [RATE]        k6 실행, perf/results/<label>.json 저장
#   perf/run.sh bid <same|ascending> <경매ID>  동시 입찰 정합성 테스트(초기화 -> 100명 동시 입찰 -> DB 검증)
#   perf/run.sh bid-hot [RATE] [라벨]          인기 경매 편중 입찰 처리량 측정(시드 초기화 -> 부하 -> 전체 정합성 검증)
#   perf/run.sh stop                       앱 종료 (포트 바인딩 기준)
set -euo pipefail
# 측정 중 맥이 잠들면 앱·k6가 함께 멈춰 지연이 튄다(2026-10-25 trace 추적에서 확인). k6는 caffeinate -i로 감싸 슬립을 막는다
cd "$(dirname "$0")/.."
PORT=8083
set -a; . ./.env; set +a
JAVA_OPTS_FIXED="-Xms512m -Xmx512m -XX:+UseG1GC ${EXTRA_JAVA_OPTS:-}"  # EXTRA_JAVA_OPTS: GC 로그 등 추적용

case "${1:-}" in
  infra) docker compose up -d mysql redis redpanda ;;
  seed)
    docker exec handsup-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "CREATE DATABASE IF NOT EXISTS handsup_perf CHARACTER SET utf8mb4" 2>/dev/null
    { echo "SET @n=${SEED_N:-5000};"; cat perf/seed_bulk.sql; } | docker exec -i handsup-mysql mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" handsup_perf 2>/dev/null ;;
  build)
    dir="${2:-.}"; (cd "$dir" && ./gradlew :api:bootJar -x test -q) && ls "$dir"/api/build/libs/*.jar ;;
  start)
    mode="$2"; jar="${3:-$(ls api/build/libs/api-*-SNAPSHOT.jar | head -1)}"
    docker exec handsup-redis redis-cli -a "$REDIS_HOST_PASSWORD" FLUSHALL >/dev/null 2>&1
    MYSQL_DATABASE=handsup_perf SERVER_PORT=$PORT SPRING_JPA_SHOW_SQL=false \
      LOGGING_LEVEL_ORG_HIBERNATE_SQL=warn LOGGING_LEVEL_ORG_HIBERNATE_TOOL_HBM2DDL=warn \
      LOGGING_LEVEL_ROOT=warn LOGGING_LEVEL_ORG_SPRINGFRAMEWORK=warn \
      nohup java $JAVA_OPTS_FIXED -Dperf.cache-mode="$mode" -jar "$jar" > "perf/results/app-$mode.log" 2>&1 &
    for _ in $(seq 60); do
      [ "$(curl -s -o /dev/null -w '%{http_code}' "localhost:$PORT/api/auctions/recommend?sort=BOOKMARK")" = 200 ] && { echo "app ready ($mode)"; exit 0; }
      sleep 2
    done
    echo "app failed to start"; tail -20 "perf/results/app-$mode.log"; exit 1 ;;
  test)
    label="$2"; rate="${3:-200}"; mkdir -p perf/results
    caffeinate -i k6 run ${K6_OUT:+--out "$K6_OUT"} -e LABEL="$label" -e RATE="$rate" -e CACHE_MODE="${CACHE_MODE:-}" -e GIT_HEAD="$(git rev-parse --short HEAD)" perf/recommend.js ;;
  bid)
    mode="$2"; aid="$3"; mkdir -p perf/results
    { echo "SET @a=$aid;"; cat perf/bid_reset.sql; } | docker exec -i handsup-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" handsup_perf 2>/dev/null
    docker exec handsup-redis redis-cli -a "$REDIS_HOST_PASSWORD" FLUSHALL >/dev/null 2>&1
    caffeinate -i k6 run -e MODE="$mode" -e AUCTION_ID="$aid" perf/bid_concurrency.js | grep -E "^\[bid|ERRO|error" || true
    { echo "SET @a=$aid;"; cat perf/bid_verify.sql; } | docker exec -i handsup-mysql mysql -t -uroot -p"$MYSQL_ROOT_PASSWORD" handsup_perf 2>/dev/null ;;
  bid-hot)
    rate="${2:-100}"; label="${3:-bid-hot}"; mkdir -p perf/results
    echo "seeding 500k bids..."
    docker exec -i handsup-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" handsup_perf < perf/bid_seed.sql 2>/dev/null | tail -1
    docker exec handsup-redis redis-cli -a "$REDIS_HOST_PASSWORD" FLUSHALL >/dev/null 2>&1
    caffeinate -i k6 run -e RATE="$rate" -e LABEL="$label" perf/bid_hot.js | grep -E "^\[(bid|$label)|ERRO|error" || true
    docker exec -i handsup-mysql mysql -t -uroot -p"$MYSQL_ROOT_PASSWORD" handsup_perf < perf/bid_verify_all.sql 2>/dev/null ;;
  stop)
    pids=$(lsof -nP -tiTCP:$PORT -sTCP:LISTEN || true); [ -n "$pids" ] && kill $pids || true ;;
  *) sed -n 2,11p "$0"; exit 1 ;;
esac
