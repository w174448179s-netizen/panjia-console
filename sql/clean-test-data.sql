-- ============================================================================
-- 清空测试数据脚本
-- ============================================================================
-- 用途：测试阶段清空所有业务数据，保留表结构和 Flyway 迁移记录。
--
-- 保留的表：
--   - flyway_schema_history （Flyway 元数据，绝对不能删）
--   - auth.t_key_version     （JWT 签名密钥版本，删除后需重新生成密钥对）
--
-- 执行方式（推荐用封装脚本自动检测环境）：
--   sh bin/clean-test-data.sh
--
-- 或手动执行：
--   本地 psql：
--     psql -U <user> -d <db> -f sql/clean-test-data.sql
--
--   Docker：
--     docker exec -i <postgres容器名> psql -U <user> -d <db> < sql/clean-test-data.sql
--
-- 危险操作！执行前请确认：
--   1. 这是测试环境，不是生产环境
--   2. 已备份重要数据（如需要）
-- ============================================================================

BEGIN;

-- ---- auth schema：清空所有业务表（CASCADE 处理外键，RESTART IDENTITY 重置自增序列）----
TRUNCATE TABLE
    auth.t_alert_record,
    auth.t_multi_instance_pending,
    auth.t_blacklist,
    auth.t_test_code,
    auth.t_heartbeat_archive,
    auth.t_heartbeat_record,
    auth.t_fingerprint_binding,
    auth.t_license_content,
    auth.t_auth_code
RESTART IDENTITY CASCADE;

-- ---- customer schema：清空所有业务表 ----
TRUNCATE TABLE
    customer.pj_ops_log,
    customer.pj_backup_record,
    customer.pj_upgrade_record,
    customer.pj_product_version,
    customer.pj_blacklist_view,
    customer.pj_alert,
    customer.pj_auth_issue_record,
    customer.pj_customer
RESTART IDENTITY CASCADE;

COMMIT;

-- ============================================================================
-- 执行完成
-- ============================================================================
-- 数据已清空，自增序列已重置为 1。
-- 密钥版本表（auth.t_key_version）保留，JWT 签名验证不受影响。
--
-- 如需连密钥版本一起清空（完全重置），取消下面这行的注释后单独执行：
--   TRUNCATE TABLE auth.t_key_version RESTART IDENTITY CASCADE;
-- 清空后需重新初始化密钥（应用启动时会自动检测并创建 V1 密钥，或手动执行 bin/gen_keypair.sh）。
