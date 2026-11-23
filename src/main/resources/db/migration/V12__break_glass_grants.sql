-- [이슈 #19] break-glass grant 테이블.
-- 고객 운영자가 한 프로젝트·환경의 Enforce를 만료 시간이 있는 범위에서 우회할 때 발급하는 권한과 그 감사 기록이다.
-- 확장(expansion)만 수행한다: 새 테이블만 추가하고 기존 테이블은 건드리지 않는다.
-- 번호 주의: #14(PR #30)가 V7을 쓰고 있다. 머지 직전에 main의 최신 번호를 확인하고, 겹치면 다음 번호로 바꾼다.
--           한 번이라도 공용 DB에 적용된 뒤에는 파일 이름을 바꾸지 말고 새 마이그레이션을 추가한다.
-- 대상: MySQL >= 8.0.16, InnoDB. 개발용 H2에서도 실행된다.

create table break_glass_grants (
    id char(36) primary key,
    organization_id char(36) not null,
    project_id char(36) not null,
    environment varchar(20) not null,
    reason varchar(500) not null,
    issued_by varchar(128) not null,
    issued_at datetime(6) not null,
    expires_at datetime(6) not null,
    revoked_at datetime(6) null,
    revoked_by varchar(128) null,
    revoke_reason varchar(500) null,
    -- 유효한 grant만 '조직:프로젝트:환경' 값을 갖고, 취소·만료 정리 후에는 NULL이 된다.
    -- UNIQUE는 NULL을 여러 개 허용하므로 범위당 유효한 grant가 DB 수준에서 하나로 보장된다.
    active_scope_key varchar(100) null,
    version bigint not null default 0,
    created_at datetime(6) not null default current_timestamp(6),
    unique key uk_break_glass_active_scope (active_scope_key),
    constraint ck_break_glass_expiry check (expires_at > issued_at)
) engine=InnoDB;

create index idx_break_glass_scope_issued on break_glass_grants (organization_id, project_id, environment, issued_at);
