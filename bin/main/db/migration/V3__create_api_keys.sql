CREATE TABLE api_keys (
    id char(36) primary key,
    api_key VARCHAR(255) NOT NULL UNIQUE,
    member_id char(36) NOT NULL,
    name VARCHAR(255),
    created_at datetime(6) not null default current_timestamp(6),
    last_used_at datetime(6) null,
    is_active BOOLEAN DEFAULT TRUE,
    CONSTRAINT fk_api_keys_member FOREIGN KEY (member_id) REFERENCES users(id)
) engine=InnoDB;

CREATE INDEX idx_api_keys_member ON api_keys (member_id);
CREATE INDEX idx_api_keys_value ON api_keys (api_key);
