-- 心跳记录表：放宽 NOT NULL 约束
-- 原因：JWT 验签失败时 auth_code_id / customer_no / fp_hash 可能为空，
--       但仍需记录心跳用于诊断。reported_at 缺省用服务端接收时间。

ALTER TABLE auth.t_heartbeat_record ALTER COLUMN auth_code_id DROP NOT NULL;
ALTER TABLE auth.t_heartbeat_record ALTER COLUMN customer_no DROP NOT NULL;
ALTER TABLE auth.t_heartbeat_record ALTER COLUMN reported_at DROP NOT NULL;
ALTER TABLE auth.t_heartbeat_record ALTER COLUMN reported_at SET DEFAULT now();

COMMENT ON COLUMN auth.t_heartbeat_record.auth_code_id IS '授权码ID（JWT验签失败时可能为空）';
COMMENT ON COLUMN auth.t_heartbeat_record.customer_no IS '客户编号（JWT验签失败时可能为空）';
