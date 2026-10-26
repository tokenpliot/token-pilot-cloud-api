# #14 프로젝트·환경 정책 버전과 변경 감사

## 범위와 후속 연동

Phase 1의 `policy_snapshots`/`monthly_budget_settings` 계약을 실행 가능한 관리 API로 확장한다. #14는 정책 저장·버전·변경 감사·복구와 요청별 정책/가격 참조 보존을 담당한다. 판정 알고리즘/Advisory HTTP API(#15), Shadow 실행·예상 거부율(#16), 원자적 예약(#17), opt-in Enforce/break-glass(#18/#19)는 구현하지 않는다. 오픈소스 `tokenpilot`과 Client SDK는 수정하지 않는다. 조직→팀→사용자 계층 정책은 지원하지 않는다.

## 정책과 한도

- 하나의 `(organization, project, environment)`에 stable UUID `policyId`가 있다. environment 생략은 프로젝트 전체 범위다. 빈 문자열은 API에서 거부하고 저장 키에서만 프로젝트 범위로 사용한다. 구체 환경은 프로젝트에 등록된 이름과 대소문자까지 일치해야 한다.
- `revision`은 정책별 1부터 증가한다. V6의 조직별 version UNIQUE를 만족하도록 공개 `version`은 `<policyId>:<revision>`이다. 같은 조직의 서로 다른 정책은 모두 revision 1로 시작할 수 있다.
- 새 정책·수정·비활성화·롤백은 항상 새 `policy_snapshots` 행을 INSERT한다. 현재 포인터만 `customer_policies.current_version`에서 바뀐다. 물리 삭제 API는 없다.
- `monthlyHardLimitUsd`는 필수다. `monthlySoftLimitUsd`, `requestSoftLimitUsd`, `requestHardLimitUsd`는 nullable이며 null은 해당 한도가 없다는 뜻이다. 0은 명시적인 0 한도다. USD/BigDecimal/DECIMAL(18,6) 정밀도를 따르며 음수·범위 초과·6자리 초과 소수는 반올림하지 않고 거부한다. soft가 hard보다 클 수 없다.
- soft는 경고/검토 권고, hard는 거부 권고의 기준이다. 둘 다 **자동 차단 허가가 아니다**. `active=false`는 해당 범위의 모든 규칙을 평가에서 제외한다. notificationThresholds(50/80/100%)는 알림 설정이며 soft/hard 규칙과 별개다.
- 월 경계는 UTC 달력 월 `[첫날 00:00, 다음달 첫날 00:00)`이다. 프로젝트 정책의 월 사용액은 모든 환경 합계, 환경 정책의 월 사용액은 해당 환경 합계다.
- 프로젝트 정책과 환경 정책이 동시에 있으면 **활성인 모든 범위의 제한을 만족해야 한다**. 환경 정책이 프로젝트 정책을 덮어쓰거나 완화하지 않는다. 월 예산은 각각의 범위에서 평가하고 요청당 한도는 각각 검사한다. 한쪽이 비활성이어도 다른 쪽은 유지된다. #15는 `rules.active`를 검사해야 한다.
- snapshot JSON에는 규칙 전체, 범위, UTC 월 경계, 조합 규칙, 자동 집행 없음, JSON schema version, legacy budget 출처를 복사한다. checksum은 작성 시 canonical JSON(고정된 필드 순서, 정규화된 소수 문자열 금액, 정렬된 임계값)의 SHA-256이다. MySQL JSON의 출력 공백/키 순서는 원문과 다를 수 있으므로 DB 출력 문자열을 그대로 해시해 비교하지 않는다. 금액을 JSON 숫자로 저장하면 MySQL에서 정밀도를 잃을 수 있어 문자열로 저장한다.

## 권한과 감사 주체

- `/api/policies/**`는 인증 필수다. JWT/회원 API Key는 기존 회원 신원을 사용한다. OAuth는 provider+providerId 조합으로 회원을 찾는다. 클라이언트가 보낸 actor/org 문자열은 권한 근거로 사용하지 않는다.
- 프로젝트의 organization_id와 **기존 `organization_members`**의 users.id 멤버십으로 매 요청 권한을 확인한다. OWNER/ADMIN은 변경·복구, OPERATOR/VIEWER는 조회·미리보기가 가능하다. 조직 멤버십이 없으면 리소스 존재를 숨기는 404다. 프로젝트가 비활성이면 수정은 409이며 과거 이력 조회는 가능하다.
- 수집용 `project_api_keys`는 회원용 `api_keys`와 다른 인증 공간이다. 관리 API를 호출할 수 없다. 이 PR은 프로젝트 키에 관리 권한을 추가하지 않는다.
- `actorType=MEMBER`, `actorId=users.id`, UTC `changedAt`, `changeType`, 필수 reason 및 복구 대상 revision을 불변 snapshot에 기록한다. 예산 이관을 수행한 현재 주체는 알려지지만 과거 작성자가 복원됐다고 주장하지 않는다.
- 멤버십 자동 부여/관리 API는 추가하지 않는다. 배포 전 운영자가 검증된 고객/프로젝트 관계를 기존 조직 멤버십에 등록해야 한다. 단순히 로그인했다는 이유로 default-org 관리자 권한을 주지 않는다. 다른 기존 API의 default-org/공개 접근 문제 전체를 고치는 PR은 아니다.

## 변경 원자성과 동시성

`projects`의 해당 행을 SELECT FOR UPDATE한 뒤 head 조회도 locking read로 수행한다. MySQL REPEATABLE READ에서 인증 단계의 이전 read view 때문에 최신 revision을 놓치지 않는다. `expectedVersion=0`은 최초 생성에만, 이후에는 현재 revision과 일치할 때만 허용한다. 같은 revision에 대한 두 변경 중 하나만 성공하고 나머지는 409다.

