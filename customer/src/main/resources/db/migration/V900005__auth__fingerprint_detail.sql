-- 指纹绑定表增加原始指纹和产品版本字段，便于应用端信息展示
ALTER TABLE auth.t_fingerprint_binding
    ADD COLUMN IF NOT EXISTS fingerprint TEXT,
    ADD COLUMN IF NOT EXISTS product_version VARCHAR(64);

COMMENT ON COLUMN auth.t_fingerprint_binding.fingerprint IS '原始设备指纹（激活时上报，用于展示）';
COMMENT ON COLUMN auth.t_fingerprint_binding.product_version IS '客户端产品版本（激活时上报）';
