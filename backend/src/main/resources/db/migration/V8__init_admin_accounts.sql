-- Administrator identities and revocable JWT state / 管理员身份与可撤销 JWT 状态

CREATE TABLE admin_accounts (
    id                       BIGSERIAL PRIMARY KEY,
    username                 VARCHAR(64)  NOT NULL,
    password_hash            VARCHAR(255) NOT NULL,
    role_code                VARCHAR(32)  NOT NULL DEFAULT 'ADMIN',
    account_status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    failed_login_count       INTEGER      NOT NULL DEFAULT 0,
    locked_until             TIMESTAMPTZ,
    token_version            BIGINT       NOT NULL DEFAULT 0,
    last_login_at            TIMESTAMPTZ,
    password_updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_admin_accounts_username CHECK (
        username = LOWER(BTRIM(username))
        AND CHAR_LENGTH(username) BETWEEN 3 AND 64
        AND username ~ '^[a-z0-9._-]+$'
    ),
    CONSTRAINT ck_admin_accounts_password_hash CHECK (
        BTRIM(password_hash) <> ''
        AND CHAR_LENGTH(password_hash) >= 60
    ),
    CONSTRAINT ck_admin_accounts_role CHECK (role_code IN ('ADMIN')),
    CONSTRAINT ck_admin_accounts_status CHECK (account_status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_admin_accounts_failed_login_count CHECK (failed_login_count >= 0),
    CONSTRAINT ck_admin_accounts_token_version CHECK (token_version >= 0)
);

CREATE UNIQUE INDEX uk_admin_accounts_username_ci
    ON admin_accounts (LOWER(username));

CREATE INDEX idx_admin_accounts_status
    ON admin_accounts (account_status);

COMMENT ON TABLE admin_accounts IS 'Administrator identities for protected operations / 受保护操作的管理员身份';
COMMENT ON COLUMN admin_accounts.username IS 'Normalized lowercase login name / 规范化小写登录名';
COMMENT ON COLUMN admin_accounts.password_hash IS 'Adaptive password hash, never plaintext / 自适应密码哈希，禁止明文';
COMMENT ON COLUMN admin_accounts.role_code IS 'Authorization role code / 授权角色编码';
COMMENT ON COLUMN admin_accounts.account_status IS 'Administrative account lifecycle status / 管理员账号生命周期状态';
COMMENT ON COLUMN admin_accounts.failed_login_count IS 'Consecutive failed login attempts / 连续登录失败次数';
COMMENT ON COLUMN admin_accounts.locked_until IS 'Temporary login lock expiration / 临时登录锁定截止时间';
COMMENT ON COLUMN admin_accounts.token_version IS 'Version checked by every JWT for immediate revocation / 每个 JWT 校验的即时撤销版本';
COMMENT ON COLUMN admin_accounts.last_login_at IS 'Latest successful login time / 最近成功登录时间';
COMMENT ON COLUMN admin_accounts.password_updated_at IS 'Latest password hash update time / 最近密码哈希更新时间';
