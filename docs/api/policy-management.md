# 프로젝트·환경 정책 관리 API (#14)

Base: `/api/policies/projects/{projectId}`. JWT `Authorization: Bearer ...` 또는 **회원용** X-API-Key로 인증한다. 모든 요청은 프로젝트 조직 멤버십 검증을 거친다. `?environment=prod`를 생략하면 프로젝트 전체 범위다. actor/org는 요청 body에서 받지 않는다.

응답은 기존 `ApiResponse {success,code,message,data,errors,timestamp}` envelope다.

| Method / suffix | 입력 | data |
| --- | --- | --- |
| GET base | environment? | 현재 `PolicyVersionView` |
| PUT base | Replace | 생성/변경된 `PolicyVersionView` |
| POST `/import-monthly-budget` | `{reason}` | legacy 설정에서 생성한 revision 1 |
| GET `/versions` | environment?, beforeVersion? (exclusive), limit? (1~100, 기본 50) | revision 내림차순 이력. 다음 페이지는 마지막 revision을 beforeVersion에 사용 |
| GET `/versions/{revision}` | environment? | 지정 당시 규칙·주체·시각·사유 |
| POST `/impact` | Replace | baseVersion, previous, proposed, affectedRegisteredEnvironments, includesFutureEnvironments, STRUCTURAL_ONLY |
| POST `/rollback` | `{expectedVersion,restoreVersion,reason}` | 이전 내용을 복사한 **새 revision** |
| GET `/request-context` | environment, requestId | context, policies(최대 2), price, priceStatus |

```json
{
  "expectedVersion": 0,
  "rules": {
    "currency": "USD",
    "monthlySoftLimitUsd": 80.000000,
    "monthlyHardLimitUsd": 100.000000,
    "requestSoftLimitUsd": 0.500000,
    "requestHardLimitUsd": 1.000000,
    "active": true,
    "notificationThresholds": [80, 100]
  },
  "reason": "고객 승인한 prod 파일럿 한도"
}
```

0은 최초 생성에만 사용한다. PUT은 부분 수정이 아니므로 모든 규칙을 다시 보낸다. soft/request 한도는 null 가능, monthlyHardLimitUsd/active/notificationThresholds는 필수다. 빈 임계값 배열은 모든 알림 임계값을 끈다. reason은 공백이 아닌 최대 500자다. reason에 API Key나 prompt 등 비밀을 적지 않는다.

PolicyVersionView는 policyId, revision(숫자), version(`<policyId>:<revision>`), snapshotId, organizationId, projectId, environment, rules, actorType/id, changedAt(UTC ISO-8601), changeType, reason, restoredFromVersion, checksum, snapshotJson을 반환한다. `active=false`인 현재 버전도 조회된다.

금액 입력은 JSON 숫자 또는 십진 문자열을 받으며, 응답과 snapshot JSON은 `"100.000000"`처럼 문자열로 보존한다. MySQL JSON 숫자의 부동소수점 변환으로 DECIMAL(18,6) 정밀도를 잃지 않기 위한 계약이다.

```json
{"expectedVersion": 2, "restoreVersion": 1, "reason": "확대 적용 후 승인된 기존 한도로 복구"}
```

성공은 200. 잘못된 금액/환경/JSON/필수 파라미터는 400, 인증 없음/수집용 Project API Key는 401, 조직 내 조회 전용 역할의 쓰기는 403, 타 조직/없는 리소스는 404, stale version·이미 이관한 범위·중복 legacy 예산·비활성 프로젝트 수정은 409다. 잠금/DB 장애는 정책 거부로 표현하지 않는다.

`request-context` 조회는 #15가 내부 `PolicyContextService.capture`를 호출해 context를 저장한 요청에만 가능하다. 수집 이벤트 전체를 임의로 과거 정책에 연결하지 않는다. policies의 각 rules.active가 true인 규칙들을 함께 적용한다. 가격 누락은 priceStatus=UNAVAILABLE, price=null이다.
