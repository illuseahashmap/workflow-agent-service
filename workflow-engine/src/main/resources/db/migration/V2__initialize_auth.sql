CREATE TABLE IF NOT EXISTS auth_user (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    username VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    tenant_code VARCHAR(64) NOT NULL,
    enabled SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_auth_user_id UNIQUE (user_id),
    CONSTRAINT uk_auth_user_username UNIQUE (username)
);

CREATE INDEX IF NOT EXISTS idx_auth_user_tenant
    ON auth_user (tenant_code);

CREATE TABLE IF NOT EXISTS auth_role (
    id BIGSERIAL PRIMARY KEY,
    role_code VARCHAR(64) NOT NULL,
    role_name VARCHAR(128) NOT NULL,
    tenant_code VARCHAR(64) NOT NULL,
    description VARCHAR(512),
    enabled SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_auth_role_code_tenant UNIQUE (tenant_code, role_code)
);

CREATE TABLE IF NOT EXISTS auth_user_role (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    tenant_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_auth_user_role UNIQUE (user_id, tenant_code, role_code)
);

CREATE TABLE IF NOT EXISTS auth_menu (
    id BIGSERIAL PRIMARY KEY,
    menu_code VARCHAR(64) NOT NULL,
    parent_code VARCHAR(64),
    menu_name VARCHAR(128) NOT NULL,
    path VARCHAR(256),
    component VARCHAR(256),
    permission_code VARCHAR(128),
    sort_order INTEGER NOT NULL DEFAULT 0,
    enabled SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_auth_menu_code UNIQUE (menu_code)
);

CREATE TABLE IF NOT EXISTS auth_role_permission (
    id BIGSERIAL PRIMARY KEY,
    role_code VARCHAR(64) NOT NULL,
    tenant_code VARCHAR(64) NOT NULL,
    permission_code VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_auth_role_permission UNIQUE (tenant_code, role_code, permission_code)
);

INSERT INTO auth_role (tenant_code, role_code, role_name, description)
VALUES ('default', 'ADMIN', 'Administrator', 'Default administrator role')
ON CONFLICT (tenant_code, role_code) DO NOTHING;
