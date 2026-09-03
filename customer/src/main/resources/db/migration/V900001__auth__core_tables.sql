-- ================= auth schema 初始化 =================

CREATE SCHEMA IF NOT EXISTS auth;

-- ================= 授权码表（授权状态权威） =================
CREATE TABLE auth.t_auth_code (
    id                      BIGSERIAL PRIMARY KEY,
    auth_code               VARCHAR(64)  NOT NULL UNIQUE,
    customer_no             VARCHAR(64)  NOT NULL,
    license_type            VARCHAR(16)  NOT NULL,
    version                 VARCHAR(32),
    max_stores              INT,
    max_users               INT,
    capabilities            jsonb,
    start_date              DATE,
    end_date                DATE,
    maintenance_end_date    DATE,
    min_supported_version   VARCHAR(32),
    max_supported_version   VARCHAR(32),
    offline_expire_at       TIMESTAMPTZ,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    is_test                 BOOLEAN NOT NULL DEFAULT FALSE,
    request_id              VARCHAR(64)  NOT NULL UNIQUE,
    issued_by               VARCHAR(64),
    issued_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at              TIMESTAMPTZ,
    revoked_reason          VARCHAR(256),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (customer_no, auth_code)
);
CREATE INDEX idx_auth_code_status ON auth.t_auth_code(status);
CREATE INDEX idx_auth_code_customer_no ON auth.t_auth_code(customer_no);

COMMENT ON TABLE auth.t_auth_code IS '授权码（授权状态权威）';
COMMENT ON COLUMN auth.t_auth_code.status IS 'ACTIVE/REBINDING/EXPIRED/REVOKED';
COMMENT ON COLUMN auth.t_auth_code.license_type IS 'PRODUCTION/TRIAL/TEST';
COMMENT ON COLUMN auth.t_auth_code.request_id IS '签发幂等键（前端生成UUID）';

-- ================= 指纹绑定表（唯一指纹权威） =================
CREATE TABLE auth.t_fingerprint_binding (
    id              BIGSERIAL PRIMARY KEY,
    auth_code_id    BIGINT       NOT NULL REFERENCES auth.t_auth_code(id) ON DELETE CASCADE,
    fp_hash         VARCHAR(128) NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    bound_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    invalidated_at  TIMESTAMPTZ,
    invalidate_reason VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (auth_code_id, fp_hash)
);

-- 每授权至多 1 条 ACTIVE（partial unique index）
CREATE UNIQUE INDEX uk_t_fingerprint_binding_active_auth_code
ON auth.t_fingerprint_binding(auth_code_id)
WHERE status = 'ACTIVE';

CREATE INDEX idx_fingerprint_fp_hash ON auth.t_fingerprint_binding(fp_hash);

COMMENT ON TABLE auth.t_fingerprint_binding IS '指纹绑定（唯一指纹权威）';
COMMENT ON COLUMN auth.t_fingerprint_binding.status IS 'ACTIVE/INVALIDATED';

-- ================= License 载荷表（当前+历史） =================
CREATE TABLE auth.t_license_content (
    id                      BIGSERIAL PRIMARY KEY,
    auth_code_id            BIGINT       NOT NULL REFERENCES auth.t_auth_code(id) ON DELETE CASCADE,
    license_version         INT          NOT NULL,
    version                 VARCHAR(32),
    max_stores              INT,
    max_users               INT,
    capabilities            jsonb,
    start_date              DATE,
    end_date                DATE,
    maintenance_end_date    DATE,
    min_supported_version   VARCHAR(32),
    max_supported_version   VARCHAR(32),
    key_version             INT,
    signature               TEXT,
    is_current              BOOLEAN NOT NULL DEFAULT TRUE,
    effective_at            TIMESTAMPTZ,
    expired_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (auth_code_id, license_version)
);

-- 每授权至多 1 条 is_current=TRUE（partial unique index）
CREATE UNIQUE INDEX uk_t_license_content_current_auth_code
ON auth.t_license_content(auth_code_id)
WHERE is_current = TRUE;

COMMENT ON TABLE auth.t_license_content IS 'License 载荷（当前+历史版本）';
COMMENT ON COLUMN auth.t_license_content.license_version IS '签发/续期/恢复时+1';
COMMENT ON COLUMN auth.t_license_content.key_version IS '签发时使用的密钥版本';

-- ================= 心跳记录表 =================
CREATE TABLE auth.t_heartbeat_record (
    id            BIGSERIAL PRIMARY KEY,
    auth_code_id  BIGINT NOT NULL,
    customer_no   VARCHAR(64) NOT NULL,
    fp_hash       VARCHAR(128),
    instance_id   VARCHAR(128),
    reported_at   TIMESTAMPTZ NOT NULL,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    current_stores INT,
    current_users  INT,
    client_mode   VARCHAR(16),
    raw           jsonb
);

CREATE INDEX idx_heartbeat_customer_received_at
ON auth.t_heartbeat_record(customer_no, received_at DESC);
CREATE INDEX idx_heartbeat_received_at ON auth.t_heartbeat_record(received_at);
CREATE INDEX idx_heartbeat_auth_code_id ON auth.t_heartbeat_record(auth_code_id);

COMMENT ON TABLE auth.t_heartbeat_record IS '心跳记录（由license直接写，customer只读）';
COMMENT ON COLUMN auth.t_heartbeat_record.received_at IS '服务端接收时间（在线判定权威）';
COMMENT ON COLUMN auth.t_heartbeat_record.reported_at IS '客户端上报时间（仅诊断用）';

-- ================= 心跳历史归档表 =================
CREATE TABLE auth.t_heartbeat_archive (
    LIKE auth.t_heartbeat_record INCLUDING ALL
);
CREATE INDEX idx_heartbeat_archive_customer_time
ON auth.t_heartbeat_archive(customer_no, received_at DESC);

COMMENT ON TABLE auth.t_heartbeat_archive IS '心跳历史归档（超过180天的记录迁移至此）';
