# 기존 ingestion API 호환성 (`/api/ingestion/events`, `/batch`)

- 관련 이슈: #10 (기존 ingestion API의 멱등성·배치 오류·schema version 강화), 완료 기준 2
- 대상: `POST /api/ingestion/events`(단건), `POST /api/ingestion/events/batch`(배치)
- 함께 읽기: [Project API Key 검증](ingestion-api-key-verification.md), 계약 초안 [`control-plane-v1.yaml`](control-plane-v1.yaml), [ADR 0001](../adr/0001-decision-enforcement-boundary.md)
- 이 문서의 동작은 H2와 내장 Tomcat에서 테스트로 확인한 것이다. MySQL에서의 확인 범위는 [알려진 한계](#7-알려진-한계)에 따로 적었다.

## 1. 원칙

기존 SDK 호출이 코드 변경 없이 계속 동작하도록 **추가만** 했다.

| 항목 | 결정 |
| --- | --- |
| 경로 | 유지 (`/api/ingestion/events`, `/api/ingestion/events/batch`). 계약의 `/api/control/v1/usage-events`로 옮기지 않았다. |
| 성공 상태코드 | 단건 `201`, 배치 `200` 유지. 계약의 `202`로 바꾸지 않았다. |
| 인증 헤더 | `X-API-Key` 유지(프로젝트 키). `Idempotency-Key` 헤더는 지원하지 않는다. |
| 요청 필드 | 기존 필드 전부 유지. 새 필드(`eventId`, `schemaVersion`)는 **선택**이다. |
| 응답 필드 | 기존 필드 전부 유지, 의미도 같다. 새 필드만 추가했다. |
| 응답 래퍼 | `ApiResponse`(`success`, `code`, `message`, `data`, `errors`, `timestamp`) 유지 |

**의도적으로 바뀐 동작은 둘뿐이다.** (1) 같은 키에 *다른* payload를 다시 보내면 예전에는 조용히 성공으로 처리됐지만 이제는 `409`이다. (2) 입력 검증이 더 엄격해졌다(알 수 없는 `schemaVersion`, 크기 제한). 금지 metadata 키는 기본 모드에서 거부하지 않고 **제거한 뒤 저장**한다([5절](#5-요청-검증과-개인정보)). 둘 다 [6절](#6-계약-문서와의-차이)과 [5절](#5-요청-검증과-개인정보)에 적었다.

## 2. 단건 `POST /api/ingestion/events`

### 요청

기존 요청은 그대로 유효하다.

```json
{
  "projectKey": "support-copilot",
  "environment": "prod",
  "requestId": "req_123",
  "provider": "openai",
  "model": "gpt-4o-mini",
  "promptTokens": 1200,
  "completionTokens": 400,
  "reasoningTokens": 0,
  "totalTokens": 1600,
  "promptCostUsd": 0.00018,
  "completionCostUsd": 0.00024,
  "reasoningCostUsd": 0,
  "totalCostUsd": 0.00042,
  "pricingVersion": "2026-05-01",
  "occurredAt": "2026-05-06T10:00:00Z",
  "metadata": { "tenantId": "tenant-a" }
}
```

| 필드 | 상태 | 비고 |
| --- | --- | --- |
| 위 예시의 모든 기존 필드 | **유지** | 필수/선택과 형식 변경 없음 |
| `eventId` | **추가(선택)** | 최대 100자. 멱등성 키로 `requestId`보다 우선한다([3절](#3-멱등성)). |
| `schemaVersion` | **추가(선택)** | 없으면 허용. 값이 있으면 `2026-10-01`만 허용하고 그 외는 `400`. |
| `metadata` | **동작 변경** | 금지 키(`prompt`, `system_prompt`, `completion`, `messages`, `content`, `response`)는 기본 모드에서 **제거한 뒤 저장**한다(`400` 아님). 엄격 모드에서만 `400`. 그 밖의 형식 위반은 기본 모드에서 경고 로그만 남기고 통과한다([5절](#5-요청-검증과-개인정보)). |

### 응답 `201`

| 필드 | before | after |
| --- | --- | --- |
| `data.eventId` | 저장된 사용 이벤트 ID | **유지** (같은 의미. 요청의 `eventId`가 아니라 서버가 부여한 ID) |
| `data.accepted` | 항상 `true` | **유지** |
| `data.duplicate` | 없음 | **추가**: 이미 저장된 이벤트의 재전송이면 `true` |
| `data.requestId` | 없음 | **추가**: 요청의 `requestId` |

```json
{ "success": true, "code": "SUCCESS", "message": "사용 이벤트 수집 성공",
  "data": { "eventId": "6f1d…", "accepted": true, "duplicate": false, "requestId": "req_123" },
  "errors": [], "timestamp": "…" }
```

- 재전송(`duplicate: true`)도 **`201`** 이다. 기존 SDK는 성공으로 본다.
- 응답의 `message`는 이전과 같다.

## 3. 멱등성

### 키

```
(project, environment, eventId ?? requestId)
```

- `project`는 인증된 키의 프로젝트이고, `environment`는 요청이 보낸 값이다.
- `eventId`가 있으면(공백 제외) 그것이 키이고, 없으면 `requestId`가 키이다. `requestId`는 기존처럼 필수다.
- 저장소 유니크 제약이 최종 판정자이다: `(project, environment, request_id)`와 `(project, environment, event_id)`.

### 같은 키로 다시 보냈을 때

| 상황 | 결과 |
| --- | --- |
| 같은 키, **같은 payload** | 기존 이벤트를 그대로 반환(`duplicate: true`, `201`). 저장은 늘지 않는다. |
| 같은 키, **다른 payload** | `409` `INGESTION-409` (before: 조용히 기존 이벤트를 반환했다) |
| `eventId`가 같고 `requestId`가 다름 | `409` (다른 호출로 본다) |
| 새 `eventId`인데 `requestId`가 이미 다른 이벤트에 저장됨 | `409` |
| 이 변경 이전에 저장된 행(fingerprint 없음)과 같은 키 | 비교할 수 없으므로 기존 이벤트를 반환(`duplicate: true`) |
| 동시에 같은 키 요청 | 한 건만 저장되고 나머지는 위 규칙대로 `duplicate: true` 또는 `409` (before: 유니크 위반으로 `500`이 날 수 있었다) |
| `requestId`는 같고 `eventId`가 다른 요청(순차·동시) | `409`. 동시 N건이면 한 건만 저장되고 나머지는 모두 `409`이다(`500` 아님). 위 표의 "새 `eventId`인데 `requestId`가 이미 다른 이벤트에 저장됨"과 같은 규칙이다. **동시성 테스트는 H2 기반이다**(실제 유니크 제약은 걸리지만 MySQL의 락·에러 거동은 아니다). |

동시 요청에서 유니크 위반이 났는데 기존 행을 찾지 못하면 `503` `INGESTION-503`(재시도 가능)이다. 데드락·락 타임아웃도 같은 `503`이다.

### payload fingerprint

"같은 payload"는 정규화한 본문의 SHA-256(소문자 hex)이 같다는 뜻이다. 현재 규칙 버전은 `v1`이다.

- **포함**(이 순서로): `provider`, `model`, `promptTokens`, `completionTokens`, `reasoningTokens`, `cachedPromptTokens`, `totalTokens`, `promptCostUsd`, `completionCostUsd`, `reasoningCostUsd`, `cachedPromptCostUsd`, `totalCostUsd`, `pricingPlanId`, `pricingVersion`, `sourceType`, `metadata`, `occurredAt`
- **metadata는 금지 키를 제거한 뒤의 값으로 계산한다.** 저장되는 값과 fingerprint가 같은 데이터를 보게 하고, 원문이 섞였다는 이유만으로 같은 이벤트가 `409`가 되지 않게 하기 위한 결정이다. 같은 이벤트를 금지 키 유무나 그 값만 바꿔 다시 보내면 `duplicate: true`이고, 나머지 metadata가 다르면 `409`이다.
- **제외**: 키 필드(`environment`, `requestId`, `eventId`)와 인증으로 정해지는 값(조직, 프로젝트, API 키 ID), `schemaVersion`

정규화(의미가 같으면 같은 해시):

| 대상 | 규칙 |
| --- | --- |
| 생략된 토큰·비용 | 저장 시 기본값과 같게 본다(`reasoningTokens`·`cachedPromptTokens`·비용은 `0`) |
| `totalTokens`, `totalCostUsd` 생략 | 구성 요소의 합으로 본다 |
| 금액 | 값으로 비교한다(`0.10` = `0.1`) |
| `sourceType` | 비어 있으면 `sdk` |
| `metadata` | 키를 재귀적으로 정렬한다. 없음·빈 문자열·`{}`는 같다 |
| `occurredAt` | UTC로 바꾸고 마이크로초까지만 본다(저장 정밀도) |
| 필드 경계 | 길이 접두로 이어 붙여 필드 값이 서로 섞여 충돌하지 않는다 |

규칙을 바꾸면 모든 해시가 바뀌므로 `PayloadFingerprint.VERSION`을 올려야 한다.

## 4. 배치 `POST /api/ingestion/events/batch`

### 요청

기존 요청(`projectKey`, `environment`, `items[]` 최대 100건)은 그대로 유효하다. 새 필드는 최상위의 선택 `schemaVersion`뿐이다. **항목별 `schemaVersion`은 지원하지 않는다.** 항목의 `eventId`는 단건과 같이 선택이다.

### 응답 `200`

| 필드 | before | after |
| --- | --- | --- |
| `data.acceptedCount` | 저장됐거나 이미 있던 항목 수 | **유지** (중복도 포함하는 의미 그대로) |
| `data.rejectedCount` | 거절된 항목 수 | **유지** |
| `data.rejectedItems[]` | `index`, `requestId`, `code`, `message` | **유지** + `retryable` 추가 |
| `data.createdCount` | 없음 | **추가**: 새로 저장된 항목 수 |
| `data.duplicateCount` | 없음 | **추가**: 이미 저장돼 있던 항목 수 |
| `data.items[]` | 없음 | **추가**: 모든 항목의 결과, 요청 순서 |

`items[]`의 한 항목:

| 필드 | 의미 |
| --- | --- |
| `index` | 요청 안의 위치(0부터) |
| `requestId` | 항목의 `requestId` (null 항목이면 null) |
| `status` | `CREATED`, `DUPLICATE`, `REJECTED` |
| `usageEventId` | `CREATED`·`DUPLICATE`일 때 저장된 이벤트 ID, 아니면 null |
| `code`, `message` | `REJECTED`일 때 오류 코드와 설명 |
| `retryable` | 같은 항목을 다시 보내면 성공할 수 있는지 |

```json
{ "success": true, "code": "SUCCESS", "message": "배치 수집 성공",
  "data": {
    "acceptedCount": 2, "rejectedCount": 1,
    "rejectedItems": [ { "index": 2, "requestId": "r3", "code": "INGESTION-503", "message": "…", "retryable": true } ],
    "createdCount": 1, "duplicateCount": 1,
    "items": [
      { "index": 0, "requestId": "r1", "status": "CREATED",   "usageEventId": "…", "code": null, "message": null, "retryable": false },
      { "index": 1, "requestId": "r2", "status": "DUPLICATE", "usageEventId": "…", "code": null, "message": null, "retryable": false },
      { "index": 2, "requestId": "r3", "status": "REJECTED",  "usageEventId": null, "code": "INGESTION-503", "message": "…", "retryable": true } ] },
  "errors": [], "timestamp": "…" }
```

### 처리 모델: 항목 단위 저장, 원자적이지 않음

- 배치 전체를 감싸는 트랜잭션이 **없다.** 항목마다 따로 처리하고 따로 커밋한다. 한 항목의 실패는 다른 항목을 롤백하거나 막지 않는다. (before: 배치 전체가 하나의 트랜잭션이었다.)
- 항목은 요청 순서대로 처리하고, 결과는 항목별로 돌려준다.
- **같은 배치를 다시 보내는 것은 안전하다.** 이미 저장된 항목은 `DUPLICATE`가 되어 같은 `usageEventId`를 돌려주고, 실패했던 항목만 새로 저장된다. 항목 순서만 바꿔 다시 보내도 항목별 결과(상태, 코드, `usageEventId`)는 같다.
- 두 배치가 같은 항목을 동시에 보내도 항목마다 한 번만 저장된다.
- 한 배치 안에서 같은 키가 반복되면 먼저 처리된 항목이 이긴다. 같은 payload는 `DUPLICATE`, 다른 payload는 `409`로 거절된다. 다른 payload끼리 충돌할 때만 순서에 따라 누가 이기는지가 달라진다.
- **인증 실패는 요청 전체 거부**이다. 항목별 결과 없이 `401`/`403`/`404`로 끝나고 아무것도 저장되지 않는다.
- 락 계열이 아닌 예상 밖 오류는 요청 전체가 `500`이 된다. 이때 앞선 항목은 이미 저장돼 있고, 클라이언트는 어느 항목이 저장됐는지 응답으로 알 수 없다. 같은 배치를 다시 보내면 저장된 항목은 `DUPLICATE`로 처리된다. 원인이 계속되는 오류는 다시 보내도 같은 `500`이다.
- 항목이 하나라도 거절돼도 HTTP 상태는 `200`이다. 전부 거절돼도 `200`이다.

## 5. 요청 검증과 개인정보

| 항목 | 정책 | 기본값(설정 키) |
| --- | --- | --- |
| `schemaVersion` | 없으면 허용. 알 수 없는 값만 `400`(지원 버전만 안내하고 입력값은 돌려주지 않음) | 지원 `2026-10-01` (코드 상수) |
| metadata 금지 키 | **기본(비엄격) 모드**: `400`이 아니다. 금지 키만 metadata에서 제거하고 나머지로 정상 저장한다(배치 항목도 `REJECTED` 없이 `CREATED`). 경고 로그에는 종류(`FORBIDDEN_KEY`)와 개수만 남기고 키 이름·값은 남기지 않는다. **엄격 모드**: `400`, 메시지는 `metadata contains a forbidden key`이며 키 이름과 값을 담지 않는다(배치에서는 해당 **항목만** `REJECTED`). 키 이름만 검사하며(대소문자 무시, 중첩·리스트 안의 맵 포함) 값은 보지 않는다. 어떤 모드에서도 금지 키와 그 값은 DB·로그·응답에 남지 않는다. | `token-pilot.ingestion.forbidden-metadata-keys` = `prompt, system_prompt, completion, messages, content, response` |
| metadata 형식(항목 16개 초과, 키 형식 `^[a-z][a-z0-9_.-]{0,63}$` 위반, 문자열이 아닌 값, 256자 초과) | 기본은 경고 로그만 남기고 통과(키·값은 로그에 없음, 금지 키를 제거한 나머지로 판단). 엄격 모드에서는 `400`(최대 16개·키 패턴·값 256자 이하 계약 규칙 적용) | `token-pilot.ingestion.strict-metadata` = `false` |
| 요청 크기 | 단건 16KB, 배치 1MB 초과 시 `413`(`INGESTION-413`). 인증·JSON 파싱 전에 거부 | `token-pilot.ingestion.max-event-bytes` = `16384`, `max-batch-bytes` = `1048576` |
| 검증 실패 응답 | 입력값을 되돌려주지 않는다(`errors[].rejectedValue`는 `null`, 키는 유지) | - |
| 원문 prompt/completion | 저장하지 않는다. 최상위의 알 수 없는 필드는 무시된다([한계](#7-알려진-한계)) | - |

위 설정은 `application.yml`의 `token-pilot.ingestion.*` 또는 환경 변수로 재정의한다. 금지 키 목록을 재정의하면 기본 목록과 **합쳐지지 않고 대체**된다. 기본값은 코드에 있으므로 설정이 없어도 동작한다.

요청이 거치는 순서는 크기 필터 → Spring Security의 회원용 키 필터 → 요청 검증(`schemaVersion`, 금지 키) → 서비스의 프로젝트 키 검증 → (배치) 항목별 검증·저장이다. 검증 오류(`400`)는 프로젝트 키 검증보다 먼저 나갈 수 있다. 응답 조합은 [Project API Key 검증](ingestion-api-key-verification.md#3-요청-처리-순서와-응답-조합)에 있다.

### 오류 코드와 재시도 가능 여부

재시도 가능 여부는 ADR 0001 §4의 기준(`408`, `429`, `5xx`는 가능, 그 외 `4xx`는 불가)을 따른다. 재시도해도 안전하다: 같은 요청은 멱등성 키로 중복 저장되지 않는다.

| 코드 | HTTP | 의미 | 재시도 | 나오는 곳 |
| --- | --- | --- | --- | --- |
| `COMMON-400` | 400 | 요청 검증 실패(필수 필드 누락, 형식, `eventId` 길이, 알 수 없는 `schemaVersion`, 엄격 모드의 금지 metadata 키 등) | 불가 | 단건·배치 요청 전체, 배치 **항목** |
| `COMMON-401` | 401 | 키 누락·미존재·폐기·만료 | 불가 | 요청 전체 |
| `COMMON-403` | 403 | 환경·프로젝트 불일치, 비활성 프로젝트 | 불가 | 요청 전체 |
| `COMMON-404` | 404 | `projectKey` 없음 | 불가 | 요청 전체 |
| `INGESTION-409` | 409 | 같은 멱등성 키에 다른 payload | 불가 | 단건, 배치 **항목** |
| `INGESTION-413` | 413 | 요청 본문이 크기 한도 초과 | 불가(본문을 줄여야 함) | 요청 전체 |
| `INGESTION-503` | 503 | 동시 요청을 해소하지 못함, 데드락·락 타임아웃 | **가능** | 단건, 배치 **항목** |
| `COMMON-500` | 500 | 예상 밖 서버 오류. 깨진 JSON·빈 본문·잘못된 `Content-Type`·허용되지 않은 메서드도 여기로 나온다([한계](#7-알려진-한계)) | 서버 오류는 가능하나 원인이 계속되면 같은 결과, 요청 오류(깨진 JSON 등)는 불가 | 요청 전체 |

- 인증 오류(401·403·404)의 세부 메시지와 검사 순서는 [Project API Key 검증](ingestion-api-key-verification.md)에 있다.
- 배치 **항목**의 오류는 `items[]`/`rejectedItems[]`의 `code`와 `retryable`로 나온다(`retryable`은 위 표의 재시도 열과 같다).
- `INGESTION-503`에 `Retry-After` 헤더는 붙지 않는다([한계](#7-알려진-한계)).

## 6. 계약 문서와의 차이

[`control-plane-v1.yaml`](control-plane-v1.yaml)과 ADR 0001은 중앙 Control Plane의 **목표 계약**이고, 이 문서의 API는 그것으로 가기 전의 기존 경로를 강화한 것이다. 같은 표가 YAML의 `x-legacy-ingestion-compat`와 ADR 부록 A에도 있다.

| 항목 | 계약 문서 | 기존 ingestion 경로(현재) |
| --- | --- | --- |
| 경로 | `POST /api/control/v1/usage-events` | `POST /api/ingestion/events` (+ `/batch`) |
| 성공 상태코드 | `202` | 단건 `201`, 배치 `200` |
| 배치 | 정의 없음 | `/batch`, 항목별 결과 |
| 멱등성 키 | `Idempotency-Key` 헤더(필수, 8~128자) | 헤더 없음. 본문의 `(project, environment, eventId ?? requestId)` |
| 키 충돌 | `409` `IDEMPOTENCY_CONFLICT` | `409` `INGESTION-409` |
| 재전송 응답 | `duplicate: true` | `duplicate: true`(`201`) |
| 응답 필드 | `usageEventId`, `requestId`, `duplicate`, `settlementState`, `schemaVersion` | `eventId`, `accepted` + `duplicate`, `requestId`. `usageEventId`는 배치 항목에만 있고 `settlementState`·`schemaVersion`은 없다 |
| `schemaVersion` | 필수, `2026-10-01` 고정 | 선택. 없으면 허용, 있으면 `2026-10-01`만 |
| 프로젝트 식별 | 키만으로 식별(본문에 없음) | 본문의 `projectKey`가 키의 프로젝트와 일치해야 함 |
| 요청 구조 | `callStatus` 필수, `usage`·`cost` 중첩 | 평면 구조(`promptTokens`, `promptCostUsd` 등), `callStatus` 없음 |
| 알 수 없는 필드 | `additionalProperties: false`로 거부 | 무시(`prompt` 같은 필드도 거부되지 않음) |
| metadata | 문자열 값만, 16개 이하, 값 256자 이하 | 금지 키는 제거 후 저장, 나머지는 경고 후 통과(엄격 모드는 금지 키와 계약 규칙 위반을 모두 `400`) |
| 오류 코드 | `INVALID_REQUEST`, `UNAUTHORIZED`, `NOT_FOUND`, `IDEMPOTENCY_CONFLICT`, `RATE_LIMITED`, `CONTROL_PLANE_UNAVAILABLE` | `COMMON-400/401/403/404`, `INGESTION-409/413/503` |
| 오류 응답의 `data.outcome` | `INDETERMINATE` 또는 `CONTROL_PLANE_UNAVAILABLE` | 없음 |
| `Retry-After` | `429`·`503`에 제공 | 없음 |
| `429` 과부하 | 있음 | 없음(요청 제한 없음) |

## 7. 알려진 한계

| 항목 | 내용 |
| --- | --- |
| scope 미지원 | 프로젝트 키에 scope(권한 범위)가 없다. 활성 키는 단건·배치를 모두 호출할 수 있고 `ingestion:write` 같은 확인은 불가능하다. #9의 `scope_version`(감사 라벨)이 병합된 뒤 다룬다. |
| ~~환경이 비어 있는 키 + 21자 이상 환경값 → `500`~~ (해결됨: `400`) | 요청의 `environment`를 `@Size(max = 20)`으로 검증한다. 키의 환경이 `null`·빈 문자열이면 모든 환경에서 유효하다는 점은 그대로이며, 이때 21자 이상은 단건 `400`, 배치는 요청 전체 `400`이다(20자는 정상). `provider`·`model`·`requestId` 등 다른 필드의 길이 초과도 `400`이다(배치는 해당 항목만 `REJECTED`). `IngestionFieldLengthTest`, H2에서 확인했다. |
| 최상위 알 수 없는 필드는 조용히 무시 | `prompt` 같은 필드를 보내도 거부되지 않는다. 저장도 로깅도 되지 않는다. 전역 `FAIL_ON_UNKNOWN_PROPERTIES`를 바꾸지 않았다. 새 계약 버전에서 다룬다. |
| 배치 항목별 `schemaVersion` 미지원 | 최상위 `schemaVersion`만 검사한다. |
| `Retry-After` 미지원 | `INGESTION-503`에도 헤더·`retryAfterSeconds`가 없다. 클라이언트가 백오프를 정해야 한다. |
| `acceptedCount`는 중복 포함 | 기존 SDK 호환을 위한 것이다. 새로 저장된 수는 `createdCount`를 본다. |
| 이전 행은 payload 비교 불가 | fingerprint가 없는 기존 행과 같은 키로 보내면 비교 없이 기존 이벤트를 반환한다. |
| 프로젝트 키가 실제 HTTP에서 거부됨 | 회원용 `ApiKeyAuthenticationFilter`가 모든 `X-API-Key`를 회원 키로 검사해 프로젝트 키도 `401`이 된다. #9 브랜치가 고친다. 자세한 내용과 테스트 변경 필요는 [Project API Key 검증](ingestion-api-key-verification.md#알려진-문제-프로젝트-키가-실제-http에서-거부된다-9에서-수정)에 있다. |
| 프레임워크 요청 오류가 모두 `500` | 깨진 JSON, 빈 본문, 지원하지 않는 `Content-Type`, 허용되지 않은 HTTP 메서드(예: `GET`)가 `400`/`415`/`405`가 아니라 `500` `COMMON-500`으로 응답된다. 전역 `GlobalExceptionHandler`의 `Exception` 처리가 프레임워크 예외까지 가로채기 때문이며 이번 이슈 이전부터의 동작이다. 단건·배치 모두 같고 **수정하지 않았다.** 내장 서블릿 환경의 MockMvc에서 확인했다. |
| MySQL 미검증 | 실제 락·데드락 거동과 Spring의 예외 번역, REPEATABLE READ 스냅샷, V5 마이그레이션의 실제 적용, 유니크 위반 판별(에러 1062), `json` 컬럼 저장 방식, 상태값·`projectKey`의 대소문자 비교(collation), 운영 연결 풀은 확인하지 못했다. |

## 8. 배포·클라이언트 메모

- **마이그레이션**: `V5__ingestion_idempotency.sql`은 `usage_events`에 nullable 컬럼 `event_id`, `payload_fingerprint`와 유니크 `(project_id, environment, event_id)`를 추가한다. 구 바이너리와 호환된다(NULL은 유니크에서 중복으로 취급되지 않는다). #9의 V5 초안과 번호가 겹치므로 먼저 병합되는 쪽이 V5를 쓰고 다른 쪽이 번호를 바꾼다.
- **SDK**: 변경 없이 동작한다. 재시도 시 같은 `requestId`(또는 `eventId`)와 같은 payload를 보내면 중복 회계가 생기지 않는다. 가능하면 `eventId`를 보내는 것을 권장한다.
- **롤백**: 새 컬럼은 nullable이라 앱만 되돌려도 DB를 되돌릴 필요가 없다.

## 9. 호환성을 보장하는 테스트

| 약속 | 테스트 |
| --- | --- |
| 옛 SDK 요청이 그대로 동작하고 기존 응답 필드가 같은 의미로 유지됨 | `IngestionLegacyCompatibilityTest` |
| 기존 컨트롤러 계약(`201`/`200`, 래퍼, 검증 오류 `COMMON-400`) | `IngestionControllerTest`(기존 3개 테스트는 수정 없음) |
| 같은 키·같은 payload 재전송, 다른 payload `409`, 키 우선순위 | `UsageLogServiceIdempotencyTest`, `IngestionServiceTest` |
| fingerprint 정규화 규칙 | `PayloadFingerprintTest` |
| 동시 요청에서 한 건만 저장, 유니크 위반 재조회, `503` 매핑, 같은 `requestId`·다른 `eventId`의 순차·동시 `409` (모두 H2 기반) | `UsageLogServiceConcurrencyTest` |
| 금지 키 제거·DB/로그/응답 비노출(기본·엄격 모드), fingerprint | `IngestionForbiddenMetadataDefaultModeTest`, `IngestionForbiddenMetadataStrictModeTest`, `IngestionMetadataPolicyTest` |
| 배치 항목별 결과, 순서만 바꾼 재전송, 부분 실패, 동시 배치 | `IngestionBatchIntegrationTest` |
| `schemaVersion`, metadata 정책, 크기 제한, 입력값 비반사 | `IngestionRequestValidationTest`, `IngestionMetadataPolicyTest`, `IngestionRequestSizeFilterTest`, `IngestionHttpIntegrationTest`, `IngestionValidationResponseTest`, `IngestionBatchMetadataIntegrationTest` |
| 잘못된 API Key는 아무것도 저장하지 않음, 검사 순서 | `ProjectApiKeyAuthenticatorTest`, `ProjectApiKeyAuthenticatorIntegrationTest`, `IngestionApiKeyFailureIntegrationTest`, `IngestionAuthOrderHttpTest` |
