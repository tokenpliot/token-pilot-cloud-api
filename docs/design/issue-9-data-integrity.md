# #9 기존 MySQL·인증·가격 정합성 및 마이그레이션 설계

- 상태: 리뷰 초안, 2026-10-04. 기준 커밋: `1f34f00` (최신 main).
- 선행: #3 ingestion. 계약: [ADR 0001](../adr/0001-decision-enforcement-boundary.md), [Control Plane v1](../api/control-plane-v1.yaml).
- 범위: 현행 코드/DDL 분석, 새 회계 값의 실행 가능한 불변식, 기존 프로젝트 키 인증을 가로막는 회원 키 필터 충돌 수정·통합 테스트, **자동 배포되지 않는** Flyway SQL 초안.
- 운영 MySQL에 접속하지 않았다. 실제 데이터 건수, DB 버전·collation, Flyway history와 DDL drift는 미확인이다. 아래 사전 검사를 복제 DB에서 수행해야 한다.

## 작업 범위와 로드맵 연결

[로드맵 #20](https://github.com/tokenpliot/token-pilot-cloud-api/issues/20)은 기존 Token Pilot core/Spring AI adapter를 유지하고 별도 Java Client SDK로 중앙 SaaS Control Plane에 연결하는 방향이다. 이 변경은 서버 저장소의 #9에 한정하며 오픈소스 코어/SDK 저장소를 변경하지 않는다.

| 이슈 | 이번 산출물과 경계 |
| --- | --- |
| #9 | 스키마·회계 규칙·인증 경계 조사, 마이그레이션 초안, 수집 경로의 프로젝트 격리 테스트. 필터 충돌만 수정하여 기존 프로젝트 인증까지 요청이 도달하도록 함 |
| [#10](https://github.com/tokenpliot/token-pilot-cloud-api/issues/10) | ingestion 멱등성/fingerprint/schema/배치 오류 강화는 구현하지 않음. 환경 등록 강제 및 UTC 만료 전환은 데이터 확인·전환 계획과 함께 처리할 제안 |
| [#11](https://github.com/tokenpliot/token-pilot-cloud-api/issues/11) | Observe 원장 저장·집계·조회 구현은 제외. 신규 회계 값은 아직 persistence/집계에 연결하지 않음 |
| [#12](https://github.com/tokenpliot/token-pilot-cloud-api/issues/12), #18 | 별도 저장소 Client SDK 및 기존 코어 연결 구현은 제외 |
| #14~#17 | 정책 변경·Advisory·Shadow·예약/정산 서비스는 제외. snapshot SQL은 #9에서 요청한 설계 초안만 제공 |

## 1. 현행 필드와 새 계약 대조

원본은 `src/main/resources/db/migration/V1__init_token_ledger_schema.sql`과 각 JPA entity다. V2는 users, V3/V4는 **회원용** api_keys를 추가/해시 전환한다. 프로젝트 키와 다른 테이블이다.

| 테이블 | 현행 필드(전체 그룹) | 새 계약/변경안 |
| --- | --- | --- |
| usage_events | id, organization_id, project_id, api_key_id, environment | tenant는 인증 키→project에서 결정. 클라이언트 조직/프로젝트 ID를 신뢰하지 않음. FK 정합성은 사전 검사 후 별도 강화 |
| usage_events | request_id, provider, model | requestId/provider/model. 현행 request_id nullable, `(project_id, environment, request_id)` unique. 신규 쓰기는 requestId 필수 |
| usage_events | prompt_tokens, completion_tokens, reasoning_tokens, cached_prompt_tokens, total_tokens | 부모 토큰에 상세가 포함되는 `INCLUSIVE_V1`; 현행 상세 기본값 0은 미제공과 0을 구별하지 못함. 새 adapter는 원문에서 nullable 상세 보존 |
| usage_events | prompt_cost_usd, completion_cost_usd, reasoning_cost_usd, cached_prompt_cost_usd, total_cost_usd | 기존 DECIMAL(18,6) NOT NULL/0 기본값은 v0 표시용으로 보존. 신규 권위 금액은 usage_accounting_values의 ESTIMATED/ACTUAL + PRICED/UNPRICED |
| usage_events | pricing_plan_id, pricing_version | 전자는 nullable이며 현재 FK 없음, 후자는 SDK 문자열. 서버가 검증한 불변 pricing_snapshot_id와 혼동 금지 |
| usage_events | source_type, metadata_json, occurred_at, created_at | source_type은 클라이언트 주장, 감사 주체 아님. UTC 발생/수신 시간 분리. 새 schema_version, call_status, settlement_state, accounting_status, token_semantics, actor_type/id, policy_snapshot_id 추가 |
| monthly_budget_settings | id, organization_id, project_id, environment, limit_usd | org→project→environment 범위. project NULL인 env 예산 금지. USD 유지, policy_version/currency/audit 주체 추가; NULL 범위를 포함하는 유일성은 중복 정리 뒤 강화 |
| monthly_budget_settings | threshold_50_enabled, threshold_80_enabled, threshold_100_enabled, created_at, updated_at | 알림 임계값일 뿐 집행 허용이 아님. 변경 때 policy snapshot에 예산/임계값/집행 설정을 함께 저장 |
| pricing_plans | id, catalog_id, provider, model, currency | 전역 서버 가격표. provider/model/currency 일치 검증. currency가 USD가 아니면 Phase 1 USD 회계에 사용하지 않음 |
| pricing_plans | prompt_rate, completion_rate, reasoning_rate, cached_prompt_rate | DECIMAL(18,6). 현재 단위 미저장. rate_unit_tokens 추가(신규 1,000,000); 기존 가격 단위는 확인 전 NULL. entity의 null→0 변환 때문에 상세 단가 0이 무료인지 미상인지 원본 확인 필요 |
| pricing_plans | version, effective_from, effective_to, created_at | provider/model/version unique, 유효기간 중첩 방지 없음. [from,to) 시점 선택, 겹치는 두 가격은 임의 선택하지 않고 UNPRICED |
| project_api_keys | id, organization_id, project_id, environment, name | environment NULL/blank는 현재 프로젝트 전체 환경 허용이며 이를 유지. 신규 발급/환경 등록 강제·blank 정리는 #10과 협의할 전환안 |
| project_api_keys | key_prefix, key_hash, status, last_used_at, expires_at, created_at, updated_at | BCrypt 검증 + ACTIVE + 기존 서버 로컬 시각 만료 + 프로젝트 ACTIVE. scope_version/audit 추가. 별도 grants는 다음 구현에서 도입; 버전만으로 권한을 부여하지 않음 |

현재 V1 테이블에는 tenant FK가 없다. JPA `ddl-auto=validate`와 Flyway만으로 기존 행의 조직 불일치를 검출할 수 없다. 날짜는 MySQL DATETIME(6), 신규 입력은 offset 필수→UTC 변환한다.

## 2. 인증 및 테넌트 경계

### 현재 확인한 동작과 이번 수정

- `ProjectApiKeyAuthenticator`는 hash 검증 후 키의 org 안에서 projectKey를 조회하고 project ID 일치/ACTIVE 상태, 키 환경을 검증한다. 다른 org의 projectKey는 404, 같은 org의 다른 project는 403이다.
- 회원용 `ApiKeyAuthenticationFilter`가 같은 X-API-Key 헤더를 먼저 회원용 SHA-256 테이블에서 검사하여 정상 프로젝트 키도 401이 되었다. 정확한 두 POST ingestion 경로만 해당 필터에서 제외하고, 실제 프로젝트 인증은 service에서 반드시 수행한다. 다른 경로와 메서드는 제외하지 않는다.
- 기존 인증기의 project ID/org 일치·키 환경 제한·서버 로컬 시각의 만료 비교를 그대로 유지한다. project_environments 등록 여부를 새 조건으로 강제하지 않는다. 향후 등록 검증은 org/project/environment 전체로 조회하되 기존 데이터 등록 이후 도입한다. UTC 전환 역시 기존 DATETIME 시간대 검증·변환 이후 별도 배포한다.
- 권한은 지금 `ingestion:write`에 한정한다. 프로젝트 키는 회원 principal로 승격되지 않으며 dashboard, projects, usage 조회 또는 internal 쓰기 권한을 얻지 않는다.

### 남아 있는 배포 차단 조건과 제거 순서

`SecurityConfig`의 anyRequest/대시보드/internal permitAll은 **익명 접근을 허용**한다. 일부 집계 repository는 organization predicate가 없고 usage cursor는 findById로 조회한다. 아래 테스트가 통과해도 전체 서비스의 tenant isolation이 완성됐다는 뜻이 아니다. 키 헤더를 빼고 접근하는 공개 경로, 회원 JWT의 조직 간 조회는 별도 위험이다.

1. `organization_members`를 users.id 기반으로 검증하는 `TenantContext(memberId, organizationId, role)` 구현. OAuth/JWT는 회원 신원만 증명하고, 선택 조직은 DB membership로 다시 확인한다. 회원용 api_keys도 동일 절차 적용. 조직 헤더만으로 허용하지 않음.
2. `ProjectService`와 `BudgetService`의 `DEFAULT_ORGANIZATION_ID="default-org"` 제거. controller→service→repository 모든 읽기/쓰기에 TenantContext 전달. NULL project 필터는 **선택 조직 전체**이지 전 조직 전체가 아님. 프로젝트 ID와 cursor도 동일 tenant predicate로 조회; 타 tenant 리소스는 404.
3. 조직 역할 정책: viewer 읽기, operator 수집·운영, admin 프로젝트/키/예산 관리. 전역 가격표 쓰기는 플랫폼 관리자만 허용. project key scope는 ingestion:write 기본, 향후 decision:request/settlement:write/approval:read를 명시 grant. approval 결재는 회원 권한으로 분리.
4. 기존 default-org를 실조직에 매핑하는 운영 승인 자료(프로젝트 소유자·회원 membership)를 준비. project 하위 environment/key/event/budget/aggregate를 같은 배치에서 이동. 증빙 없는 행은 격리하며 이메일 도메인·첫 회원으로 소유권을 추정하지 않는다.
5. 사용 프런트에 인증·조직 선택 배포 후 read 경로 authenticated + tenant 검증 적용. `/internal/usage-logs`는 서비스 계정 및 네트워크 제한 적용 또는 폐기. 익명 호환은 보존하지 않는다. 운영 공개 경로를 닫기 전 다중 tenant rollout 금지.
6. #10과 협의하여 등록 환경 검증 배포 전 기존 SDK 환경을 사전 목록화하고 관리자 확인 후 등록. 키의 기존 DATETIME 만료가 UTC인지 확인 후 변환; 시간대 미상 행은 자동 추정하지 않는다. 이 PR은 환경 등록 강제나 시각 비교 기준 변경을 배포하지 않는다.

## 3. 토큰 및 금액 불변식

`domain.accounting.UsageTokens`와 `AccountingAmount`는 신규 계약의 실행 가능한 값이다. 기존 서비스 저장/집계에 아직 연결하지 않았으며 기존 데이터를 몰래 재계산하지 않는다.

### 포함 토큰

`promptTokens`는 cache를 포함하고 `completionTokens`는 reasoning을 포함한다.

- `total = prompt + completion` (checked long addition). `0 <= cachedPrompt <= prompt`, `0 <= reasoning <= completion`.
- 예: prompt=1200, completion=400, cache=300, reasoning=100 → total=1600 (2000이 아님).
- 상세 미제공은 NULL(unknown)이며 확인된 0과 다르다. 부모 사용량이 미상이면 usage 자체가 미상이다.
- provider가 exclusive 값을 주면 SDK adapter가 포함 형태로 정규화하고 semantics/provider adapter version을 snapshot에 기록한다. 의미 미상 payload는 추정 합산하지 않는다.
- 현행 `UsageLogService.saveNew`와 `UsageLog.prePersist`는 total 미제공 시 네 항목을 더한다. v0 legacy writer는 그대로 유지하고, 신규 adapter는 정규화된 total을 반드시 명시한다. 과거 total만으로 포함/배타 의미를 역추정하여 덮어쓰지 않는다.

### 금액·통화·우선순위

- Phase 1은 USD만 지원. Java BigDecimal, 저장 DECIMAL(18,6), 신규 API는 소수 6자리 이내의 문자열. 입력에 double을 경유하지 않는다. 허용 최대 999999999999.999999, 음수/범위 초과/통화 불일치 거부. v0 응답의 JSON number는 유지한다.
- 단가 단위는 `rate_unit_tokens=1000000`. 중간 곱셈/덧셈은 반올림 없이 수행, 전체 합을 단위로 나눈 뒤 **최종 한 번 HALF_EVEN scale 6**. 입력 값의 7번째 유효 소수는 자동 반올림하지 않고 거부한다.
- 비용 버킷은 상호 배타적: `(prompt-cache)*promptRate + cache*cacheRate + (completion-reasoning)*completionRate + reasoning*reasoningRate`. reasoning 별도 단가가 없는 **확인된** 가격은 completionRate를 사용한다. cache 단가 미상 및 cache>0이면 UNPRICED. 상세 토큰 미상인데 상세·부모 단가가 다르면 UNPRICED. 동일 단가일 때만 부모 합계로 계산 가능하다.
- 예: 위 토큰, prompt=2, completion=8, cache=0.5, reasoning=8 USD/1M → 0.00515 USD. 기존 promptCost에 이미 cache가 포함된 SDK 비용을 다시 더하지 않는다.
- 반올림 예: 0.0000005→0.000000, 0.0000015→0.000002. **가격 근거가 있으면 0도 PRICED**. 표시 component 반올림 합과 최종 total 차이는 residual로 설명하고 authoritative total을 바꾸지 않는다.

| 우선순위 | 근거 | 처리 |
| --- | --- | --- |
| 1 | 검증된 provider 청구/정산 자료 (요청·프로젝트 귀속 확인) | ACTUAL/PRICED/PROVIDER_BILLING. 이전 서버 계산과 차이는 새 revision으로 기록, 추정값은 보존 |
| 2 | 서버의 불변 가격 snapshot + 유효한 토큰 | ESTIMATED 또는 ACTUAL / PRICED / SERVER_CATALOG. decision은 예약 시점 가격, usage는 연결된 decision snapshot, 독립 usage는 occurredAt 가격 |
| 3 | SDK 제공 비용·가격 버전만 있음 | `sdk_reported_cost_json`에 보조 증거로 저장. 서버 근거가 없으면 UNPRICED/NULL/NONE. 클라이언트 pricingPlanId/version으로 권위 가격을 강제할 수 없음 |

서버 단가와 SDK 비용 불일치 시 서버 계산을 사용하고 차이/SDK 버전/서버 snapshot을 감사 기록한다. 누락 가격, 중첩 유효기간, 잘못된 통화는 모두 UNPRICED. 가격표가 없다는 이유로 무료나 0원으로 정산하지 않는다.

| 사용량 근거 | 가격 상태 | 신규 회계 값 |
| --- | --- | --- |
| 호출 전 예상 1000토큰 | 가격 존재 | ESTIMATED / PRICED / amount 포함 |
| provider 실제 800토큰 | 가격 존재 | ACTUAL / PRICED / amount 포함 |
| provider 실제 800토큰 | 가격 미상 | ACTUAL / UNPRICED / amount NULL |
| 예상 사용량 | 가격 미상 | ESTIMATED / UNPRICED / amount NULL |
| 사용량도 미상 | 가격과 무관 | ACTUAL 값 생성하지 않음, UNSETTLED 유지 |

추정과 실제를 합산하지 않는다. 신규 사용 비용은 이벤트별 최신 ACTUAL/PRICED revision만 집계하고 unpricedCount/unknownUsageCount를 별도로 표시한다. 예약 잔액은 별도 reservation 상태로 관리하며 사용 비용에 중복 가산하지 않는다. ACTUAL은 실제 **사용량 기준**이며 청구서 확정 여부는 cost_source로 구분한다.

## 4. Snapshot·감사·event status

- `pricing_snapshots`: immutable ID/version, provider/model/currency, 가격·단위·유효기간·토큰 의미·선택 알고리즘을 canonical JSON으로 저장하고 SHA-256 checksum 부여. 기존 pricing_plans를 수정해도 과거 snapshot은 변경하지 않음.
- `policy_snapshots`: org/project/env 범위, version, 예산·월 경계(UTC)·threshold·집행 설정·규칙 JSON/checksum. 수정 시 새 ID와 version을 만든다. 변경 가능 monthly_budget_settings는 현재 설정 projection이다.
- request 본문의 sourceType/metadata는 actor가 아님. 인증 project key→PROJECT_API_KEY/id, 회원→MEMBER/users.id, 백필→SYSTEM/실행 ID. 근거 없는 과거 actor는 UNKNOWN/NULL. 원문 키/비밀은 저장하지 않음.
- `usage_accounting_values`는 event/value_kind/revision 유일, revision은 동일 event를 잠근 트랜잭션에서 증가. 기존 행 UPDATE 금지. 정정은 새 revision; latest revision만 합산. event 접근 권한을 통과한 쿼리로만 조회한다.
- call_status: SUCCEEDED / PROVIDER_FAILED / USAGE_UNKNOWN / BLOCKED_BY_CLIENT / LEGACY_UNKNOWN. settlement_state: SETTLED / UNSETTLED. provider 실패만으로 사용량 0이라고 확정하지 않는다.
- 실제 사용량+권위 가격 확보 시 SETTLED, 사용량/가격 미상은 UNSETTLED. 호출 전 BLOCKED는 검증된 미호출 증거가 있을 때만 무료 ACTUAL/PRICED로 기록 가능. SDK 주장만으로 예산 반환하지 않는다.
- accounting_status: LEGACY_UNVERIFIED(기존/구버전 writer), PENDING(신규 수집), VERIFIED(검증 완료), QUARANTINED(불일치). PRICED와 VERIFIED는 별도 의미다.
- requestId는 상관 ID, Idempotency-Key는 쓰기 연산 ID다. 신규 dedup 범위는 org/project/env/operation/key + canonical payload SHA-256. 동일 key·다른 payload는 409, 동일 payload 재전송은 동일 receipt. 현행 requestId 중복은 payload 비교 없이 기존 응답을 돌려주는 차이를 유지하고 v1에서는 개선해야 한다. settlement·decision dedup 테이블은 각 구현 이슈에서 추가한다.

## 5. Flyway 초안·백필·롤백

[SQL 디렉터리](../migrations/issue-9/)는 Flyway 기본 classpath 밖이다. V5는 #10의 `V5__ingestion_idempotency.sql`(`event_id`, `payload_fingerprint`)이 사용하므로 이 초안은 V6 후보이며, 승격 시 마지막 버전 다음으로 다시 정한다. 기존 마이그레이션 checksum은 변경하지 않는다.

1. **Preflight**: [preflight.sql](../migrations/issue-9/preflight.sql) 실행 결과 저장. 실제 MySQL `SELECT VERSION()`, SHOW CREATE TABLE, flyway_schema_history, charset/collation 대조. 복제 DB에 백업 복원까지 시험. orphan/tenant 불일치/가격 중첩/예산 중복은 소유자 확인 전 배포 중단.
2. **Expand**: [V6 초안](../migrations/issue-9/V6__accounting_snapshots_draft.sql). 기존 금액·응답을 건드리지 않고 nullable/default 컬럼과 새 테이블 추가. MySQL 8.0.16+ CHECK 지원을 전제로 함. 실제 버전/DDL 시간/잠금은 미검증. 운영 규모 복제 DB에서 소요 시간 및 잠금을 측정하고 쓰기 중지 창을 정한다.
3. **Writer 배포**: 구버전은 LEGACY_UNVERIFIED defaults로 계속 쓰며 신규 adapter만 accounting_values를 생성. snapshot과 usage/회계/감사 기록은 한 트랜잭션. 오류 시 전체 롤백. 가격 미상은 NULL로 수집하고 후속 reconciliation 대상으로 남긴다.
4. **Backfill**: [backfill.sql](../migrations/issue-9/backfill.sql)은 승인된 PK 구간만 `created_at <= cutoff`로 처리하고 동일 구간 재실행 가능. 이전 USD 값은 sdk_reported 증거로만 복사. 무조건 ACTUAL/PRICED로 승격하지 않음. 별도 증빙으로 토큰 의미/가격 단위/귀속/실제 여부를 입증한 행만 verifier가 snapshot과 ACTUAL revision을 추가. 실패 행은 QUARANTINED, 재처리 사유·실행 ID 보존.
5. **검산**: 구/신 전체 건수·tenant별 건수·USD 합계·날짜별 합계 비교. 원본 v0 합계가 변하지 않아야 함. 신규 actual 합계/추정 합계/unpriced·unknown 건수/격리 건수는 따로 보고하고 증빙 있는 차이만 허용. UUID PK는 시간순이 아니므로 최초 대상 PK 목록 또는 cutoff+키셋을 고정하고 watermark 기록. 구 writer가 뒤늦게 쓴 행은 재스캔한다.
6. **Contract 단계**: 모든 writer 전환·백필 검증 이후 별도 Flyway에서 복합 tenant FK, budget NULL-normalized unique, 상태 NOT NULL/check 제약 강화. 빈 문자열은 scope sentinel로 쓰기 전 금지하고 org/global scope 중복을 정리한다. 물리 삭제 대신 tombstone 유지. 가격 기간 중첩은 MySQL exclusion constraint 대신 범위 잠금+서비스 검증으로 차단.
7. **Rollback**: 신규 writer/read flag를 끄고 구 projection으로 복귀한다. 새 컬럼/테이블은 남겨 구 앱이 무시하도록 한다. provider 정산 자료와 revision을 지우지 않는다. DDL 일부 성공 시 SHOW CREATE TABLE와 Flyway history 대조 후 forward-fix; SQL 전체가 transaction rollback될 것이라고 가정하지 않는다. 잘못된 백필은 실행 ID별 증거/새 revision으로 정정. 파괴적 DROP은 보존 기간 경과·백업 복원 검증 이후 별도 변경으로만 수행한다.

MySQL DDL은 암묵적 commit이므로 여러 DDL 문 전체를 원자 롤백할 수 없다 ([MySQL 공식 문서](https://dev.mysql.com/doc/refman/8.0/en/atomic-ddl.html)). Flyway는 설정된 locations의 versioned SQL을 실행한다 ([Flyway 공식 설명](https://www.red-gate.com/hub/product-learning/flyway/maintaining-variants-of-a-database-using-flyway-locations/)).

## 6. API 하위 호환표

| 경로 | 현재 응답/인증 | 전환 경로 |
| --- | --- | --- |
| POST /api/ingestion/events | 201 ApiResponse.data `{eventId,accepted}`; project key | 형식 유지. 이번 수정: 정상 프로젝트 key가 기존 인증기에 도달 가능. 기존 환경 범위/만료 비교/요청명/숫자 USD 유지 |
| POST /api/ingestion/events/batch | 200 `{acceptedCount,rejectedCount,rejectedItems:[{index,requestId,code,message}]}` | 최대 100건·부분 validation 실패 형식 유지. key/scope 실패는 전체 요청 거부, 어떤 item도 쓰지 않음 |
| POST /internal/usage-logs | 201 UsageLogResponse를 ApiResponse로 포장; 현재 공개 | legacy shape 유지하되 서비스 인증 또는 폐기. body tenant 신뢰 금지. 수집 SDK는 project-key ingestion으로 전환 |
| GET /api/dashboard/kpi | `{totalCost,totalTokens,blockedRequests}` | v0 숫자 필드 유지. 인증/tenant 필터 먼저 강화; 신규 회계 집계로 조용히 치환하지 않음 |
| GET /api/dashboard/model-cost-summary | 배열 `{modelId,totalCost,totalTokens}` | v0 projection 유지, 신규 화면은 versioned endpoint 사용 |
| GET /api/dashboard/project-ranking | 배열 `{projectId,totalCost,totalTokens}` | 같은 응답, 조직 범위 필수 적용 예정 |
| GET /api/dashboard/overview | `{filters,kpis,costTrend,projectRanking,events}` | 기존 중첩 필드·숫자 비용 유지. v1은 actual/estimated와 unpricedCount 분리 |
| GET /api/usage-events | `{items:[eventId,projectId,environment,provider,model,totalTokens,totalCostUsd,occurredAt],nextCursor}` | cursor도 tenant 검증 예정. v1에서 nullable 금액과 상태 노출, v0에는 새 UNPRICED를 0원으로 투영하지 않음 |
| PUT /api/budgets, GET /api/budgets/summary | limitUsd/thresholds, budgetLimitUsd/spentUsd/remainingUsd/usagePercent | schema 유지, org 하드코딩 제거; v1은 미정산 포함 여부·예약을 명시 |
| /api/control/v1/usage-events, /api/control/v1/decisions, /api/control/v1/reservations/{reservationId}/settlements | #8 문서 계약만 존재 | 구현 전 아래 계약 충돌 해결. 기존 `/api` 경로와 별도 도입 |

v0 조회는 legacy `usage_events`/집계 projection에 남긴다. 신규 v1-only UNPRICED 이벤트를 v0 0 USD로 노출하지 않도록 **reader 분리 및 accounting_status 필터를 먼저 배포**한 뒤 신규 writer를 활성화한다. 이를 구현하기 전에는 SQL 초안만으로 새 ingestion을 켜지 않는다. sunset 날짜는 SDK/프런트 전환율 확인 후 공지한다.

### #8 계약과 함께 해결할 사항

현재 schemaVersion `2026-10-01`은 SUCCEEDED/COMMIT에서 cost를 필수로 하고 PRICED/UNPRICED와 ESTIMATED/ACTUAL 필드가 없다. 성공했지만 unpriced인 요청을 cost=0으로 위장하지 않는다. 새 회계 envelope(`kind,pricingStatus,amount,currency,source,snapshotId`)와 미상 비용 허용은 **새 schemaVersion 및 /v2** 초안으로 합의한 후 공개한다. 이 PR은 기존 YAML이나 v1 client 계약을 바꾸지 않는다. 예:

```json
{"kind":"ACTUAL","pricingStatus":"UNPRICED","amount":null,"currency":"USD","source":"NONE","snapshotId":null}
```

#8 Identifier 최대 128과 기존 DB environment(20), provider(50), model/requestId(100), pricing_version(50)의 불일치도 남아 있다. v1 구현 전 참조하는 모든 scope 컬럼/인덱스를 함께 확장하는 별도 migration 또는 **새 계약 버전에서** 제한 조정이 필요하다. 절단 저장/기존 계약의 묵시적 제한 변경은 금지한다.

## 7. 검증 및 완료 기준

- `ProjectKeyIsolationIntegrationTest`: 실제 Spring Security chain + BCrypt + H2 repository. 같은 org의 타 프로젝트/타 org/키 범위 밖 환경/폐기·만료·누락 키/비활성 프로젝트/조직 불일치 거부, batch 전체 인증 실패 시 무저장, 동일 requestId의 프로젝트별 격리와 타 프로젝트 중복 수집으로 기존 event ID를 얻지 못함을 검증한다. 환경 행이 없는 기존 키와 wildcard 동작, 한국 로컬 시각 만료도 회귀 테스트한다.
- `memberKeyFilterRejectsProjectKeysOutsideIngestion`는 회원 키/프로젝트 키 인증 namespace 분리만 검증한다. 조회 API의 tenant 격리 테스트가 아니다. 리뷰 재현에서 프로젝트 B 사용량을 넣고 A 키 헤더를 보내면 401이지만 헤더를 제거하면 200으로 B 사용량이 노출됐다. 기존 공개 조회 경로는 별도 인증/tenant 변경 전까지 해결되지 않으며, #9 테스트 결과를 전체 서비스의 접근 격리 완료로 해석하지 않는다.
- `AccountingContractTest`: cache/reasoning 중복 방지, subset·overflow 검증, 실제 미가격과 무료 구분, USD scale/range·source 검증.
- 검증 결과(2026-10-04): 기존 controller/decision 계약 테스트 포함 `./gradlew test` **77 tests, 0 failures, 0 errors, 0 skipped**. 신규 15개 테스트 포함. H2 테스트는 MySQL DDL/Flyway 실행 증거가 아니다.
- 로컬 MySQL 9.5.0의 별도 임시 datadir 초기화를 시도했으나 서버가 초기화 도중 SIGSEGV로 종료되어 SQL 실행 검증은 하지 못했다. 운영 DB에는 접근하지 않았다. 대상 MySQL 8 복제 DB에서 V1~V4→초안 적용, 구 writer insert, PRICED/UNPRICED 제약, 백필 2회 실행의 멱등성, 구 앱 롤백을 검증해야 한다.
- 리뷰 산출물: 네 테이블 대조표, tenant 제거/접근 설계, 토큰/금액/우선순위 규칙, snapshot/status SQL, 사전검사·백필·롤백, API 호환표.
- 후속 구현: TenantContext 및 공개 경로 차단, 신규 versioned accounting API/adapter·snapshot writer, MySQL 복제 DB 리허설 및 실제 제약 적용. 이번 이슈는 설계 초안이며 운영 마이그레이션 완료를 주장하지 않는다.
