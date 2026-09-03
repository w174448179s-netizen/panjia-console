-- ================= customer schema 初始化 =================

CREATE SCHEMA IF NOT EXISTS customer;

-- ================= 客户档案表 =================
CREATE TABLE customer.pj_customer (
    id              BIGSERIAL PRIMARY KEY,
    customer_no     VARCHAR(64)  NOT NULL UNIQUE,
    customer_name   VARCHAR(128) NOT NULL,
    contact_person  VARCHAR(64),
    contact_phone   VARCHAR(32),
    instance_id     VARCHAR(64),
    current_version VARCHAR(32),
    remark          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE customer.pj_customer IS '客户档案';

-- ================= 签发流水表（投影，可重建） =================
CREATE TABLE customer.pj_auth_issue_record (
    id              BIGSERIAL PRIMARY KEY,
    license_id      VARCHAR(128) NOT NULL UNIQUE,
    customer_no     VARCHAR(64)  NOT NULL REFERENCES customer.pj_customer(customer_no),
    auth_code       VARCHAR(64)  NOT NULL,
    license_type    VARCHAR(16)  NOT NULL,
    version         VARCHAR(32),
    max_stores      INT,
    max_users       INT,
    capabilities    jsonb,
    start_date      DATE,
    end_date        DATE,
    maintenance_end_date DATE,
    operator        VARCHAR(64),
    issue_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (customer_no, auth_code)
);

CREATE INDEX idx_issue_record_customer ON customer.pj_auth_issue_record(customer_no, issue_at DESC);

COMMENT ON TABLE customer.pj_auth_issue_record IS '签发流水（投影，license是权威源，可整表重建）';

-- ================= 客户运行时快照表（看板） =================
CREATE TABLE customer.pj_customer_runtime (
    id                  BIGSERIAL PRIMARY KEY,
    customer_no         VARCHAR(64) NOT NULL UNIQUE REFERENCES customer.pj_customer(customer_no),
    instance_id         VARCHAR(64),
    last_heartbeat_at   TIMESTAMPTZ,
    online_status       VARCHAR(16) DEFAULT 'UNKNOWN',
    current_stores      INT,
    current_users       INT,
    client_mode         VARCHAR(16),
    last_check_at       TIMESTAMPTZ,
    verified            BOOLEAN DEFAULT TRUE,
    verify_error        VARCHAR(64),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE customer.pj_customer_runtime IS '客户运行时快照（看板，从心跳聚合刷新）';
COMMENT ON COLUMN customer.pj_customer_runtime.online_status IS 'ONLINE/OFFLINE/LOST/UNKNOWN';
COMMENT ON COLUMN customer.pj_customer_runtime.client_mode IS 'NORMAL/RESTRICT';

-- ================= 告警缓存表（看板，从auth同步） =================
CREATE TABLE customer.pj_alert (
    id          BIGSERIAL PRIMARY KEY,
    source      VARCHAR(32)  NOT NULL,
    source_id   VARCHAR(128) NOT NULL,
    customer_no VARCHAR(64),
    alert_type  VARCHAR(64),
    trigger     VARCHAR(32),
    severity    VARCHAR(16),
    title       VARCHAR(256),
    detail      jsonb,
    status      VARCHAR(16) DEFAULT 'OPEN',
    occurred_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source, source_id)
);

CREATE INDEX idx_pj_alert_customer_created ON customer.pj_alert(customer_no, created_at DESC);
CREATE INDEX idx_pj_alert_status ON customer.pj_alert(status);

COMMENT ON TABLE customer.pj_alert IS '告警缓存（看板，从auth同步，可重建）';

-- ================= 黑名单视图表（缓存） =================
CREATE TABLE customer.pj_blacklist_view (
    id          BIGSERIAL PRIMARY KEY,
    auth_code   VARCHAR(64) NOT NULL UNIQUE,
    customer_no VARCHAR(64),
    reason      VARCHAR(32) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    synced_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE customer.pj_blacklist_view IS '黑名单缓存（可重建，不参与实时放行判定）';

-- ================= 产品版本库 =================
CREATE TABLE customer.pj_product_version (
    id          BIGSERIAL PRIMARY KEY,
    version     VARCHAR(32)  NOT NULL UNIQUE,
    file_path   VARCHAR(256),
    file_size   BIGINT,
    sha256      VARCHAR(128),
    release_note TEXT,
    released_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE customer.pj_product_version IS '产品版本库';

-- ================= 升级记录表 =================
CREATE TABLE customer.pj_upgrade_record (
    id          BIGSERIAL PRIMARY KEY,
    from_version VARCHAR(32),
    to_version   VARCHAR(32)  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ,
    error_msg    TEXT,
    operator     VARCHAR(64),
    backup_id    BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_upgrade_status ON customer.pj_upgrade_record(status);
CREATE INDEX idx_upgrade_created ON customer.pj_upgrade_record(created_at DESC);

COMMENT ON TABLE customer.pj_upgrade_record IS '升级记录';
COMMENT ON COLUMN customer.pj_upgrade_record.status IS 'PENDING/IN_PROGRESS/SUCCESS/FAILED/ROLLBACK_SUCCESS';

-- ================= 备份记录表 =================
CREATE TABLE customer.pj_backup_record (
    id          BIGSERIAL PRIMARY KEY,
    file_path   VARCHAR(256) NOT NULL,
    file_name   VARCHAR(128) NOT NULL,
    file_size   BIGINT,
    sha256      VARCHAR(128),
    backup_type VARCHAR(16)  NOT NULL DEFAULT 'FULL',
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    started_at  TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    error_msg   TEXT,
    operator    VARCHAR(64),
    remark      VARCHAR(256),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_backup_created ON customer.pj_backup_record(created_at DESC);

COMMENT ON TABLE customer.pj_backup_record IS '备份记录';
COMMENT ON COLUMN customer.pj_backup_record.backup_type IS 'FULL/INCREMENTAL';
COMMENT ON COLUMN customer.pj_backup_record.status IS 'PENDING/IN_PROGRESS/SUCCESS/FAILED';

-- ================= 操作审计日志表（全系统唯一） =================
CREATE TABLE customer.pj_ops_log (
    id          BIGSERIAL PRIMARY KEY,
    operator    VARCHAR(64) NOT NULL,
    action      VARCHAR(64) NOT NULL,
    target_type VARCHAR(32),
    target_id   VARCHAR(128),
    params      jsonb,
    result      VARCHAR(16),
    ip          VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ops_log_created ON customer.pj_ops_log(created_at DESC);
CREATE INDEX idx_ops_log_action ON customer.pj_ops_log(action);
CREATE INDEX idx_ops_log_target ON customer.pj_ops_log(target_type, target_id);

COMMENT ON TABLE customer.pj_ops_log IS '操作审计日志（全系统唯一）';
COMMENT ON COLUMN customer.pj_ops_log.action IS 'ISSUE/REVOKE/RESTORE/REBIND/CANCEL_REBINDING/BLACKLIST_REMOVE/...';
COMMENT ON COLUMN customer.pj_ops_log.params IS 'input=调用入参(脱敏),before=变更前快照,after=变更后快照';
COMMENT ON COLUMN customer.pj_ops_log.result IS 'SUCCESS/FAILED';
