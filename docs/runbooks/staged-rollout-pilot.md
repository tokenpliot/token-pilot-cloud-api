# 단계적 배포·break-glass·파일럿 승인 게이트 런북 (#19)

한 프로젝트·환경에서 Observe → Advisory → Shadow → 명시적 Enforce로 넘어가고, 문제가 생기면 되돌리는 절차다.
기준을 채우지 못하면 Enforce로 넘어가지 않고 **Advisory를 유지**한다.

관련 문서: 판정 API [decision-api-rollout.md](decision-api-rollout.md)(#15), 정책 버전 [policy-management.md](../api/policy-management.md)(#14),
Shadow·rollout 선언·보고서 형식 [shadow-audit.md](../api/shadow-audit.md)(#16), 예약 [issue-17-reservations.md](../design/issue-17-reservations.md)(#17).

> 이 문서의 값은 모두 자리표시자다. 실제 API 키, JWT, 비밀번호를 문서·이슈·로그에 적지 않는다.
> `$TOKEN`은 회원 로그인 JWT, `$PROJECT_KEY`는 프로젝트 API 키, `$PROJECT_ID`는 프로젝트 id다.

## 0. 먼저 알아 둘 한계

- **Enforce 집행은 고객 SDK가 한다.** 서버는 판정과 감사 기록만 남긴다. rollout 선언(`/rollout-events`)은 `CUSTOMER_DECLARATION_ONLY`이며 SDK 설정을 배포하지 않는다.
- **break-glass는 아직 SDK가 읽지 않는다.** 서버의 발급·취소·조회·사용량 API는 있지만, Java Client SDK에 break-glass 조회 기능이 없다
  (`tokenpliot/token-pilot-client`에 기능 요청 이슈 등록 예정). 그때까지 break-glass를 발급하면 **고객 운영자가 SDK 모드를 직접 ADVISORY/OBSERVE로 바꿔야** 실제로 우회된다.
  break-glass 기록은 "누가·왜·언제까지 우회를 허가했는가"의 감사 근거로 쓴다.
- **지표는 프로세스별·재시작 시 0부터 다시 센다.** Prometheus의 `increase()`/`rate()`로 기간 증가량을 본다.

## 1. 켜기

| 항목 | 설정 | 비고 |
| --- | --- | --- |
| 판정 API | `token-pilot.decision.enabled=true` | 꺼져 있으면 404. 가격 단위 `token-pilot.observe.ingestion.rate-unit-tokens > 0` 필요 |
| Observe 수집 v2 | `token-pilot.observe.ingestion.enabled=true` | 우회 호출의 사용량·비용 기록에 필요 |
| Shadow | `token-pilot.shadow.enabled=true` | 시뮬레이션·rollout 선언·집행 보고 API |
| 예약 만료 회수 | `token-pilot.reservation.expiry.enabled=true` | 재시작 뒤 만료 예약 회수 포함 |
| 지표 노출 | `application.yml`의 `management.endpoints.web.exposure.include=health,prometheus` | **`/actuator/**`가 인증 없이 열려 있다.** 운영에서는 네트워크나 보안 설정으로 Prometheus 수집기만 접근하게 막는다 |
| break-glass | 별도 플래그 없음 | V12 `break_glass_grants`가 적용돼 있어야 한다 |

## 2. 시연 시나리오: shadow 검토 → opt-in Enforce → rollback

완료 기준 "하나의 프로젝트·환경에서 shadow 결과 검토 후 opt-in Enforce와 rollback을 시연"을 이 순서로 보인다. 예시 환경은 `prod`다.

### 2-1. 현재 정책 확인

```bash
curl -sS "$BASE_URL/api/policies/projects/$PROJECT_ID?environment=prod" -H "Authorization: Bearer $TOKEN"
```

응답의 `snapshotId`를 shadow baseline으로, `revision`(숫자)을 정책 변경의 `expectedVersion`으로 쓴다.
`version`은 `<policyId>:<revision>` 형태의 문자열 라벨이라 `expectedVersion`에 넣지 않는다. 정책이 아직 없으면 `expectedVersion`은 0이다.

### 2-2. Advisory로 판정 데이터 쌓기

고객 SDK를 `ADVISORY`로 두고 일정 기간 트래픽을 보낸다. 판정만 기록되고 호출은 막히지 않는다.
판정 결과 분포는 지표 `tokenpilot_decision_outcome_total`로 본다(§5).

### 2-3. Shadow run으로 후보 정책 비교

```bash
curl -sS -X POST "$BASE_URL/api/shadow/projects/$PROJECT_ID/runs" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: shadow-$(date +%s)" -H "Content-Type: application/json" \
  -d '{"baselineSnapshotId":"<2-1의 snapshotId>","month":"2026-11",
       "candidate":{"currency":"USD","monthlyHardLimitUsd":"100.000000","requestHardLimitUsd":"0.050000",
                    "active":true,"notificationThresholds":[80]},
       "reason":"prod Enforce 전환 전 검토"}'
```

보고서(PilotReport)에서 확인할 것: 표본 수, `indeterminate`와 사유, `newlyDenied`, `falseDenialCandidates`, `rolloutReadiness`.
`rolloutReadiness`는 자동 승인이 아니다(`INSUFFICIENT_EVIDENCE` 또는 `MANUAL_REVIEW_REQUIRED`). §4 go/no-go로 사람이 판단한다.
오차단 후보는 `GET .../runs/{runId}/decisions?falseDenialOnly=true`로 decisionId별로 고객과 검토한다.

### 2-4. 정책 적용과 opt-in Enforce 선언

go 판정이 나면 후보 정책을 실제 정책으로 바꾸고(#14), Enforce 전환을 선언한다(#16).

```bash
# 정책 교체: 새 revision이 생긴다
curl -sS -X PUT "$BASE_URL/api/policies/projects/$PROJECT_ID?environment=prod" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"expectedVersion":<현재 revision>,"rules":{...후보와 같은 규칙...},"reason":"shadow run <runId> 검토 후 적용"}'

# Enforce 선언: optIn=true 필수, evidenceRunId로 근거 run을 남긴다
curl -sS -X POST "$BASE_URL/api/shadow/projects/$PROJECT_ID/rollout-events" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: rollout-$(date +%s)" -H "Content-Type: application/json" \
  -d '{"expectedVersion":<최신 rollout revision, 첫 선언이면 0>,"environment":"prod",
       "settings":{"mode":"ENFORCE","enforcedOutcomes":["DENY_BUDGET"],"onIndeterminate":"FAIL_OPEN","onUnavailable":"FAIL_OPEN"},
       "optIn":true,"evidenceRunId":"<runId>","reason":"고객 운영자 승인(승인자·일시)"}'
```

그 다음 고객이 SDK를 `ENFORCE`로 바꾼다. 처음에는 `enforcedOutcomes`를 좁게(예: `DENY_BUDGET`만), 장애 시 동작을 `FAIL_OPEN`으로 시작한다.

### 2-5. Rollback

문제가 생기면 **SDK를 먼저 되돌리고**, 그다음 서버 기록을 남긴다.

1. 고객이 SDK를 `ADVISORY`(또는 `OBSERVE`)로 바꾼다. 급하면 §3 break-glass를 함께 발급해 우회 허가를 기록한다.
2. 정책을 이전 revision으로 되돌린다. 이전 내용을 복사한 새 revision이 생긴다.
   ```bash
   curl -sS -X POST "$BASE_URL/api/policies/projects/$PROJECT_ID/rollback?environment=prod" \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"expectedVersion":<현재 revision>,"restoreVersion":<되돌릴 revision>,"reason":"<원인>"}'
   ```
3. 같은 범위에 `mode: ADVISORY` rollout 선언을 새로 기록한다(`optIn: false`, `evidenceRunId`와 원인을 reason에).
4. 이력 확인: `GET .../versions`(정책), `GET .../rollout-events?environment=prod`(선언).

> 정책 API의 요청 형식은 [policy-management.md](../api/policy-management.md)를 기준으로 한다. 이 문서와 다르면 그쪽이 맞다.
> `environment`를 빼면 프로젝트 전체 정책이다. 프로젝트 정책과 환경 정책은 따로 버전이 매겨진다.

## 3. break-glass 운영 절차

장애 대응 중 한 프로젝트·환경의 Enforce를 **만료 시간이 있는 범위**에서 우회할 때 쓴다.
발급·취소는 조직의 OWNER·ADMIN·OPERATOR가 할 수 있다(관리자를 기다리지 않고 고객 운영자가 바로 실행). 조회·감사는 조직 멤버 누구나.

| 단계 | 호출 | 확인할 것 |
| --- | --- | --- |
| 발급 | `POST /api/rollout/break-glass` `{"projectId","environment","reason","durationMinutes"}` | 사유 필수, 최대 1440분(24시간). 같은 범위에 유효한 grant가 있으면 409 → 연장은 취소 후 재발급 |
| SDK 반영 | (SDK 기능 전까지) 고객이 SDK 모드를 ADVISORY/OBSERVE로 변경 | 우회 호출은 사용량 이벤트 metadata에 `break_glass_grant_id: <grantId>`를 붙인다 |
| 유효 확인 | `GET /api/rollout/break-glass/active?projectId=&environment=` | `active`, 만료 시각. 만료 시각이 지나면 배치 없이 자동으로 무효 |
| 사용량 확인 | `GET /api/rollout/break-glass/{grantId}/usage` | 아래 표 |
| 취소 | `POST /api/rollout/break-glass/{grantId}/revoke` `{"reason"}` | 이미 만료·취소면 409 |
| 사후 감사 | `GET /api/rollout/break-glass?projectId=&environment=&limit=` | 발급자·사유·발급/만료/취소 시각·취소자·취소 사유. `/api/events` 운영 이벤트 피드에도 `BREAK_GLASS_ISSUED`/`REVOKED`가 남는다 |

사용량 응답 읽는 법:

| 필드 | 뜻 |
| --- | --- |
| `withinWindow` | 발급 시각 ≤ 발생 시각 < 취소(없으면 만료) 시각인 우회 호출 |
| `outsideWindow` | 같은 grantId가 붙었지만 기간 밖. **0이 아니면 SDK가 만료·취소를 늦게 반영한 것**이므로 원인을 확인한다 |
| `actualCostUsd` | 최신 ACTUAL 개정본이 PRICED인 금액 합(#11 원장과 같은 기준) |
| `unpricedEvents` | 가격 미상·실제값 미도착. 이 비용은 합계에 없다 → 정산 후 다시 조회 |
| `legacyEvents` / `legacyCostUsd` | 기존 v1 수집 경로로 들어온 호출과 검증되지 않은 금액 |
| `quarantinedEvents` | 귀속이 불확실해 격리된 호출. 비용 합계에서 뺀다 |
| `callStatusCounts` | `SUCCEEDED`, `PROVIDER_FAILED`, `USAGE_UNKNOWN` 등 호출 결과 분포 |

한계: 해당 프로젝트·환경의 사용량 이벤트 metadata를 문자열로 훑어 찾는다. 파일럿 규모에서는 문제없지만, 이벤트가 수백만 건이 되면 인덱스나 별도 컬럼이 필요하다.

## 4. 검증 기준과 go/no-go

### 4-1. 측정 항목

임계값은 **제안값**이다. 파일럿 시작 전에 팀·고객과 확정하고 보고서에 확정값을 적는다. 관찰 기간도 함께 정한다(제안: Advisory 7일).

| 항목 | 측정 방법 | 출처 | 제안 기준 |
| --- | --- | --- | --- |
| 지연 | 판정 API 서버 처리 시간 평균·최대 | `http_server_requests_seconds_{sum,count,max}{uri="/api/control/v1/decisions"}` | 평균 < 50ms, 최대 < SDK 타임아웃의 50% |
| 가용성 | 중앙 장애 비율 | `increase(tokenpilot_control_plane_unavailable_total[기간])` ÷ (그 값 + `increase(tokenpilot_decision_outcome_total[기간])`) | < 0.1% |
| false denial | 오차단 후보와 고객 override | Shadow `falseDenialCandidates`, `GET .../execution-events`의 `customerOverride=ALLOW` | 후보 전부 고객 검토 완료, 확인된 오차단 0 |
| 미정산 liability | 결과를 모르는 예약·이벤트 | `budget_reservations.state='RECONCILIATION_REQUIRED'`의 `reserved_cost_usd` 합, `GET /api/observe/ledger/reconciliation`의 `reconciliationRequiredEventCount` | 기간 끝에 미처리 0, 또는 건별 사유·담당자 기록 |
| 이중 누적 | 같은 호출이 두 번 세지지 않는가 | 아래 SQL, 판정 지표 증가량 = `decision_records` 증가량(재시작 없는 구간) | 모두 0 / 일치 |
| break-glass | 기간 밖 우회 호출 | `usage`의 `outsideWindow.events` | 0 |

지연 분위수(p95·p99)가 필요하면 `management.metrics.distribution.percentiles-histogram.http.server.requests=true`를 켠다(현재 꺼져 있음).

이중 누적 확인 SQL(읽기 전용):

```sql
-- 같은 예약에 COMMIT이 두 번 이상 기록됐는가 (0이어야 함)
select reservation_id, count(*) from budget_reservation_settlements
where resulting_state = 'COMMITTED' group by reservation_id having count(*) > 1;

-- 같은 사용량 이벤트에 같은 종류·개정 번호의 원장 값이 둘 이상인가 (UNIQUE 제약으로 0이어야 함)
select usage_event_id, value_kind, revision, count(*) from usage_accounting_values
group by usage_event_id, value_kind, revision having count(*) > 1;

-- 기간 내 판정 건수 (지표 증가량과 비교)
select outcome, count(*) from decision_records
where project_id = :projectId and created_at >= :from and created_at < :to group by outcome;
```

> 테이블·컬럼 이름은 V8·V6·V10 마이그레이션 기준이다. 스키마가 바뀌면 이 쿼리도 함께 고친다.

### 4-2. go/no-go 체크리스트

모두 **예**여야 go다. 하나라도 아니면 no-go.

- [ ] Advisory 관찰 기간을 채웠고, 4-1의 확정 기준을 모두 통과했다
- [ ] Shadow run이 있고 `INSUFFICIENT_EVIDENCE`가 아니다. 표본 수와 `indeterminate` 비율을 고객이 확인했다
- [ ] 오차단 후보를 decisionId별로 검토했고 확인된 오차단이 없다
- [ ] 미정산 liability가 0이거나 건별 사유·담당자가 기록됐다
- [ ] 이중 누적 점검 결과가 모두 0/일치다
- [ ] rollback(§2-5)과 break-glass(§3)를 같은 환경에서 한 번 이상 연습했고 이력이 남았다
- [ ] 장애 주입 결과(§6)가 파일럿 보고서에 들어갔다
- [ ] 고객 승인자·승인 일시·적용 범위(`enforcedOutcomes`, 장애 시 동작)가 rollout 선언 reason과 보고서에 있다

### 4-3. no-go일 때

1. SDK를 `ADVISORY`로 유지(또는 되돌림)한다.
2. 같은 범위에 `mode: ADVISORY` rollout 선언을 기록한다. `reason`에 미달 항목, `evidenceRunId`에 검토한 run을 넣는다.
3. 미달 항목의 원인과 재평가 예정일을 보고서에 적는다. 기준을 낮춰 통과시키지 않는다.

## 5. 운영 지표: 정책 거부·중앙 장애·provider 실패 구분

`/actuator/prometheus`에서 본다. 태그에 조직·프로젝트 id는 없다(전체 합계만).

| 구분 | 지표 | 뜻 | 대응 |
| --- | --- | --- | --- |
| 정책 거부 | `tokenpilot_decision_outcome_total{outcome="DENY_POLICY"\|"DENY_BUDGET"\|"REQUIRE_APPROVAL"}` | 정책·예산 때문에 거부한 판정. 정상 동작이다 | 급증하면 정책 변경·트래픽 변화 확인. 오차단 의심 시 shadow로 검토 |
| 판정 불가 | `tokenpilot_decision_outcome_total{outcome="INDETERMINATE"}` | 가격·정책 근거 부족 | [decision-api-rollout.md](decision-api-rollout.md)의 원인 표 |
| 중앙 장애 | `tokenpilot_control_plane_unavailable_total{reason="STORE_DOWN"\|"RETRY_LATER"}` | Token Pilot 쪽 문제로 503. 정책 거부가 아니다 | STORE_DOWN: DB 확인(서버 로그 `Decision store unavailable`). RETRY_LATER: 동시 요청 충돌, 지속되면 부하 확인 |
| provider 실패 | `tokenpilot_ingestion_call_status_total{status="PROVIDER_FAILED"}` | 고객의 provider 호출 실패 | **아직 연결 전**(PR #40 머지 뒤 추가). 그 전에는 `GET /api/observe/ledger/...` 원장 조회와 `usage`의 `callStatusCounts`로 본다 |
| break-glass | `tokenpilot_break_glass_total{action="ISSUED"\|"REVOKED"}` | 우회 발급·취소 | 발급이 있으면 §3 사후 감사 |
| HTTP 전체 | `http_server_requests_seconds_count{uri,status}` | Spring 기본 지표 | 4xx는 고객 요청 오류(키·형식), 503은 중앙 장애 |

로그: 중앙 장애는 `ERROR` 로그(`Decision store unavailable`)가 남는다. 정책 거부는 정상 결과라 로그를 남기지 않고 `decision_records`와 지표로 본다.
API 오류: 판정 API는 정책 거부를 `200`+`outcome`으로, 중앙 장애를 `503 CONTROL_PLANE_UNAVAILABLE`+`Retry-After`로 돌려준다(어떤 오류도 정책 거부로 바꾸지 않는다).

## 6. 장애 주입 결과 (파일럿 보고서에 옮길 표)

"자동 테스트"는 2026-11-20 `./gradlew test` 기준 통과다(H2). "MySQL"은 실제 MySQL 8.0.46 + Flyway 적용 DB에서 통과한 것이다.
실환경 주입은 SDK Enforce(#18) 연동 뒤 파일럿 환경에서 채운다. **미실시 항목은 미실시로 둔다.**

| 분류 | 시나리오 | 근거 | 결과 |
| --- | --- | --- | --- |
| 동시성 | 같은 한도에 동시 예약 → 한도 초과 없음 | `ReservationConcurrencyTest.concurrentReservationsNeverExceedTheLimit` | 자동 테스트 통과 |
| 동시성 | 같은 예약 동시 재시도·중복 COMMIT → 한 번만 반영 | `ReservationConcurrencyTest` (재시도·중복 commit·commit/release 경합) | 자동 테스트 통과 |
| 동시성 | 같은 판정 요청 동시 2건 → 판정 1건 | `DecisionServiceIntegrationTest.concurrentSameKeyAndBodyStoreOneDecisionAndReturnTheSameResponse` | 자동 테스트 통과 |
| 동시성 | 같은 범위 break-glass 동시 발급·재발급·취소 → 한쪽만 성공 | `BreakGlassConcurrencyTest` | 자동 테스트 통과, MySQL 3회 반복 통과 |
| 동시성 | 같은 사용량 이벤트 재전송 → 원장 1건 | `ObserveIngestionServiceTest.sameEventSentTwiceIsRecordedOnce`, `AccountingLedgerWriterTest` | 자동 테스트 통과 |
| 재시작 | 서버가 꺼진 동안 만료된 예약을 재시작 후 회수 | `ReservationRestartRecoveryTest` | 자동 테스트 통과 |
| 재시작 | 재시작 후 원장 상태 조회 | `ObserveIngestionHttpIntegrationTest.ledgerStateCanBeQueriedAfterARestart` | 자동 테스트 통과 |
| 재시작 | 발급·취소 뒤 같은 DB로 서버 재기동 → 유효 grant 유지, 만료 시각에 무효, 취소 상태 유지, 같은 범위 중복 발급 409, 재시작 전 우회 호출 집계 | `BreakGlassRestartTest` | 자동 테스트 통과 |
| 중앙 장애 | DB 장애 → 503 `CONTROL_PLANE_UNAVAILABLE`, 정책 거부로 바뀌지 않음 | `ControlPlaneExceptionHandlerTest`, `ControlPlaneUnavailableMetricsTest` | 자동 테스트 통과 |
| 네트워크 | 서버 다운 중 SDK Observe 전송 | #13 E2E(2026-10-28): 3건 모두 provider 응답 유지, 이벤트당 4회 재시도 후 `RETRIES_EXHAUSTED` | 실환경 확인. **긴 중단 시 이벤트 유실**(SDK 재시도 약 5초) |
| 네트워크 | 키 폐기 후 전송 | #13 E2E: `REQUEST_REFUSED`, 재시도 없음, 원장 변화 없음 | 실환경 확인 |
| 네트워크 | Enforce 중 판정 API 응답 지연·끊김 → `onUnavailable`대로 동작 | SDK #18 연동 필요 | **미실시** |
| 네트워크 | break-glass 만료 직후 SDK 반영 지연 | SDK break-glass 기능 필요. 서버는 `outsideWindow`로 감지 | **미실시** |

## 7. 배포·롤백

- V12(`break_glass_grants`)는 새 테이블만 추가한다. 롤백할 때 테이블과 이력은 지우지 않는다(감사 기록).
- break-glass·사용량·지표 기능은 별도 플래그가 없다. 끌 필요가 있으면 컨트롤러 배포를 되돌린다. 지표만 숨기려면 노출 설정에서 `prometheus`를 뺀다.
- 판정 API를 끄기 전 주의사항은 [decision-api-rollout.md §6](decision-api-rollout.md)을 따른다(ENFORCE + fail-closed 고객 먼저 확인).
