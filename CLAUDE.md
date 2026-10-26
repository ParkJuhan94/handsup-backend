# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build
./gradlew build                    # Build with tests
./gradlew build -x test            # Build without tests

# Test
./gradlew test                     # Run all tests
./gradlew :core:test               # Run core module tests only
./gradlew :api:test                # Run api module tests only
./gradlew test --tests '*ServiceTest'  # Run tests matching pattern

# Coverage
./gradlew jacocoTestReport         # Generate coverage report (HTML in build/reports/jacoco)

# Run
./gradlew :api:bootRun             # Start the application
```

## Project Architecture

**Multi-module Gradle project** (Java 17, Spring Boot 3.2.0):

- **api** - REST controllers, request/response DTOs, WebSocket endpoints. Entry point: `HandsUpApplication.java`
- **core** - Business logic, domain entities, services, repositories. Contains QueryDSL configuration
- **event** - Kafka producers/consumers for async event processing
- **common** - Shared utilities (java-library, no Spring Boot)

Module dependencies: `api → core, event` | `event → core`

## Domain Model

Peer-to-peer auction marketplace with these core entities:

- **Auction** (aggregate root) - Contains Product, status lifecycle (BIDDING → TRADING → COMPLETED)
- **Bidding** - User bids with trading status (WAITING → PREPARING → PROGRESSING → COMPLETED/CANCELED)
- **User** - Participants with reputation score (0-200)
- **ChatRoom/ChatMessage** - Seller-bidder communication
- **Review** - Post-transaction ratings affecting user score

Domain packages follow: `domain/`, `service/`, `repository/`, `dto/`, `exception/`

## Key Patterns

- **Layered + DDD**: Rich domain entities with validation in constructors
- **Event-Driven**: Kafka for async processing (BiddingEventProducer/Listener)
- **Query Repositories**: QueryDSL for complex queries (e.g., `AuctionQueryRepository`)
- **Schedulers**: `AuctionScheduler`, `BiddingScheduler` for status transitions
- **Caching**: Caffeine (local) + Redis (distributed)

## Testing

Test base classes in `core/src/test/java/dev/handsup/common/support/`:

- `DataJpaTestSupport` - Repository tests with TestContainers (MySQL + Redis)
- `ApiTestSupport` - Controller integration tests with MockMvc
- `ApiTestKafkaSupport` - Tests requiring embedded Kafka

Fixtures in `core/src/testFixtures/java/dev/handsup/fixture/`:
- `AuctionFixture`, `UserFixture`, `BiddingFixture`, etc. - Static factory methods for test data

## External Services

- MySQL (primary DB), Redis (cache/locking), Kafka (events)
- AWS S3 (images), Firebase FCM (push notifications), OpenAI API

## 작업 기록

### 2026-07-06 - EC2 App ↔ 외부 EC2 Kafka 연동 문제 해결 및 운영 설정 마무리

**배경**
- App은 EC2 A(main push → `cd.yml`)에, Kafka는 별도 EC2 B(dev push → `cd-dev.yml`, `docker-compose.dev.yml`)에 배포되는 분리 구조.
- 배포된 App이 외부 Kafka 브로커에 붙지 못하고 `Bootstrap broker ... disconnected` 경고가 반복되던 것을 추적/해결.

**해결한 근본 원인 (3가지)**
- **포트 불일치**: App의 `KAFKA_BOOTSTRAP_SERVERS`가 `:9092`를 가리켰으나, Kafka의 외부 접속 포트는 `29092`(컨테이너 EXTERNAL 리스너 `9094` → 호스트 `29092`). 내부 `9092`는 호스트에 퍼블리시되지 않아 도달 불가. → App 시크릿을 `172.31.38.179:29092`로 수정.
- **advertised host**: Kafka의 `KAFKA_ADVERTISED_HOST`를 퍼블릭 IP로 두면 EC2 재시작 시 IP가 바뀌어 광고 주소가 낡음. 두 EC2가 같은 VPC(`172.31.x`)이므로 **프라이빗 IP `172.31.38.179`로 통일** (재부팅에도 고정, 인터넷 미경유, 브로커 미노출). 확인 결과 이미 프라이빗으로 설정돼 있었음.
- **보안그룹**: Kafka EC2 SG에 `29092` 인바운드 추가, Source를 App EC2 SG(`sg-0f371a0e8279f12fb`) 또는 VPC CIDR로 제한. (기존 CIDR 규칙은 SG 참조로 in-place 변경 불가 → 삭제 후 재생성 필요)

**결과 (검증됨)**
- App 로그에서 `disconnected` 경고 소멸. 대신 `UNKNOWN_TOPIC_OR_PARTITION`(bidding-events.dlt-retry/dlt-dlt)만 남음 = **브로커 연결 성공**, 새 클러스터라 토픽 미생성 상태(auto-create로 첫 메시지 시 생성). 네트워크/포트/advertised 이슈 모두 해결.

**변경 파일 (미커밋)**
- `api/src/main/resources/application-prod.yml` - `ddl-auto: update → validate`. 첫 배포용 임시 `update`(커밋 `be7bc6f`)를 앱 안정화 후 되돌림. 운영에서 update 상시 유지는 의도치 않은 스키마 변경 위험.
- `.gitignore` - `docs/PORTFOLIO_HIGHLIGHTS.md` 로컬 전용 무시 추가.
- `docs/PORTFOLIO_HIGHLIGHTS.md` - (gitignore됨) 포트폴리오 기록 파일 신규.

**결정 사항**
- Kafka 연동은 **프라이빗 IP 기준**으로 통일. App/Kafka 양쪽 IP·포트 정합성이 핵심(bootstrap 대상과 advertised host가 같은 도달 가능 주소여야 함).
- App EC2에 EIP 미부착 시 재시작으로 퍼블릭 IP가 바뀌면 `EC2_HOST` 시크릿과 불일치 → CD의 SCP가 `i/o timeout`. 근본 해결책으로 **EIP 부착 권장**(미적용).
- GitHub Secret(`ENV_CONTENT`/`ENV_DEV_CONTENT`) 값은 CD 워크플로에서만 EC2로 주입됨 → 시크릿 변경은 **반드시 push(또는 워크플로 재실행)** 로 반영. EC2에서 `compose up`만 하면 옛 `.env`를 읽어 미반영.

**다음 작업**
- 미커밋 변경 커밋 필요: `application-prod.yml`(ddl-auto validate 복귀), `.gitignore`. 제안 메시지: `fix(prod): 앱 안정화 완료로 ddl-auto를 validate로 복귀`.
- `validate` 적용은 **main push로 CD 트리거 시** 발효. 배포 후 `docker logs hands-up-app`에서 `Schema-validation` 오류 없이 기동하는지 확인 필수(불일치 시 기동 거부).
- Kafka end-to-end 검증: 실제 입찰 발생 → kafka-ui(`http://<KafkaIP>:8080`)에서 `bidding-events` 토픽 메시지 증가 확인.
- CLUSTER_ID 백업: `docker exec hands-up-kafka cat /var/lib/kafka/data/meta.properties`의 `cluster.id`를 안전한 곳에 보관하고 `ENV_DEV_CONTENT` 시크릿 값과 일치 확인(불일치 시 재배포 실패, 새 발급은 볼륨 초기화 필요).
- dev ↔ main 브랜치 동기화 필요(main에 직접 커밋한 CI/CD 작업들이 dev에 미반영).
- `gc-logs/` 미추적 디렉터리 존재 - gitignore 여부 검토 필요.

