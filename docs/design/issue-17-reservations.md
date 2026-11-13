# #17 예산 예약·정산 상태 머신 (1단계: 저장소와 상태 머신)

ENFORCE + ALLOW 판정이 잡아두는 예산 예약과 그 정산을 MySQL에 저장한다. 이번 단계는 테이블, 엔티티, 상태 머신, 서비스까지이며 API는 연결하지 않았다.

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| 1 | V10 마이그레이션, 엔티티, 상태 머신, 원자적 예약·정산 서비스, 동시성 테스트 | 이 PR |
| 2 | 정산 API `POST /api/control/v1/reservations/{id}/settlements` | 후속 |
| 3a | 만료 예약 회수 서비스(`ReservationExpiryService`) | 이 PR |
| 3b | 회수 주기 실행(`ReservationExpiryJob`)과 재시작 복구 | 이 PR |
| 3c | 운영 API: 예약 조회, 운영자 수동 정산(COMMIT·WRITE_OFF) | 이 PR |
| - | 판정 API(#15)에서 `PolicyContextService.capture` 다음에 `ReservationService.reserve` 호출 | #15 |

## 계약(control-plane-v1.yaml, #32)과 맞춘 부분

| 계약 | 구현 |
| --- | --- |
| Idempotency-Key 고유 범위는 (프로젝트, 엔드포인트), 서버는 불투명 문자열로 다룸 | 예약 `uk_reservation_idempotency (project_id, idempotency_key)`, 정산 `uk_reservation_settlement_idempotency (project_id, idempotency_key)`. 환경은 범위가 아니라 본문이라 지문에 넣는다. 키는 8~128자만 검사한다. |
| 정산 재전송은 결과가 아니라 본문 비교, `occurredAt`까지 같아야 원래 결과 | 정산 요청마다 `budget_reservation_settlements`에 본문 지문(reservationId, requestId, result, usage, cost 전체, occurredAt)과 결과를 남긴다. 같은 키·같은 지문이면 저장된 결과를 `duplicate: true`로, 지문이 다르면 `409 IDEMPOTENCY_CONFLICT`. |
| COMMIT 금액 기준은 `cost.totalCostUsd`, 분할이 서버 계산과 달라도 거부하지 않음 | `SettlementCommand.totalCostUsd()`만 쓴다. prompt·completion 분할은 지문에만 들어간다. |
| 응답 `requestId`는 요청 값을 그대로 | 요청 `requestId`가 예약과 다르면 400. 응답에는 요청 값을 그대로 넣는다. |
| 판정 API가 없으면 404 → INDETERMINATE | #15 범위. 이 단계는 API가 없다. |

## 예산 범위 (#14)

#14 설계대로 프로젝트 정책과 환경 정책이 둘 다 있으면 각각의 월 예산을 모두 만족해야 한다. 그래서 예약 하나가 카운터 두 개에 같은 금액을 잡는다.

- `budget_reservation_counters`: (프로젝트, `environment_key`, 월)당 한 행. `environment_key = ''`는 프로젝트 전체 범위다(#14 `customer_policies`와 같은 규칙). `held_usd`(미정산 예약 합계), `committed_usd`(예약 경로로 정산된 비용).
- 한도가 `null`이면 그 범위에 활성 예산이 없다는 뜻이고 제한하지 않는다. 그래도 카운터에는 금액을 잡아 두어, 나중에 예산을 켜도 합계가 맞는다.
- 환경·멱등성 키·requestId 컬럼은 #14처럼 `utf8mb4_bin`이다. `Prod`와 `prod`는 다른 범위다.
- 예약은 #14의 요청 context(`policy_request_context_id`)를 가리킨다. 예약 시점의 프로젝트·환경 정책 스냅샷과 가격 스냅샷이 거기에 묶여 있다.

## 상태 전이

ADR 0001 §6을 그대로 따른다(`ReservationState`). 정산 종류(`SettlementAction`)는 yaml의 `COMMIT`, `RELEASE`, `RECONCILIATION_REQUIRED`에 운영자용 `WRITE_OFF`를 더했다.

| 요청 | 가능한 시작 상태 | 결과 상태 | 두 카운터 |
| --- | --- | --- | --- |
| `COMMIT` | RESERVED*, IN_FLIGHT, RECONCILIATION_REQUIRED | COMMITTED | 예약 금액을 풀고 `totalCostUsd`를 더함 |
| `RELEASE` | RESERVED, IN_FLIGHT | RELEASED | 예약 금액을 풂 |
| `RECONCILIATION_REQUIRED` | RESERVED*, IN_FLIGHT | RECONCILIATION_REQUIRED | 그대로 둠 (부채 유지) |
| `WRITE_OFF` | RECONCILIATION_REQUIRED | WRITTEN_OFF | 예약 금액을 풀고 같은 금액을 비용으로 더함 |

\* RESERVED에서 바로 온 COMMIT/RECONCILIATION_REQUIRED는 호출이 이미 일어난 것이므로 IN_FLIGHT를 거친 것으로 본다. 응답의 `previousState`는 실제 직전 상태(RESERVED)다.

- **결과를 모르면 0원으로 두지 않는다.** RECONCILIATION_REQUIRED는 예약 금액을 계속 잡아두고, WRITE_OFF도 예약 금액을 비용으로 확정한다.
- **늦게 온 실제 사용량**: RECONCILIATION_REQUIRED에 COMMIT이 오면 그때 확정하고 남은 금액을 돌려준다. 앞서 보낸 RECONCILIATION_REQUIRED의 재전송은 그때 결과를 그대로 받는다(키가 결과마다 달라서).
- 종료 상태에 다른 결과를 요구하면 `409 COMMON-409`.

## 만료 예약 회수

`ReservationExpiryService.expire(nowUtc, limit)`는 `expires_at`이 지난 RESERVED·IN_FLIGHT 예약을 오래된 순서로 닫는다. `ReservationTx.expire`가 예약 행을 잠근 뒤의 상태로 회수 결과를 정하고 같은 트랜잭션에서 적용한다. 잠그기 전에 읽은 상태로 정하면 그 사이 IN_FLIGHT가 된 예약을 RELEASE로 풀 수 있기 때문이다. 카운터·정산 기록 규칙은 클라이언트 정산과 같다.

| 만료 시 상태 | 회수 결과 | 카운터 |
| --- | --- | --- |
| IN_FLIGHT | RECONCILIATION_REQUIRED | 예약 금액 유지 (부채) |
| RESERVED | 기본 RECONCILIATION_REQUIRED, 설정 `token-pilot.reservation.expired-reserved-action=RELEASE`면 RELEASED | 기본은 유지, RELEASE면 반환 |

- RESERVED의 기본값이 ADR(RELEASED)과 다른 이유: 계약에 호출 시작을 알리는 API가 없어서 RESERVED여도 실제로 호출됐을 수 있다. 0원으로 풀면 늦게 온 사용량을 받을 수 없다(종료 상태라 409). 아래 "정해야 할 것" 참고.
- 회수 정산의 키는 `exp:<reservationId>`, 사건 시각은 `expires_at`이다. `exp:` 접두사는 서버 전용이라 클라이언트 정산에서는 `400`이다. 클라이언트가 다른 예약의 회수 키를 먼저 차지하면 그 예약은 회수 기록을 남길 수 없어 영영 닫히지 않기 때문이다.
- 여러 서버가 같은 예약을 동시에 회수하거나 클라이언트 정산이 먼저 오면, 늦은 쪽은 잠금 뒤 이미 닫힌 상태를 보고 건너뛴다. 회수 뒤에 늦게 온 COMMIT은 RECONCILIATION_REQUIRED → COMMITTED로 정상 정산된다.

### 주기 실행과 재시작 복구

- `token-pilot.reservation.expiry.enabled=true`일 때만 켜진다(기본 꺼짐). `interval`(기본 1분)마다 `batch-size`(100)씩 최대 `max-batches`(10)번 회수하고, 남은 것은 다음 주기에 처리한다.
- **재시작 복구**: 예약·정산 상태는 모두 DB에 있어 메모리에서 복구할 것이 없다. 서버가 꺼져 있던 동안 만료된 예약만 남으므로, 기동 직후(`ApplicationReadyEvent`) 한 번 회수한다.
- `ReservationRestartRecoveryTest`가 같은 DB로 두 번째 애플리케이션을 띄워 이를 확인한다. 버려진 예약은 RECONCILIATION_REQUIRED로 닫히고 금액은 부채로 남으며, 재시작 뒤 늦게 온 COMMIT도 정산된다.

## 운영 API

로그인한 회원만 쓸 수 있고(`/api/reservations/**`), 프로젝트 권한은 정책 API와 같은 `PolicyAccess`로 확인한다. 다른 조직 프로젝트는 존재도 알려주지 않는다(404).

| API | 권한 | 설명 |
| --- | --- | --- |
| `GET /api/reservations/projects/{projectId}?state=&limit=` | OWNER·ADMIN·OPERATOR·VIEWER | 예약 목록. `state`가 없으면 아직 예산을 잡고 있는 RESERVED·IN_FLIGHT·RECONCILIATION_REQUIRED. 쉼표로 여러 상태, `limit` 1~500(기본 50) |
| `GET /api/reservations/projects/{projectId}/{reservationId}` | OWNER·ADMIN·OPERATOR·VIEWER | 예약 한 건과 정산 이력. 이력마다 출처(CLIENT·EXPIRY·OPERATOR)를 표시한다 |
| `POST /api/reservations/projects/{projectId}/{reservationId}/reconcile` | OWNER·ADMIN, 활성 프로젝트 | 운영자 수동 정산. `Idempotency-Key` 헤더 필수 |

수동 정산 본문: `{"action": "COMMIT" | "WRITE_OFF", "totalCostUsd": "0.17", "occurredAt": "...", "reason": "..."}`

- **COMMIT**: 청구서 등으로 확인한 실제 비용으로 확정한다. 토큰 수는 알 수 없어 0으로 기록한다.
- **WRITE_OFF**: 끝내 확인하지 못한 예약을 예약 금액으로 확정한다. RECONCILIATION_REQUIRED에서만 가능하다(상태 전이 규칙).
- 운영자는 RELEASE·RECONCILIATION_REQUIRED를 고를 수 없다. 그건 SDK와 만료 회수가 정한다.
- 정산 키는 `ops:<Idempotency-Key>`다. `exp:`처럼 서버 전용이라 클라이언트 정산에서 이 접두사는 400이다. 같은 키·같은 본문의 재시도는 처음 결과를, `occurredAt`까지 같지 않으면 409를 돌려준다.
- `reason`은 필수다. 누가(회원 id) 어느 예약을 무엇으로 왜 닫았는지 감사 로그(INFO)에 남긴다. DB에 감사 컬럼을 두는 것은 후속 과제다.

## 동시성

- **예약**: 프로젝트 카운터 → 환경 카운터를 `SELECT ... FOR UPDATE`로 잠그고 두 한도를 모두 확인한 뒤 둘 다에 금액을 잡고 예약 행을 넣는다. 한 트랜잭션이라 같은 멱등성 키가 동시에 와서 늦은 쪽이 UNIQUE 오류를 내면 잡은 금액도 함께 되돌아간다. 한도가 모자라면 예외가 아니라 `granted=false`(판정 API에서 `DENY_BUDGET`)다.
- **정산**: 예약 행 → 프로젝트 카운터 → 환경 카운터 순으로 잠그고, 상태·카운터·정산 기록을 한 트랜잭션에서 바꾼다. 같은 정산이 동시에 와도 한 번만 반영된다.
- **잠금 순서**: 예약은 카운터만(프로젝트 → 환경), 정산은 예약 → 프로젝트 → 환경. 순환 대기가 없다. 데드락·락 타임아웃은 재시도 가능한 `503 INGESTION-503`.
- 쓰기 트랜잭션은 #14와 같이 `READ_COMMITTED`다. 잠금을 기다린 뒤 읽는 값이 방금 커밋된 최신 값이어야 하기 때문이다.
- 테스트(`ReservationConcurrencyTest`)는 H2 기반이다. 실제 UNIQUE 제약과 행 잠금은 걸리지만 MySQL 잠금 거동 자체는 아니다. MySQL에서는 `PolicyMySqlIntegrationTest`처럼 `ddl-auto=validate`로 매핑을 확인해야 한다(후속).

## 정해야 할 것

- **한도에 무엇을 넣을지**: 카운터는 예약 경로의 금액만 안다. 예약 없이 들어온 사용량(Observe 원장)을 한도에서 뺄지는 #15가 `projectBudgetLimitUsd`·`environmentBudgetLimitUsd`를 계산할 때 정한다(예: 월 예산 − 원장 누적).
- **IN_FLIGHT 신호**: 계약에 RESERVED → IN_FLIGHT를 알리는 API가 없다. 지금은 정산이 오면 거친 것으로 본다. 만료 회수(3단계)에서 RESERVED 만료를 RELEASED(ADR)로 둘지, 호출 여부를 모르니 RECONCILIATION_REQUIRED로 둘지 결정이 필요하다.
