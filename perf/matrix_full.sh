#!/usr/bin/env bash
# 캐시 계층 3종(HEAD) + 과거 커밋(fetch join, 98b5c53)을 같은 부하로 측정한다. 사용: perf/matrix_full.sh <before-jar> [RATE] [REPS]
# before-jar: 98b5c53 워크트리에 perf.cache-mode 토글(none 분기)을 얹어 빌드한 jar.
# 이전 커밋 빌드에는 gitignore된 설정(application.yml, firebase_account.json)이 없어 HEAD 것을 복사해 쓴다.
set -euo pipefail
cd "$(dirname "$0")/.."
BEFORE_JAR="$1"; RATE="${2:-100}"; REPS="${3:-5}"; QRATE="${QRATE:-10}"  # 쿼리 비교는 fetch join 버전이 100 RPS에서 포화돼 낮은 부하로 따로 잰다
export WARMUP="${WARMUP:-30s}"

# k6는 임계치 초과 시 비정상 종료하므로(포화 측정 포함) 실패해도 다음 측정을 이어간다
perf/run.sh stop; SPRING_KAFKA_TOPIC_NAME=bidding-events SPRING_PROFILES_ACTIVE=perf perf/run.sh start none "$BEFORE_JAR"
for r in $(seq "$REPS"); do CACHE_MODE=before-fetchjoin perf/run.sh test "query-before-r$r" "$QRATE" | grep '^\[query' || true; done
perf/run.sh stop; perf/run.sh start none
for r in $(seq "$REPS"); do CACHE_MODE=none perf/run.sh test "query-after-r$r" "$QRATE" | grep '^\[query' || true; done
for mode in none redis both; do
  perf/run.sh stop; perf/run.sh start "$mode"
  for r in $(seq "$REPS"); do CACHE_MODE=$mode perf/run.sh test "cache-$mode-r$r" "$RATE" | grep '^\[cache' || true; done
done
perf/run.sh stop
