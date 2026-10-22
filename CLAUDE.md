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