### 2026-10-23 - Kafka 단일 EC2 통합(Redpanda 교체)
**변경 사항**
- 별도 EC2(handsup-kafka 저장소)에서 운영하던 Kafka를 앱 EC2의 `docker-compose.prod.yml`로 통합하고 브로커를 Apache Kafka에서 Redpanda(v24.2.18)로 교체. 외부 리스너·호스트 포트(29092 PLAINTEXT)를 없애고 앱은 `hands-up-redpanda:9092`로 접속(compose에 직접 지정 - `.env`의 `KAFKA_BOOTSTRAP_SERVERS`는 더 이상 쓰지 않음).
- 확인 UI는 kafka-ui(8080) 대신 Redpanda Console을 `--profile redpanda-console`로 온디맨드 기동(`127.0.0.1:8091`, SSH 터널 전용). 앱이 호스트 8080을 쓰므로 8091로 옮김.

**결정 사항**
- 분리의 이점(장애 격리)보다 비용·운영 부담(EC2 2대, advertised host 낡음, SG 규칙, PLAINTEXT 외부 노출)이 커서 통합. 메모리 부담은 Redpanda(네이티브)로 상쇄 - 로컬 검증에서 유휴 약 73MiB, 한도 900m.
- CD가 하이픈 버전 `docker-compose`(v1)라 `deploy.resources` 대신 `mem_limit`을 사용(v1은 deploy 한도를 무시).

