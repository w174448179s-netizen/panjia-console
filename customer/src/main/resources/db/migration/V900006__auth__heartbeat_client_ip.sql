-- V900006: 心跳记录增加 client_ip 字段，用于 IP 多实例检测
-- 同一授权码在短时间内出现不同 IP → 判定为同时在线 → 拉黑

ALTER TABLE auth.t_heartbeat_record
    ADD COLUMN IF NOT EXISTS client_ip VARCHAR(64);

ALTER TABLE auth.t_heartbeat_archive
    ADD COLUMN IF NOT EXISTS client_ip VARCHAR(64);

-- 按授权码 + IP 查询的索引，用于多实例 IP 检测
CREATE INDEX IF NOT EXISTS idx_heartbeat_auth_code_ip_received
    ON auth.t_heartbeat_record(auth_code_id, client_ip, received_at DESC);

COMMENT ON COLUMN auth.t_heartbeat_record.client_ip IS '客户端 IP（用于 IP 多实例检测）';
