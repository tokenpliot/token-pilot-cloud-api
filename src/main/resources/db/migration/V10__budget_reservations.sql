-- [이슈 #17] ENFORCE 예산 예약·정산 상태 머신 테이블.
-- 확장(expansion)만 수행한다: 새 테이블 세 개만 추가하고 기존 테이블은 건드리지 않는다.
-- 대상: MySQL >= 8.0.16, InnoDB. V8(판정 기록, #15)·V9(Shadow 감사, #16) 다음 번호다. main에 이미 V10이 있으면 번호를 올린다.
-- 환경·멱등성 키·requestId는 V7(#14)과 같이 utf8mb4_bin으로 비교한다. ("Prod"와 "prod"가 같은 범위로 섞이지 않게)

-- 예산 범위 하나의 합계. environment_key가 ''이면 프로젝트 전체 범위, 값이 있으면 그 환경 범위다.
-- (#14: 프로젝트 정책과 환경 정책이 둘 다 있으면 각각의 월 예산을 모두 만족해야 한다)
-- 예약·정산은 이 행을 SELECT ... FOR UPDATE로 잠근 뒤 바꾼다. 여러 서버가 동시에 예약해도 이 잠금에서 한 줄로 선다.
create table budget_reservation_counters (
    id char(36) primary key,
    organization_id char(36) not null,
    project_id char(36) not null,
    environment_key varchar(20) character set utf8mb4 collate utf8mb4_bin not null,
    budget_period char(7) not null,
    held_usd decimal(18,6) not null,
    committed_usd decimal(18,6) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    unique key uk_reservation_counter_scope (project_id, environment_key, budget_period),
    constraint fk_reservation_counter_project foreign key (project_id) references projects(id),
    constraint ck_reservation_counter_amounts check (held_usd >= 0 and committed_usd >= 0)
) engine=InnoDB;
-- held_usd: 아직 정산되지 않은 예약 합계(RESERVED, IN_FLIGHT, RECONCILIATION_REQUIRED). 결과를 모르는 예약도 0원으로 풀지 않는다.
-- committed_usd: 예약 경로로 정산된 비용 합계. 예약 없이 들어온 사용량(Observe 원장)은 여기에 없다.

create table budget_reservations (
    id char(36) primary key,
    organization_id char(36) not null,
    project_id char(36) not null,
    environment varchar(20) character set utf8mb4 collate utf8mb4_bin not null,
    budget_period char(7) not null,
    project_counter_id char(36) not null,
    environment_counter_id char(36) not null,
    idempotency_key varchar(128) character set utf8mb4 collate utf8mb4_bin not null,
    request_id varchar(128) character set utf8mb4 collate utf8mb4_bin not null,
    request_fingerprint char(64) not null,
    provider varchar(128) not null,
    model varchar(128) not null,
    estimated_prompt_tokens bigint null,
    estimated_completion_tokens bigint null,
    reserved_cost_usd decimal(18,6) not null,
    project_budget_limit_usd decimal(18,6) null,
    environment_budget_limit_usd decimal(18,6) null,
    currency char(3) not null,
    policy_request_context_id char(36) null,
    state varchar(30) not null,
    committed_cost_usd decimal(18,6) null,
    released_cost_usd decimal(18,6) null,
    expires_at datetime(6) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    unique key uk_reservation_idempotency (project_id, idempotency_key),
    index idx_reservation_expiry (state, expires_at),
    constraint fk_reservation_project_counter foreign key (project_counter_id) references budget_reservation_counters(id),
    constraint fk_reservation_environment_counter foreign key (environment_counter_id) references budget_reservation_counters(id),
    constraint fk_reservation_policy_context foreign key (policy_request_context_id) references policy_request_contexts(id),
    constraint ck_reservation_state check (state in
        ('RESERVED', 'IN_FLIGHT', 'RECONCILIATION_REQUIRED', 'COMMITTED', 'RELEASED', 'WRITTEN_OFF')),
    constraint ck_reservation_amounts check (reserved_cost_usd > 0
        and (project_budget_limit_usd is null or project_budget_limit_usd >= 0)
        and (environment_budget_limit_usd is null or environment_budget_limit_usd >= 0)
        and (committed_cost_usd is null or committed_cost_usd >= 0)
        and (released_cost_usd is null or released_cost_usd >= 0)),
    constraint ck_reservation_currency check (currency = 'USD')
) engine=InnoDB;
-- uk_reservation_idempotency: Idempotency-Key의 고유 범위는 (프로젝트, 엔드포인트)다 (control-plane-v1.yaml).
--   환경은 범위가 아니라 요청 본문이므로 지문에 넣는다. 같은 키로 다른 환경을 보내면 409다.
-- policy_request_context_id: 예약 시점의 프로젝트·환경 정책 스냅샷과 가격 스냅샷을 묶은 #14의 요청 context.
-- idx_reservation_expiry: 만료 예약 회수(후속 작업)가 정산되지 않은 행 중 expires_at이 지난 행을 찾을 때 쓴다.

-- 정산 요청 한 건. 같은 Idempotency-Key·같은 본문(occurredAt 포함)의 재전송은 이 행의 결과를 그대로 돌려준다.
create table budget_reservation_settlements (
    id char(36) primary key,
    reservation_id char(36) not null,
    organization_id char(36) not null,
    project_id char(36) not null,
    idempotency_key varchar(128) character set utf8mb4 collate utf8mb4_bin not null,
    request_fingerprint char(64) not null,
    action varchar(30) not null,
    previous_state varchar(30) not null,
    resulting_state varchar(30) not null,
    committed_cost_usd decimal(18,6) null,
    released_cost_usd decimal(18,6) null,
    occurred_at datetime(6) not null,
    created_at datetime(6) not null,
    unique key uk_reservation_settlement_idempotency (project_id, idempotency_key),
    index idx_reservation_settlement_reservation (reservation_id, created_at),
    constraint fk_reservation_settlement_reservation foreign key (reservation_id) references budget_reservations(id),
    constraint ck_reservation_settlement_action check (action in
        ('COMMIT', 'RELEASE', 'RECONCILIATION_REQUIRED', 'WRITE_OFF')),
    constraint ck_reservation_settlement_amounts check ((committed_cost_usd is null or committed_cost_usd >= 0)
        and (released_cost_usd is null or released_cost_usd >= 0))
) engine=InnoDB;
-- Append-only. 예약 하나에 정산 행이 여러 개일 수 있다 (예: RECONCILIATION_REQUIRED 다음에 늦게 온 COMMIT).
