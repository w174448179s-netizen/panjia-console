-- 心跳记录增加受限原因字段，便于排查客户端 RESTRICT 模式
ALTER TABLE auth.t_heartbeat_record
    ADD COLUMN IF NOT EXISTS restrict_reason VARCHAR(64);

COMMENT ON COLUMN auth.t_heartbeat_record.restrict_reason IS '受限原因（LicenseErrorCode name），NORMAL 时为 NULL';
