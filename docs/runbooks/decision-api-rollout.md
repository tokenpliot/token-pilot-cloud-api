# 판정 API 롤아웃 런북 (#15)

대상: `POST /api/control/v1/decisions`. 설계와 가정은 [#15 설계 문서](../design/issue-15-advisory-decision.md), 계약은 [`control-plane-v1.yaml`](../api/control-plane-v1.yaml)에 있다.

> **먼저 읽을 것.** 판정 API가 꺼져 있거나 정책이 없는 범위는 SDK에서 `INDETERMINATE`로 처리된다. `ENFORCE`에 `on-indeterminate: fail-closed`를 고른 고객은 이때 **모든 호출이 막힌다.** 그래서 고객에게 `ENFORCE`를 안내하기 전에 아래 1~3단계를 끝내야 한다.

## 1. 켜기

| 항목 | 값 |
| --- | --- |
| 설정 | `token-pilot.decision.enabled=true` (환경 변수로는 `TOKEN_PILOT_DECISION_ENABLED=true`) |
| 기본값 | **꺼짐.** 꺼져 있으면 컨트롤러가 등록되지 않아 `404`(`data: null`, `data.outcome` 없음)다. |
| 필요한 선행 설정 | `token-pilot.observe.ingestion.rate-unit-tokens`가 0보다 커야 한다. 0이면 가격을 계산할 수 없어 모든 판정이 `INDETERMINATE`(`PRICING_UNAVAILABLE`)다. 판정과 Observe가 같은 가격 단위를 쓴다. |
| 필요한 데이터 | 해당 provider·model의 `pricing_plans`(유효 기간 안, 통화 USD), 프로젝트(또는 환경) 정책 |

배포 전에 `V8__decision_records.sql`이 Flyway로 적용된다. **MySQL에서 이 SQL을 아직 실행해 보지 못했다.** 운영 배포 전에 복제 DB에서 리허설하고 백업한다(설계 문서의 "검증 한계" 참고).

## 2. 정책과 가격 준비

판정은 정책이 있는 범위에서만 `ALLOW`/`DENY_*`를 낸다. **정책이 없는 범위는 `INDETERMINATE`(`POLICY_NOT_CONFIGURED`)이고, 기존 월 예산(`PUT /api/budgets`)만 있는 범위도 정책 없음으로 본다.** 기존 예산은 자동으로 이관되지 않는다.

- 정책 만들기: `PUT /api/policies/projects/{projectId}` (환경 정책은 `?environment=prod`). [정책 관리 API](../api/policy-management.md) 참고.
- 기존 월 예산을 가져오기: `POST /api/policies/projects/{projectId}/import-monthly-budget`. 먼저 [issue-14 마이그레이션 런북](../migrations/issue-14/README.md)의 사전 검사를 한다.
- 정책을 일시적으로 끄려면 `active=false`로 바꾼다. 적용되는 정책이 **모두** 비활성이면 판정은 `ALLOW`(`POLICY_INACTIVE`)이고 fail-closed 고객도 막히지 않는다. 프로젝트 정책과 환경 정책 중 하나만 끄면 다른 쪽이 계속 적용된다.
- 정책 변경은 **새 요청부터** 적용된다. 이미 판정된 requestId는 처음 묶은 정책·가격 snapshot을 유지한다.

## 3. 켠 뒤 확인

테스트용 프로젝트 키와 정책이 있는 프로젝트로 한 번 호출한다. 값은 자리표시자다. 실제 키를 문서, 이슈, 로그에 적지 않는다.

```bash
curl -sS -X POST "$BASE_URL/api/control/v1/decisions" \
  -H "X-API-Key: $PROJECT_KEY" \
  -H "Idempotency-Key: smoke-$(date +%s)" \
  -H "Content-Type: application/json" \
  -d '{"schemaVersion":"2026-10-01","requestId":"smoke-'"$(date +%s)"'","environment":"prod",
       "provider":"openai","model":"gpt-4o-mini","clientMode":"ADVISORY",
       "estimatedInputTokens":1000,"maxOutputTokens":500}'
```

| 응답 | 의미와 조치 |
| --- | --- |
| `200` `outcome: ALLOW` | 정상. |
| `200` `outcome: INDETERMINATE` | 요청은 받았으나 근거가 부족하다. `reasons[0].code`를 아래 표로 확인한다. |
| `404` | 플래그가 꺼져 있다(또는 배포 전). 설정과 재시작을 확인한다. 환경 변수 이름이 맞는지도 이때 확인된다. |
| `401` | 키가 없거나 폐기·만료됐다. |
| `403` | 키가 다른 환경 전용이거나 프로젝트가 보관 상태다. |

### 판정 불가(`INDETERMINATE`) 원인

| `reasons[].code` | 원인 | 조치 |
| --- | --- | --- |
| `POLICY_NOT_CONFIGURED` | 프로젝트·환경 어디에도 정책이 없다(기존 예산만 있는 경우 포함). | 2단계로 정책을 만들거나 import한다. |
| `PRICING_NOT_FOUND` | 서버에 이 provider·model의 가격표가 없다. provider와 model은 정확히 일치해야 한다. | `pricing_plans`의 이름과 유효 기간을 확인한다. |
| `PRICING_UNAVAILABLE` | 가격표는 있으나 비용을 계산할 수 없다. `rate-unit-tokens`가 0이거나, 통화가 USD가 아니거나, 같은 가격표 버전에 다른 내용의 snapshot이 이미 있거나, 이미 정해진 requestId에 가격이 묶이지 않았다. | `rate-unit-tokens`와 가격표를 확인한다. 상세 사유(`RATE_UNIT_UNCONFIRMED`, `CURRENCY_UNSUPPORTED`, `PRICE_SNAPSHOT_CONFLICT`, `PRICE_NOT_BOUND` 등)는 `reasons[0].message`에 있다. |

## 4. 고객 안내 순서

1. 판정 API를 켜고 3단계 확인을 마친다.
2. 고객 프로젝트에 정책을 만든다. 정책이 없으면 안내를 시작하지 않는다.
3. `OBSERVE`로 연결하고 Observe 원장이 실제 사용량과 맞는지 확인한다.
4. `ADVISORY`를 안내한다. 판정은 경고일 뿐 호출을 막지 않는다.
5. `ENFORCE`는 아래 "ENFORCE 안내 전 체크리스트"를 모두 통과한 프로젝트·환경에만 안내한다.

### ENFORCE 안내 전 체크리스트

- [ ] 판정 API가 운영에서 켜져 있고 3단계 확인이 `200`이다.
- [ ] 해당 프로젝트·환경에 정책이 있다. 없으면 fail-closed 고객은 전 호출이 막힌다.
- [ ] 쓰는 모든 provider·model에 가격표가 있다. 없으면 `PRICING_NOT_FOUND`로 같은 결과가 된다.
- [ ] `ENFORCE`를 `ADVISORY`/`OBSERVE`로 되돌리는 설정 롤백을 고객이 알고 있다. ADR 0001은 재배포 없이 설정만으로 되돌릴 수 있어야 한다고 정했다. SDK가 실제로 그런지는 이 저장소에서 확인하지 못했다.
- [ ] 고객이 `on-indeterminate`/`on-unavailable`을 직접 골랐다. 기본은 둘 다 fail-open이다.

## 5. 알아 둘 동작

- **`maxTokens`를 주지 않으면** SDK가 `maxOutputTokens`를 **1024**로 보낸다. 이때 서버가 계산한 최대 예상 비용은 실제 호출의 상한이 아니다. 고객에게 `maxTokens` 설정을 권한다.
- **`ENFORCE` 요청도 Advisory로만 판정**한다. 예약(`reservation`)은 #17 전까지 발급하지 않고 `effectiveMode`는 `ADVISORY`다.
- **soft 한도 경고**(`*_SOFT_LIMIT_EXCEEDED`)는 `ALLOW`와 함께 `reasons`로 나간다. SDK의 Spring AI advisor는 아직 `reasons`를 앱에 넘기지 않으므로 서버 기록(`decision_records`)에서만 볼 수 있다.
- **v1 수집(`/api/ingestion/events`)만 쓰는 고객은 월 지출이 실제보다 적게 잡힌다.** 판정은 Observe 원장의 확정 지출(v2 수집)만 쓰므로 남은 예산이 크게 나오고 월 한도 거부 권고가 늦게 나온다.
- **같은 프로젝트의 판정은 정책 snapshot을 묶는 구간에서 하나씩 처리된다**(프로젝트 행 잠금). 파일럿 규모를 가정했고 처리량은 측정하지 않았다. 지연이 늘면 먼저 이 구간을 본다.
- **속도 제한(`429`)이 없다.** 호출이 몰려도 서버가 막아 주지 않는다.
- **판정 기록이 계속 쌓인다.** 보존 기간과 삭제 정책이 없다.
- 요청 크기는 단건 수집과 같은 한도(`token-pilot.ingestion.max-event-bytes`, 기본 16384바이트)다. 넘으면 `413`이다.

### 오류가 SDK에서 어떻게 처리되는가

| 서버 응답 | SDK 처리 |
| --- | --- |
| `200` (모든 판정 결과) | 그 판정 그대로. `DENY_*`도 `200`이다. |
| `400` `401` `403` `404` `409` `413` | `INDETERMINATE` |
| `408` `429` `5xx`, 연결 실패·타임아웃 | `CONTROL_PLANE_UNAVAILABLE` |

어떤 오류도 정책 거부(`DENY_*`)로 바뀌지 않는다. 고객 설정(`onIndeterminate`, `onUnavailable`)으로만 집행된다.

## 6. 롤백

1. **판정 API를 끄기 전에** `ENFORCE` + fail-closed 고객이 있는지 확인한다. 끄면 `404`가 되어 그 고객의 호출이 모두 막힌다. 먼저 고객이 `ADVISORY`/`OBSERVE`로 되돌리거나 fail-open으로 바꾸게 한다(ADR 0001 기준으로 설정만으로 가능해야 한다. SDK에서 확인하지 못했다).
2. `token-pilot.decision.enabled=false`로 바꾸고 재시작한다. 이후 호출은 `404`다.
3. **`V8`은 되돌리지 않는다.** `decision_records`는 남겨 둔다. `policy_request_contexts.request_id`를 100자로 줄이면 101자 이상의 값이 생겼을 때 실패한다.
4. 기록된 판정과 정책·가격 snapshot은 삭제하지 않는다(insert/select 전용).

## 7. 문의 시 필요한 값

- 응답의 `decisionId`(`dec-` + 기록 ID)와 `requestId`. 이 둘로 `decision_records`와 `GET /api/policies/projects/{projectId}/request-context`에서 당시 정책·가격을 찾는다.
- 원문 prompt는 서버에 없다. 요청에도 기록에도 담지 않으며, 보내면 `400`으로 거부한다.
