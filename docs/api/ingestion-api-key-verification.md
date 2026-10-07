# Ingestion Project API Key 검증

- 관련 이슈: #10 (작업 5: Project API Key scope 및 만료·폐기 검증 재확인)
- 대상: `POST /api/ingestion/events`, `POST /api/ingestion/events/batch`
- 구현: `ProjectApiKeyAuthenticator` (동작은 이 문서의 내용 그대로이며, 이번 이슈에서 **동작을 바꾸지 않고** 테스트로 고정하고 문서화했다)
- 같은 헤더(`X-API-Key`)를 쓰는 회원용 API Key와는 다른 키·다른 테이블이다.

## 1. 검증 순서와 응답

위에서부터 순서대로 검사하고 첫 실패에서 끝난다. 모든 실패는 재시도해도 성공하지 않으며(재시도 불가), 응답 메시지에 키 값이 들어가지 않는다.

| 순서 | 검사 | 실패 시 | 메시지 |
| --- | --- | --- | --- |
| 1 | 키가 없거나 공백 | `401` | Project API key is required. |
| 2 | `status = 'ACTIVE'`이고 `key_prefix` 후보가 맞는 행 중 BCrypt 해시가 일치하는 키 | `401` | Invalid project API key. |
| 3 | 만료: `expires_at`이 있고 서버 로컬 `now()`보다 과거 | `401` | Project API key has expired. |
| 4 | 환경: 키에 환경이 있으면 요청의 `environment`와 정확히 일치(대소문자 구분) | `403` | Project API key is not allowed for this environment. |
| 5-a | 프로젝트: `(키의 조직, projectKey)`로 조회, 없음 | `404` | Project was not found for projectKey. |
| 5-b | 조회된 프로젝트가 키의 프로젝트가 아님 | `403` | Project API key is not allowed for this project. |
| 5-c | 프로젝트 상태가 `ACTIVE`가 아님 (`ARCHIVED`, `DELETED`) | `403` | Project is not active. |

- 모두 통과하면 키의 `last_used_at`을 갱신한다. 실패한 요청은 갱신하지 않는다.
- 응답 본문은 `ApiResponse` 형태이고 `code`는 `COMMON-401`, `COMMON-403`, `COMMON-404`이다.
- `prefix` 후보: 키가 30자 이하면 키 전체, 더 길면 앞 30자. 여기에 마지막 `_` 또는 `.` 앞부분(최대 30자)을 더한다.
- 이 검사들은 **배치에서도 요청 전체에 한 번** 적용된다. 키가 잘못되면 항목별 결과 없이 위 응답으로 끝나고, 항목은 하나도 저장되지 않는다.

### 구분하지 않는 것과 구분하는 것

- 존재하지 않는 키, 틀린 키, 폐기·비활성 키는 **같은 응답**(순서 2)이다. 키의 존재 여부를 알려주지 않기 위해서다.
- 만료(순서 3)는 해시가 맞은 뒤에만 도달하므로 키를 가진 쪽에만 구분되어 보인다.
- 프로젝트가 없는 경우(404)와 다른 프로젝트의 키인 경우(403)는 구분된다. 유효한 키를 가진 쪽은 자기 조직의 `projectKey` 존재 여부를 알 수 있다.

## 2. 하지 않는 검증과 처리 방침