**다음 작업**
- 머지 후 운영 전환 절차(EC2 A 메모리 확인 → 배포 → 입찰 이벤트 E2E 확인 → EC2 B 종료 → handsup-kafka 저장소 CD 중단/아카이브)는 PR 본문 체크리스트 참고. EC2 A 인스턴스 타입(메모리)은 미확인.

### 2026-10-23 - 추천 API 로컬 성능 측정(k6)
**변경 사항**
- `perf/`에 k6 스크립트(`recommend.js`), 실행 도우미(`run.sh`), 반복 측정(`matrix.sh`, `matrix_full.sh`), 대량 시드(`seed_bulk.sql`)를 추가. 격리 DB `handsup_perf`에 경매 5,000건을 넣고 JVM 힙 512m·포트 8083 고정으로 측정.
- `AuctionCacheService`에 `perf.cache-mode`(none|redis|both) 토글 추가. 기본값 `both`라 운영 동작 그대로.

**측정 결과 (로컬 맥, 5회 중앙값, 결과 JSON은 `perf/results/`)**
- 쿼리 개선(fetch join `98b5c53` → DTO 조회+대표 이미지 `is_main` HEAD, 10 RPS): p50 24.9→11.0ms, p95 267→24.7ms, p99 351→90ms. Before는 100 RPS에서 포화(p50 10초, 드롭 5,237건).
- 캐시 3모드(100 RPS): p50 DB 7.4ms / Redis 2.1ms / Caffeine+Redis 1.1ms, p95 10.8 / 3.3 / 2.5ms.

