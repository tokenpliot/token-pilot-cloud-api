# ADR 0001. 판정·집행 책임 경계와 Control Plane API 계약

- 상태: 승인됨 (Phase 1 초안)
- 날짜: 2026-10-02
- 관련 이슈: #8, 로드맵 #20
- 계약 원본: [`docs/api/control-plane-v1.yaml`](../api/control-plane-v1.yaml)
- 실행 가능한 계약: `com.tokenledgercloud.api.domain.decision`

## 배경

Token Pilot Cloud는 고객 Java 애플리케이션의 LLM 호출에 대해 사용량을 기록하고 정책 판정을 반환하는 중앙 Control Plane으로 확장된다.
중앙 서버가 고객의 provider 호출을 직접 막는 구조가 되면, 서버 장애나 판정 불가 상황이 고객 서비스 장애로 번진다.
따라서 **누가 정책을 소유하고, 누가 판정하며, 누가 집행하는지**를 먼저 고정해야 한다.

## 결정

### 1. 책임 분리

| 역할 | 소유자 | 설명 |
| --- | --- | --- |
| 정책 정의 | 고객 | 예산·모델 허용 목록·승인 규칙과 적용 범위(프로젝트·환경)를 고객이 정한다. |
| 판정 | Token Pilot Control Plane | 정책과 원장을 근거로 `DecisionOutcome`과 사유를 반환한다. 판정은 provider 호출을 바꾸지 않는다. |
| 집행 | 고객 애플리케이션(Java Client SDK) | 고객 설정(`EnforcementSettings`)에 따라 판정을 호출 진행·차단·승인 대기로 변환한다. |

Control Plane은 provider 호출 경로에 있지 않다. 집행 여부는 언제나 클라이언트의 고객 설정으로만 결정된다.

### 2. 집행 모드

| 모드 | 의미 | 차단 가능 여부 | 전환 조건 |
| --- | --- | --- | --- |
| `OBSERVE` (기본값) | 사용량·판정을 기록만 한다. | 없음 | 새 프로젝트·환경 연결 시 기본값 |
| `ADVISORY` | 판정을 경고(advisory)로 애플리케이션에 전달한다. | 없음 | 고객이 명시적으로 켠다. Observe 원장이 실제 사용량과 일치함을 확인한 뒤 권장 |
| `ENFORCE` | 집행 범위에 포함된 판정만 차단·승인 대기로 집행한다. | 고객이 고른 범위만 | 명시적 opt-in. Shadow 검증과 롤백 경로를 통과한 프로젝트·환경에서만 파일럿으로 허용 |

- 모드 전환은 고객 설정 변경으로만 일어나며, 서버가 응답으로 모드를 올리거나 내리지 않는다.
- `ENFORCE`에서 `ADVISORY`/`OBSERVE`로의 롤백은 언제나 허용되며 재배포 없이 설정만으로 가능해야 한다(break-glass).

### 3. 판정 결과(`DecisionOutcome`)

| 결과 | 의미 | 분류 | 반환 방식 |
| --- | --- | --- | --- |
| `ALLOW` | 정책상 허용 | 허용 | `200` |
| `DENY_POLICY` | 모델·용도 등 정책 규칙 위반 | 정책 거부 | `200` |
| `DENY_BUDGET` | 예산 한도 초과 | 정책 거부 | `200` |
| `REQUIRE_APPROVAL` | 사람 승인이 필요 | 승인 대기 | `200` |
| `INDETERMINATE` | 요청은 받았으나 근거 부족(가격 누락, 정책 미설정, 잘못된 요청 등)으로 판정 불가 | 판정 불가 | `200` 또는 클라이언트 측 매핑 |
| `CONTROL_PLANE_UNAVAILABLE` | Control Plane이 판정을 제공하지 못함(장애·과부하·통신 실패) | 서버 장애 | `503` 또는 클라이언트 측 매핑 |

정책 판정은 **HTTP 성공(`200`)으로 전달되는 정상 결과**다. 정책 거부를 `4xx`로 표현하지 않는다.

### 4. 정책 거부와 서버 장애의 분리

어떤 HTTP 오류나 통신 실패도 `DENY_POLICY`·`DENY_BUDGET`으로 변환하지 않는다. 클라이언트는 다음 규칙으로만 오류를 판정 결과로 바꾼다.

| 클라이언트가 받은 결과 | 변환되는 판정 |
| --- | --- |
| `408`, `429`, `5xx`, 연결 실패·타임아웃 | `CONTROL_PLANE_UNAVAILABLE` |
| 그 밖의 `4xx` (`400`, `401`, `403`, `404`, `409`, `422` 등) | `INDETERMINATE` |

`401`/`403`(키 폐기·권한 없음)도 정책 거부가 아니다. 고객 설정이 허용하지 않는 한 호출을 막지 않으며, 감사 로그와 운영 지표에서 정책 거부와 별도로 집계한다.

### 5. 집행 결과(`EnforcementAction`) 행렬

`EnforcementSettings`는 `mode`, 집행 범위(`enforcedOutcomes`), 판정 불가 시 동작(`onIndeterminate`), 장애 시 동작(`onUnavailable`)으로 구성된다. 기본값은 `OBSERVE`, `FAIL_OPEN`, `FAIL_OPEN`이다.