| 항목 | 현재 상태 | 이번 처리 |
| --- | --- | --- |
| **scope(권한 범위)** | `project_api_keys`에 scope가 없다. 활성 키는 단건·배치 ingestion을 모두 호출할 수 있고 `ingestion:write` 같은 확인은 불가능하다. | 변경하지 않음. #9 초안의 `scope_version`(감사 라벨이며 권한 grant가 아님)과 겹치므로 #9 병합 후 다룬다. |
| **폐기** | `status`가 `ACTIVE`가 아니면 모두 폐기로 취급한다(`REVOKED`, `DISABLED` 등 값과 무관). 폐기 시각·사유·주체는 없다. 만료와 폐기는 응답으로 구분된다(순서 2 vs 3). | 변경하지 않음. `revoked_at`은 #9 소관이다. 이 저장소에는 프로젝트 키의 발급·회전·폐기 코드가 없다. |
| **키의 환경이 비어 있음 (`null` 또는 빈 문자열)** | **모든 환경에서 유효**하다. 요청의 `environment`는 별도 검증 없이 그대로 쓰이고, 사용 이벤트도 요청이 보낸 환경으로 저장된다. | 동작 유지, 테스트로 고정. 환경을 제한하려면 키에 환경을 지정해야 한다. |
| 만료 시각의 시간대 | DB의 `datetime`(시간대 없음)을 서버 로컬 `now()`와 비교한다. 저장 기준(UTC인지 로컬인지)을 확인할 발급 코드가 없다. | 변경하지 않음. #9 문서의 "UTC 만료 전환은 데이터 확인과 함께"와 겹친다. |
| 무차별 대입·요청 제한 | 없다. 같은 prefix의 활성 키마다 BCrypt 비교를 한다. | 범위 밖 |
| 환경 등록 여부 | 요청의 `environment`가 프로젝트에 등록된 환경인지 확인하지 않는다. | 범위 밖(#9에서 제안만 됨) |

### 해결됨: 환경이 비어 있는 키 + 21자 이상의 환경값은 400

요청의 `environment`는 `@Size(max = 20)`(`usage_events.environment`의 `varchar(20)`과 같다)으로 검증한다. 키의 환경이 비어 있어도 21자 이상이면 저장까지 가지 않고 **400(`COMMON-400`)** 이다. 단건은 `400`, 배치는 `environment`가 요청 최상위 필드이므로 항목별 결과 없이 요청 전체가 `400`이며 아무것도 저장되지 않는다. 20자는 정상이다. 검증이 프로젝트 키 확인보다 먼저라서 키의 환경 설정과 무관하게 같은 결과이다. `provider`(50), `model`(100), `requestId`(100), `eventId`(100), `pricingPlanId`(36), `pricingVersion`(50), `sourceType`(30)의 길이 초과도 단건은 `400`, 배치는 해당 항목만 `REJECTED`(`COMMON-400`)이다. 확인은 H2에서 했다(`IngestionFieldLengthTest`).

### 대소문자와 DB collation

`status = 'ACTIVE'` 비교와 `projectKey` 조회는 DB의 collation에 따라 대소문자를 구분하지 않을 수 있다. H2 기본은 구분하고 MySQL 기본 collation은 구분하지 않으므로, `active` 같은 값이 MySQL에서 활성으로 인정되는지는 확인하지 못했고 테스트로 고정하지 않았다. 환경 비교(순서 4)는 자바에서 하므로 대소문자를 구분한다.

## 3. 요청 처리 순서와 응답 조합

실제 HTTP에서 요청이 거치는 순서는 다음과 같다.

1. 크기 필터: 한도 초과는 `413` (`INGESTION-413`)
2. Spring Security: 회원용 `ApiKeyAuthenticationFilter`는 두 ingestion POST 경로(`/api/ingestion/events`, `/api/ingestion/events/batch`)를 건너뛴다(#9). `X-API-Key`는 회원 키로 검사되지 않고 4에서 프로젝트 키로만 검사된다.
3. 요청 검증: `schemaVersion` 등은 `400` (`COMMON-400`). metadata 금지 키는 엄격 모드에서만 `400`이고 기본 모드는 제거 후 통과
4. 서비스의 프로젝트 키 검증(위 1절)
5. (배치) 항목별 검증과 저장

| 요청 | 응답 | 이유 |
| --- | --- | --- |
| 키 없음 + 알 수 없는 `schemaVersion` | `400` | 3이 4보다 먼저 |
| 키 없음 + metadata 금지 키 | 기본 모드 `401`, 엄격 모드 `400` | 기본 모드에서 금지 키는 검증 오류가 아니다(제거 후 저장) |
| 키 없음 + 올바른 본문 | `401` "Project API key is required." | 4 |
| 알 수 없는 키(`X-API-Key` 있음) + 알 수 없는 `schemaVersion` | `400` | 3이 4보다 먼저 |
| 알 수 없는 키 + 정상 본문 | `401` "Invalid project API key." | 4 |
| 유효한 활성 프로젝트 키 + 정상 본문 | `201` | |
| 유효한 활성 프로젝트 키 + 알 수 없는 `schemaVersion` | `400` | 3 |

검증 오류(`400`)는 인증보다 먼저 나가지만 일반적인 문구만 담고 입력값을 되돌려주지 않는다. 배치의 항목별 metadata 검사는 서비스 안에서 4 이후에 일어나므로, 키가 잘못된 배치는 항목 검사에 도달하지 못한다.

### 해결됨: 프로젝트 키가 실제 HTTP에서 거부되던 문제 (#9)

회원용 `ApiKeyAuthenticationFilter`가 모든 `X-API-Key`를 회원 키로 검사해서 유효한 프로젝트 키도 `401`이 되던 문제는 #9에서 두 ingestion POST 경로를 이 필터에서 제외해 해결했다. 필터는 원본 URI를 정확히 비교하므로, 인코딩·후행 슬래시 등 변형 경로는 필터를 건너뛰지 않고 회원 키 검사로 `401`이 된다(닫힌 쪽으로 실패). `IngestionAuthOrderHttpTest`가 현재 동작을 고정한다.

## 4. 테스트 대응

| 확인 내용 | 테스트 |
| --- | --- |
| 검사 순서·응답·메시지, `lastUsedAt`, prefix 후보 | `ProjectApiKeyAuthenticatorTest` |
| 실제 DB의 상태 필터(`ACTIVE` 외 거절), 폐기와 미존재 키가 같은 응답, 만료, 환경, 프로젝트 | `ProjectApiKeyAuthenticatorIntegrationTest` |
| 잘못된 키의 배치·단건은 아무것도 저장하지 않음, 환경이 비어 있는 키 | `IngestionApiKeyFailureIntegrationTest` |
| 인증 실패의 HTTP 상태·코드와 본문에 키가 없음 | `IngestionAuthErrorResponseTest` |
| 실제 HTTP에서의 처리 순서와 응답 조합 | `IngestionAuthOrderHttpTest` |
