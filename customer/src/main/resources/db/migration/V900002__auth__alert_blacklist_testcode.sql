-- ================= 黑名单表 =================
CREATE TABLE auth.t_blacklist (
    id          BIGSERIAL PRIMARY KEY,
    auth_code_id BIGINT NOT NULL UNIQUE REFERENCES auth.t_auth_code(id) ON DELETE CASCADE,
    reason      VARCHAR(32) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  VARCHAR(64)
);

CREATE INDEX idx_blacklist_reason ON auth.t_blacklist(reason);

COMMENT ON TABLE auth.t_blacklist IS '黑名单';
COMMENT ON COLUMN auth.t_blacklist.reason IS 'REVOKE/MANUAL/MULTI_INSTANCE';

-- ================= 多实例两阶段确认表 =================
CREATE TABLE auth.t_multi_instance_pending (
    id              BIGSERIAL PRIMARY KEY,
    auth_code_id    BIGINT       NOT NULL REFERENCES auth.t_auth_code(id) ON DELETE CASCADE,
    fp_hash         VARCHAR(128) NOT NULL,
    confirm_count   INT          NOT NULL DEFAULT 1,
    first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (auth_code_id, fp_hash)
);

CREATE INDEX idx_multi_instance_auth_code ON auth.t_multi_instance_pending(auth_code_id);
CREATE INDEX idx_multi_instance_created_at ON auth.t_multi_instance_pending(created_at);

COMMENT ON TABLE auth.t_multi_instance_pending IS '多实例两阶段确认（防误杀）';

-- ================= 告警记录表 =================
CREATE TABLE auth.t_alert_record (
    id           BIGSERIAL PRIMARY KEY,
    source       VARCHAR(32) NOT NULL DEFAULT 'LICENSE_SERVER',
    source_id    VARCHAR(128) NOT NULL,
    customer_no  VARCHAR(64),
    auth_code_id BIGINT,
    alert_type   VARCHAR(64),
    trigger      VARCHAR(32),
    severity     VARCHAR(16),
    title        VARCHAR(256),
    detail       jsonb,
    status       VARCHAR(16) DEFAULT 'OPEN',
    occurred_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source, source_id)
);

CREATE INDEX idx_alert_customer_created ON auth.t_alert_record(customer_no, created_at DESC);
CREATE INDEX idx_alert_status ON auth.t_alert_record(status);

COMMENT ON TABLE auth.t_alert_record IS '告警记录（供看板同步）';
COMMENT ON COLUMN auth.t_alert_record.trigger IS 'T1_AUTH_FAIL/T2_SERVER_REVOKED/T3_INTEGRITY/SERVER_DECISION';
COMMENT ON COLUMN auth.t_alert_record.severity IS 'INFO/WARN/ERROR/CRITICAL';

-- ================= 测试码表 =================
CREATE TABLE auth.t_test_code (
    id          BIGSERIAL PRIMARY KEY,
    test_code   VARCHAR(64)  NOT NULL UNIQUE,
    auth_code_id BIGINT      NOT NULL REFERENCES auth.t_auth_code(id) ON DELETE CASCADE,
    expire_at   TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE auth.t_test_code IS '测试码';

-- ================= 密钥版本表 =================
CREATE TABLE auth.t_key_version (
    id           BIGSERIAL PRIMARY KEY,
    key_version  INT         NOT NULL UNIQUE,
    public_key   TEXT        NOT NULL,
    is_current   BOOLEAN     NOT NULL DEFAULT TRUE,
    activated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- V1 只支持一个生产密钥版本，插入初始记录
INSERT INTO auth.t_key_version (key_version, public_key, is_current)
VALUES (1, 'PLACEHOLDER_REPLACE_WITH_REAL_PUBLIC_KEY', TRUE);

COMMENT ON TABLE auth.t_key_version IS '密钥版本（V1仅一条当前记录，不做轮换）';
