# #14 V7 배포·이관·복구

대상: MySQL 8.0.16+ / InnoDB. 기존 V1~V6를 수정하지 않고 V7만 추가한다. 운영 데이터에서는 사전 백업 및 복제 DB 리허설 후 적용한다.

1. 현재 Flyway 버전이 6이고 실패한 migration이 없는지 확인한다. V6의 policy_snapshots 크기와 ALTER 잠금 시간을 복제 DB에서 측정한다.
2. 프로젝트/환경별 legacy budget 중복, 프로젝트 조직 불일치, 미등록 환경을 `preflight.sql`로 조사한다. UNKNOWN 과거 감사 주체를 현재 사용자로 백필하지 않는다.
3. `organization_members`에서 실제 고객 관리자/조회자를 확인한다. OWNER/ADMIN은 쓰기, OPERATOR/VIEWER는 읽기다. 멤버십이 없는 사용자를 일괄 관리자 승격하지 않는다.
4. V7 적용: customer_policies 및 policy_request_contexts 생성, 기존 policy_snapshots에 nullable 출처/이력 컬럼과 FK/UNIQUE/CHECK 추가. 기존 snapshot ID와 usage FK를 보존한다. 환경/request ID는 대소문자를 구분한다.
5. 새 서버 배포 후 소수의 검증된 프로젝트/환경에 `import-monthly-budget` 호출. 기존 알림 임계값을 soft limit으로 추정하지 않는다. 응답 policyId/version/snapshotId/actorId와 legacy projection(policy_version)을 확인한다.
6. 새 API 수정/이력/rollback과 v0의 전환 범위 쓰기 409를 점검한다. prod를 이관했다면 PROD/PrOd 요청도 409인지 확인한다. 전체 서비스 쓰기 배포가 끝나기 전에 이관하면 구버전 인스턴스가 audit 없이 monthly_budget_settings를 변경할 수 있으므로 **구버전 writer를 먼저 배제**한다.
7. #15 연동 시 request context를 기존 snapshot 참조로 저장하고, 원본 입력 fingerprint/월 누적값/알고리즘 버전은 해당 판정 감사에 함께 남긴다.

## 애플리케이션 rollback

- 정책 내용 오류는 rollback API로 이전 revision을 복사한다. 정책 이력/요청 context를 DELETE하거나 UPDATE하지 않는다.
- 코드 배포 회귀는 관리 변경을 중지하고 직전 호환 버전으로 롤백한다. V7 테이블/컬럼은 **유지**한다. V6-only 서버를 내릴 경우 v0 PUT /api/budgets를 ingress에서 중지해 관리된 projection의 무감사 변경을 막는다. 이미 잡힌 context는 계속 보존한다.
- V7에는 자동 down migration이 없다. 미래에 제거하려면 참조/보존 의무·백업을 검토한 별도 migration이 필요하다. Flyway history 행만 지워 재실행하지 않는다. MySQL DDL은 전체 rollback이 안 되므로 실패 시 적용된 object를 조사하고 복제본에서 repair 절차를 검증한다.
- 런타임 DB 사용자에게 policy_snapshots/policy_request_contexts의 INSERT/SELECT만 허용하고 migration 계정을 분리한다. JPA 모델도 immutable이며 애플리케이션 repository는 update/delete를 노출하지 않는다.

## 반복 가능한 검증

기본: `./gradlew test` (H2, 전체 회귀 테스트).

추가: **버려도 되는 빈 MySQL schema**에서 아래 실행. 테스트는 fixture 테이블 데이터를 정리하므로 운영/공용 DB를 사용하면 안 된다.

```sh
POLICY_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:33214/policy_issue14?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC' \
POLICY_TEST_MYSQL_USER=root \
./gradlew test --tests '*PolicyMySqlIntegrationTest'
```

비밀번호가 필요하면 POLICY_TEST_MYSQL_PASSWORD 환경변수로 전달한다. 해당 URL이 없으면 MySQL suite는 skip한다. Flyway V1~V7를 사용하며 pool 기본값은 REPEATABLE READ, 정책/예산 쓰기와 capture의 트랜잭션은 READ COMMITTED다. 초기 생성/동시 갱신·불변 이력·가격 연결·HTTP 권한과 함께 대소문자 예산 우회, 이미 로드된 프로젝트의 보관 처리, 서로 다른 프로젝트의 동시 최초 생성을 검증한다. 동시 생성 테스트는 양쪽의 실제 head/예산 조회를 동기화해 존재하지 않는 행을 모두 조회한 후 INSERT하도록 만든다. ddl-auto는 validate로 실행해 모든 엔티티 매핑이 실제 MySQL 스키마와 일치하는지도 함께 확인한다.
