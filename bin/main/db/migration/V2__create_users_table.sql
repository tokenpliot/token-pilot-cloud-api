create table users (
    id char(36) primary key,
    email varchar(255) not null,
    name varchar(100) not null,
    password_hash varchar(255) null,
    role varchar(30) not null,
    provider varchar(30) not null,
    provider_id varchar(100) null,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6) on update current_timestamp(6),
    unique key uk_users_email (email),
    unique key uk_users_provider_provider_id (provider, provider_id)
) engine=InnoDB;