head 변경, 불변 snapshot INSERT, 월 예산 projection 갱신을 **한 트랜잭션**으로 커밋한다. 중복 legacy 예산이 있으면 임의의 첫 행을 선택하지 않고 409로 중단한다. 프로젝트 공통 정책과 환경 정책도 같은 프로젝트 잠금으로 직렬화한다. 요청 context capture도 같은 잠금을 사용해 한 순간의 두 정책을 묶는다. 파일럿 규모의 정확성을 우선한 잠금이며 고처리량 판정 서비스의 성능은 #15/#17에서 측정한다.

## 요청 당시 정책·가격 설명

`PolicyContextService.capture(AuthenticatedProjectApiKey, environment, requestId, pricingSnapshotId)`는 **서버 내부 연동 지점**이다. #15는 프로젝트 키 인증을 마친 뒤 서버가 선택/생성한 불변 가격 snapshot ID를 넘겨야 한다. SDK가 제시한 임의 snapshot ID를 신뢰하는 public 생성 API는 제공하지 않는다.

- `(org, project, environment, requestId)`에 최초 1회 `policy_request_contexts`를 저장한다. project/env snapshot ID, 가격 snapshot ID, 인증 키 ID 및 UTC 시각을 기록한다. 비활성 버전도 보존해 당시 제한이 적용되지 않은 이유를 설명한다. 존재하지 않았던 범위는 NULL이다.
- 재시도는 최초 context를 반환한다. 현재 정책·가격으로 다시 선택하거나 덮어쓰지 않는다. #15는 요청 입력 fingerprint 충돌을 별도로 검사해야 한다.
- 가격이 없으면 priceStatus=UNAVAILABLE, price=null로 보존한다. 무료/0원으로 변환하지 않는다.
- `GET .../request-context`는 당시 snapshot과 규칙/가격 버전을 조회한다. 이후 새 버전 작성·비활성화·rollback에도 기존 참조는 그대로다. pricing_plans를 재조회하지 않는다.
- **현재 기존 ingestion/Observe가 이 capture를 호출하지는 않는다.** #15가 판정 경로에 연결하며, #16은 context ID에 판정 입력(월 누적값/토큰 상한 포함), 알고리즘 버전, outcome·근거·실제 집행 결과를 추가 기록한다. #14의 context만으로 판정 결과까지 재실행했다고 주장하지 않는다. 테스트는 실제 DB에 context를 생성하고 정책/가격 변경 후 동일 요청의 불변 버전 조회를 검증한다.

## 영향 미리보기와 즉시 복구

`POST .../impact`는 쓰기 없이 baseVersion, 이전 snapshot, 제안 규칙, 영향받는 등록 환경, 향후 환경 포함 여부를 반환한다. 프로젝트 정책 변경은 현재/미래 모든 환경, 환경 정책은 하나의 환경에 영향을 준다. 영향은 새로운 request context부터 적용되고 이미 캡처된 요청은 바뀌지 않는다. `STRUCTURAL_ONLY`이며 예상 거부율·false denial 수치를 만들어 내지 않는다. 그런 추정은 #16의 표본/과거 트래픽 Shadow 결과를 첨부한다.

운영 순서: 현재 버전/영향 조회 → Shadow 근거 검토(#16) → 동일 expectedVersion으로 수정 → 신규 요청 지표 확인. 광범위한 변경은 먼저 한 환경에서 검증한 뒤 프로젝트 범위로 확대한다. 미리보기와 저장 사이 다른 수정이 있으면 409로 재검토한다.

복구는 `POST .../rollback {expectedVersion, restoreVersion, reason}` 한 번으로 이전 규칙·active·알림 설정을 복사한 새 revision을 만든다. 예: v1→v2 변경 후 v1 복구는 v3이며 v2를 삭제하지 않는다. 새 request context부터 v3를 사용한다. 이미 진행 중인 요청은 기존 snapshot을 유지한다. rollback과 별개로 자동 차단을 끄는 break-glass는 #19 범위다.

## 기존 API와 이관

| 경로/데이터 | 동작 |
| --- | --- |
| v0 `PUT /api/budgets`, 미전환 범위 | 기존 입력/응답과 변경 방식 유지 |
| v0 `PUT /api/budgets`, 전환한 동일 범위 | 409: 버전 API 사용. 감사 우회 덮어쓰기를 허용하지 않음 |
| v0 summary/dashboard | 기존 응답 형태, monthly hard 한도와 알림 projection 유지. soft/request/active를 표현하지 않으므로 정책 판정에 사용 금지 |
| 새 `PUT /api/policies/projects/{id}` | 완전 교체, 새 revision과 actor/time/reason 반환 |
| `POST .../import-monthly-budget` | 정확히 같은 프로젝트/환경의 단일 legacy budget을 v1로 복사. soft/request 한도는 null. 알림 flags 유지 |
| 조직 범위 legacy 예산(project_id NULL) | 자동 이관/상속하지 않음. 이번 파일럿 범위 밖 |
| 기존 snapshot/usage/accounting/ingestion 응답 | V6 식별자 유지. 기존 응답 계약 불변 |

일괄 자동 백필은 하지 않는다. 범위별 중복/고아/음수·통화·멤버십을 사전 검사하고 고객 확인 후 import한다. 신규 관리 API는 default-org를 하드코딩하지 않고 프로젝트의 실제 조직을 사용한다. v0 summary 자체의 조직/인증 한계는 기존 상태이며 새 관리 API가 그 경로를 사용하지 않는다.

마이그레이션/배포 rollback 절차: [issue-14 migration runbook](../migrations/issue-14/README.md). HTTP 요청 예시: [policy API](../api/policy-management.md).