**결정 사항**
- 부하 동일성은 고정 도착률(constant-arrival-rate)과 결정적 요청 순서로 보장. 쿼리 비교(10 RPS)와 캐시 비교(100 RPS)는 부하가 달라 표를 가로 비교하지 않는다.
- After 기준은 `2ba27ad`가 아니라 HEAD. `2ba27ad`는 이미지가 여러 장이면 서브쿼리 오류(500)라 `0e70faa`(#39)의 `is_main` 이후와 비교.
- 이전 커밋 빌드에는 gitignore된 `application.yml`·`firebase_account.json`이 없어 HEAD 것을 복사(쿼리 코드는 그대로, 토글만 추가).

**주의**
- 모드 전환 직후 첫 실행(r1)은 JIT 워밍업 영향으로 드롭이 생김(워밍업 30초로도 남음). 이력서용 수치는 r2~r5 기준.
- 로컬 상대 비교용이며 운영 절대 수치가 아님.

### 2026-10-25 - 입찰 락 점유 시간 개선과 동시 입찰 정합성 테스트
**변경 사항**
- `bidding(auction_id, bidding_price)` 복합 인덱스 추가, 입찰 가격 검증을 `MAX` 쿼리 대신 경매의 현재가·입찰 수로 변경, 입찰의 락 대기 5초 → 1초, 락 대기 초과를 429로 응답(`LockAcquisitionException`, 코드 `rl_001` 유지).
- `perf/`에 동시 입찰 정합성 테스트(`bid_concurrency.js`), 인기 경매 편중 부하(`bid_hot.js`), 입찰 50만 건 시드(`bid_seed.sql`), 전체 정합성 검증 SQL(`bid_verify_all.sql`) 추가. 측정 중 맥 슬립 방지로 k6를 `caffeinate -i`로 감쌈.

**측정 결과 (로컬 맥, 입찰 50만 건, 100 RPS 5회 중앙값, 요청의 80%가 경매 1건)**
- 수락 16.0 → 99.7건/초, p95 13,580 → 8ms, 드롭 약 940 → 0. 300 RPS도 수락 292.8건/초·드롭 0, 600 RPS에서 포화(429 1,332건). 정합성 불일치는 전 구간 0.
- 원인: 락 안의 `MAX(bidding_price) WHERE auction_id=?`가 인덱스 없이 전체 스캔(약 49.8만 행, 43~54ms). 인덱스 후 약 0.1ms.

**결정 사항**
- 락 제거(조건부 UPDATE, B안)는 보류. 락을 유지한 채 300 RPS까지 소화돼 필요성이 확인되지 않음.
- query-after-r4 급등은 GC·MySQL이 아니라 측정 중 맥 정지로 판단(15회 재측정 r11에서 재현, 독립 샘플러 기록도 같은 시각에 끊김).

**주의 / 다음 작업**
- ⚠️ 운영은 `ddl-auto: validate`라 인덱스가 자동 생성되지 않는다. 배포 전 운영 DB에서 `CREATE INDEX idx_bidding_auction_price ON bidding (auction_id, bidding_price)`를 먼저 실행해야 함(미실행).
- 실행 중인 측정 스크립트(`perf/run.sh`)를 수정하면 bash가 어긋난 위치부터 읽어 다른 분기가 실행될 수 있다. 측정 중에는 고치지 않는다.
- 입찰 시 경매 상태·마감일 검증이 없던 것을 확인해 `Auction.validateBiddingAvailable`로 추가했다(상태 BIDDING 아님 `B_011`, 마감일 지남 `B_012`, 마감일 당일까지 입찰 가능). 서버가 꺼져 자정 스케줄러가 못 돌아도 마감 경매를 막는다.

### 2026-10-25 - Kafka 운영 전환(Redpanda 통합) 실제 수행
**수행 내용**
- 별도 Kafka EC2(B)는 이미 없었고(콘솔 인스턴스 3개: quantlime-prod, k6-influxdb-grafana, handsup-app 모두 중지 상태), `handsup-app`(t3a.medium)만 켜서 전환했다. 서버는 옛 compose(외부 Kafka IP)로 자동 기동돼 있었다.
- SSH(`~/.ssh/handsup-ec2-key-v2`, 사용자 `ubuntu`)로 `docker-compose.prod.yml`을 올리고 `up -d --pull never`로 전환. 앱 이미지는 서버에 있던 `parkjuhan/be-handsup:latest`를 그대로 썼다(Docker Hub 최신 이미지는 받지 않음). 전환 전 MySQL 덤프(`~/handsup_dump-20261025-pre-redpanda.sql`, 12MB)와 옛 compose 백업(`docker-compose.prod.yml.bak-20261025`)을 서버에 남겼다.
- 확인: `rpk cluster health` Healthy, 앱이 `bidding-events` 등 토픽 7개 생성, 컨슈머 그룹 7개 Stable, `GET /api/auctions/recommend` 200(로컬·공인), 앱 로그에 Kafka 연결 오류 없음. 메모리 가용 약 2.0GB(Redpanda 129MB). 확인 후 인스턴스를 다시 중지.

**주의 (미해결)**
- ⚠️ 디스크: 루트 볼륨 6.8GB가 전환 직후 96%(여유 약 340MB)다. Redpanda가 파티션마다 약 33MB를 미리 할당해 `redpanda_data`가 약 270MB가 됐다(일회성 증가로 추정, 추가 증가 여부는 미확인). 전환 전에는 91%라 `amazon-cloudwatch-agent.deb`(설치 완료된 설치 파일), apt 캐시를 지워 약 330MB를 확보했다. MySQL 쓰기 여유가 얇으므로 EBS 확장(8GB → 16GB 등)을 권장한다(미수행).
- 입찰 이벤트 E2E(입찰 → `bidding-events` 메시지 증가)는 운영 DB에 쓰는 일이라 수행하지 않았다. 컨슈머 연결과 토픽 생성까지만 확인.
- 서버가 탄력적 IP 없이 켜질 때마다 공인 IP가 바뀐다(이번 3.36.127.23). `EC2_HOST` 시크릿(2026-09-03 값)은 맞지 않으니 CD를 쓰려면 갱신이 필요하다.
- `handsup-kafka` 저장소의 CD 중단/아카이브는 하지 않았다(저장소 변경은 별도 확인 필요).

### 2026-10-27 - 캐시 콜드·TTL 측정, 락 제거(B안) 비교, 측정 오류 두 건
**측정 결과 (로컬 맥, 경매 5,000건, 고정 도착률, 상대 비교)**
- 캐시 콜드·TTL(100 RPS 780초 3회, `perf/trace_ttl.sh` + `perf/analyze_ttl.py`): 캐시가 빈 상태의 첫 30초만 p95 10~15ms(처음 1~3초에 DB 조회 초당 87~98건), 5분(Caffeine 만료)은 Redis가 받아 영향 없음, 10분 부근 두 계층이 거의 동시에 만료될 때만 DB 조회 초당 29~36건·p99 약 22ms(안정 구간 3~4ms). 실패·드롭 없음.
- 락 제거(B안, 조건부 UPDATE) 비교(입찰 50만 건, 요청의 80%가 경매 1건, 각 3회): 100·300 RPS는 A(락 유지)와 같고, 600 RPS에서 A는 포화(429 1,154건, 드롭 2,666건, p50 1,165ms)·B는 거의 소화(드롭 26건, p50 65ms). 정합성 불일치는 둘 다 0. B는 `exp/bid-conditional-update` 로컬 브랜치에만 있고 머지하지 않았다.

**결정 사항**
- B안 채택은 보류. 한 경매에 초당 300건 이하면 A로 충분하다. 채택하려면 동시성 설계 변경(락이 트랜잭션 바깥이라는 보장 소멸), Hikari 풀(기본 10개) 병목 가능성 검토가 필요하다.
- README 성능 줄을 이번 수치(조건 명시)로 갱신.

**측정에서 걸러낸 오류 (재발 방지)**
- 시드의 경매 마감일이 지나(#127 마감일 검증 추가 이후) 입찰이 전부 거절됐다(수락 0건). `bid_seed.sql`·`bid_reset.sql`이 마감일을 오늘 + 60일로 맞춘다. 앱은 JVM 시간대(KST), MySQL 컨테이너는 UTC라 하루 어긋날 수 있다.
- 앱을 바꿔 띄울 때 옛 앱이 준비 확인에 통과하는 틈이 있었다(B 첫 6회 요청 0건). `perf/run.sh start`가 포트가 비길 기다리고 포트를 쥔 PID를 확인하도록 고쳤다. 이전 캐시 모드 측정의 "첫 실행만 드롭"도 같은 원인일 수 있다(확인 못 함).
- 결과가 0이거나 너무 좋으면 요청 수·응답 코드 분포·정합성 테스트부터 본다.