| 판정 \ 모드 | `OBSERVE` | `ADVISORY` | `ENFORCE` |
| --- | --- | --- | --- |
| `ALLOW` | `PROCEED` | `PROCEED` | `PROCEED` |
| `DENY_POLICY`, `DENY_BUDGET` | `PROCEED` | `PROCEED_WITH_ADVISORY` | 집행 범위에 있으면 `BLOCK`, 없으면 `PROCEED_WITH_ADVISORY` |
| `REQUIRE_APPROVAL` | `PROCEED` | `PROCEED_WITH_ADVISORY` | 집행 범위에 있으면 `HOLD_FOR_APPROVAL`, 없으면 `PROCEED_WITH_ADVISORY` |
| `INDETERMINATE` | `PROCEED` | `PROCEED_WITH_ADVISORY` | `onIndeterminate`가 `FAIL_CLOSED`면 `BLOCK`, 아니면 `PROCEED_WITH_ADVISORY` |
| `CONTROL_PLANE_UNAVAILABLE` | `PROCEED` | `PROCEED_WITH_ADVISORY` | `onUnavailable`이 `FAIL_CLOSED`면 `BLOCK`, 아니면 `PROCEED_WITH_ADVISORY` |

- 기본 설정(`OBSERVE`)에서는 어떤 판정도 provider 호출을 막지 않는다.
- `ADVISORY`는 `FAIL_CLOSED` 설정이 있어도 비차단이다. 장애 시 동작은 `ENFORCE`에서만 의미가 있다.
- `ENFORCE`의 기본 집행 범위는 `DENY_POLICY`, `DENY_BUDGET`이며 `REQUIRE_APPROVAL` 집행은 고객이 따로 켠다.
- 감사 기록은 `BLOCK`의 원인이 정책 거부인지, 판정 불가인지, 장애인지를 판정 결과로 구분해 남긴다.

### 6. 식별자·멱등성·버전

- 모든 요청·이벤트 본문은 `schemaVersion`(현재 `"2026-10-01"`)을 가진다. 호환되지 않는 변경은 새 날짜 버전과 새 경로(`/v2`)로 낸다.
- `requestId`: 고객 애플리케이션의 LLM 호출 1건을 식별한다. 판정·사용량·정산 이벤트가 같은 값을 공유한다.
- `Idempotency-Key` 헤더: 모든 쓰기 요청에 필수다. 같은 키와 같은 본문은 같은 결과를 돌려주고, 같은 키에 다른 본문은 `409`를 반환한다.
- `decisionId`: 서버가 발급하는 판정 식별자. 감사·Shadow 비교의 기준이다.
- `reservationId`: `ENFORCE`에서 `ALLOW`된 예산 예약 식별자. `OBSERVE`/`ADVISORY`에서는 발급하지 않는다.
- `approvalId`: `REQUIRE_APPROVAL` 판정에서 발급하며 승인 상태를 조회할 때 쓴다.

예약 상태 전이(라이브러리 `ReservationState`와 같은 이름을 쓴다):

```
RESERVED ──▶ IN_FLIGHT ──▶ COMMITTED
   │             │
   │             ├──▶ RECONCILIATION_REQUIRED ──▶ COMMITTED | WRITTEN_OFF
   │             └──▶ RELEASED
   └──▶ RELEASED (호출 전 취소·만료)
```

`COMMITTED`, `RELEASED`, `WRITTEN_OFF`는 종료 상태이며, 종료 상태에 대한 중복 정산은 원래 결과를 그대로 반환한다.

승인 상태 전이: `PENDING ──▶ APPROVED | REJECTED | EXPIRED`.

### 7. 개인정보와 필수 데이터

- 판정·사용량·정산 API는 **원문 prompt와 completion을 받지 않는다.** 스키마는 `additionalProperties: false`로 정의된 필드 외 입력을 거부한다.
- 필수 데이터: 프로젝트 키(헤더), `environment`, `requestId`, `provider`, `model`, 추정·실제 토큰 수, 비용, 가격 버전, 발생 시각.
- `metadata`는 최대 16개의 문자열 키·값(값 최대 256자)만 허용하며, 사용자 식별자는 고객이 가명화한 값만 넣도록 문서화한다. 기본 SDK 설정은 `metadata`를 비워 보낸다.

## 결과

- 서버 장애나 판정 불가가 정책 거부로 집계되지 않으므로, 감사·오류·운영 지표를 분리할 수 있다.
- 기본값이 `OBSERVE`이므로 연결 직후 고객 provider 호출이 Token Pilot 때문에 실패하지 않는다.
- 클라이언트 SDK(`tokenpliot/tokenpilot`)는 `EnforcementSettings`와 오류 매핑 규칙을 같은 의미로 구현해야 한다. 서버 저장소의 계약 테스트(`DecisionContractTest`, `ControlPlaneOpenApiContractTest`)가 기준이다.
- `ENFORCE`에서 고객이 `FAIL_CLOSED`를 고르면 Control Plane 가용성이 고객 호출 가용성에 영향을 준다. 이 위험은 고객이 명시적으로 선택한 경우에만 생긴다.
