# 예산 예약 운영 런북 (#17)

대상: ENFORCE 예산 예약, 정산 API, 만료 회수, 운영자 수동 정산. 설계와 상태 전이는 [#17 설계 문서](../design/issue-17-reservations.md), 판정 API 켜기는 [판정 API 롤아웃 런북](decision-api-rollout.md)에 있다.

> **먼저 알아둘 것.** 예약은 판정 API가 발급하므로, 판정 API가 꺼져 있으면 정산 API도 등록되지 않는다(404). 만료 회수 작업은 따로 켜야 한다. 회수를 켜지 않으면 정산이 오지 않은 예약의 금액이 월말까지 한도를 잡고 있다.

## 1. 켜기

| 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `token-pilot.decision.enabled` | `false` | 판정 API와 정산 API(`POST /api/control/v1/reservations/{id}/settlements`)를 함께 등록한다. |
| `token-pilot.reservation.expiry.enabled` | `false` | 만료 예약 회수 작업. 켜면 기동 직후 한 번(재시작 복구), 그 뒤 주기마다 돈다. |
| `token-pilot.reservation.expiry.interval` | `PT1M` | 회수 주기. |
| `token-pilot.reservation.expiry.batch-size` | `100` | 한 번에 닫는 예약 수. |
| `token-pilot.reservation.expiry.max-batches` | `10` | 한 주기에 도는 최대 배치 수. 남은 것은 다음 주기로 넘긴다. |
| `token-pilot.reservation.expired-reserved-action` | `RECONCILIATION_REQUIRED` | 만료 시 RESERVED 예약 처리. `RELEASE`면 금액을 돌려준다(아래 3절 참고). |

배포 전에 `V10__budget_reservations.sql`이 Flyway로 적용된다. 운영 DB에 적용하기 전에 복제 DB에서 아래 MySQL 테스트를 돌린다.

```powershell
# 빈 일회용 DB에서만. 테스트가 Flyway로 V1~V11을 적용한다.
$env:RESERVATION_TEST_MYSQL_URL = "jdbc:mysql://localhost:3306/token_pilot_rsv_test?serverTimezone=UTC"
$env:RESERVATION_TEST_MYSQL_USER = "root"
$env:RESERVATION_TEST_MYSQL_PASSWORD = "<비밀번호>"
.\gradlew test --tests "*ReservationMySqlIntegrationTest"
```

URL이 없으면 이 테스트는 건너뛴다(실패가 아니다). 비밀번호를 문서·이슈·로그에 적지 않는다.

## 2. 켠 뒤 확인

1. 정책이 있는 테스트 프로젝트에 ENFORCE 판정을 한 번 보내 `reservationId`를 받는다.
2. 운영 API로 예약이 잡혔는지 본다.

   ```bash
   curl -sS "$BASE_URL/api/reservations/projects/$PROJECT_ID" -H "Authorization: Bearer $TOKEN"
   ```

3. 정산을 보내고, 같은 요청을 한 번 더 보내 `duplicate: true`가 오는지 본다.

   ```bash
   curl -sS -X POST "$BASE_URL/api/control/v1/reservations/$RESERVATION_ID/settlements" \
     -H "X-API-Key: $PROJECT_KEY" -H "Idempotency-Key: smoke-settle-0001" \
     -H "Content-Type: application/json" \
     -d '{"schemaVersion":"2026-10-01","requestId":"'"$REQUEST_ID"'","result":"RELEASE",
          "occurredAt":"2026-11-15T00:00:00Z"}'
   ```

4. 회수를 켰다면 로그에서 기동 직후의 회수 결과(`released`, `reconciliationRequired`, `skipped`)를 확인한다.

## 3. 일상 점검

| 볼 것 | 방법 | 대응 |
| --- | --- | --- |
| 오래 남은 RECONCILIATION_REQUIRED | `GET /api/reservations/projects/{projectId}?state=RECONCILIATION_REQUIRED` | 4절 수동 정산 |
| 만료가 지났는데 RESERVED·IN_FLIGHT로 남은 예약 | `?state=RESERVED,IN_FLIGHT` 결과에서 `expiresAt`이 지난 것 | 회수가 꺼져 있거나 밀린 것이다. 설정과 로그를 본다 |
| 정산 409 `IDEMPOTENCY_CONFLICT` 증가 | API 로그 | SDK가 같은 키로 다른 본문(특히 다른 `occurredAt`)을 보내고 있다. SDK 쪽 재시도 로직 확인 |
| 정산·예약 503 `INGESTION-503` 증가 | API 로그 | 잠금 경합(데드락·락 타임아웃)이다. 같은 요청 재시도로 해결되며, 계속되면 DB 부하를 본다 |

`RECONCILIATION_REQUIRED`는 결과를 모르는 예약이다. 금액은 0원으로 풀리지 않고 한도를 계속 잡고 있다. 늦게라도 SDK가 COMMIT을 보내면 자동으로 정산된다.

**RESERVED 만료 처리 선택**: 지금 계약에는 호출 시작(IN_FLIGHT)을 알리는 API가 없어서, RESERVED로 만료돼도 실제로는 호출됐을 수 있다. 그래서 기본값은 금액을 남기는 `RECONCILIATION_REQUIRED`다. `RELEASE`로 바꾸면 한도는 빨리 돌아오지만, 그 뒤 늦게 온 COMMIT은 종료 상태라 409가 되어 비용이 빠진다.

## 4. 수동 정산 (운영자)

청구서 등으로 실제 비용을 확인했거나, 끝내 확인할 수 없을 때 쓴다. OWNER·ADMIN만 가능하다.

```bash
curl -sS -X POST "$BASE_URL/api/reservations/projects/$PROJECT_ID/$RESERVATION_ID/reconcile" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: ops-2026-11-15-0001" \
  -H "Content-Type: application/json" \
  -d '{"action":"COMMIT","totalCostUsd":"0.17","occurredAt":"2026-11-15T00:00:00Z",
       "reason":"11월 청구서로 실제 비용 확인"}'
```

| 상황 | action | 결과 |
| --- | --- | --- |
| 실제 비용을 확인함 | `COMMIT` + `totalCostUsd` | 그 금액으로 확정, 남은 예약 금액 반환 |
| 끝내 확인 못 함 (RECONCILIATION_REQUIRED만) | `WRITE_OFF` | 예약 금액 전체를 비용으로 확정 |

- `reason`은 필수이며 감사 로그(INFO)에 회원 id와 함께 남는다.
- 네트워크 오류로 결과를 못 받았으면 **같은 Idempotency-Key와 같은 본문**으로 다시 보낸다. 본문이 다르면 409다.
- 이미 종료된 예약(COMMITTED·RELEASED·WRITTEN_OFF)은 다시 정산할 수 없다(409).

## 5. 장애 상황

| 상황 | 영향 | 대응 |
| --- | --- | --- |
| 서버 재시작·배포 | 예약·정산 상태는 모두 DB에 있어 잃지 않는다 | 회수가 켜져 있으면 기동 직후 밀린 만료를 처리한다 |
| 여러 서버 인스턴스 | 회수 작업이 서버마다 돈다 | 같은 예약을 동시에 회수해도 행 잠금으로 한 번만 닫힌다. 늦은 쪽은 `skipped` |
| 정산 API 장애 | SDK는 정산을 재시도한다 | 복구 뒤 같은 키로 오는 재전송은 한 번만 반영된다 |
| 회수 작업을 끄고 싶음 | | `token-pilot.reservation.expiry.enabled=false` 후 재배포. 미정산 예약은 한도를 계속 잡는다 |

## 6. 되돌리기

- 판정 API를 끄면(`token-pilot.decision.enabled=false`) 새 예약과 정산 API가 모두 멈춘다. SDK는 `INDETERMINATE`로 처리하므로 fail-closed 고객은 호출이 막힌다. [판정 API 롤아웃 런북](decision-api-rollout.md)을 먼저 본다.
- 이미 잡힌 예약은 DB에 남는다. 다시 켜면 그대로 이어서 정산·회수된다.
- V10 테이블은 지우지 않는다. 정산 기록은 비용 감사 근거다.
