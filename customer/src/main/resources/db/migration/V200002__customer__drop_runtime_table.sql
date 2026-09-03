-- 删除运行时快照表（冗余，直接从心跳流水表查询即可）
DROP TABLE IF EXISTS customer.pj_customer_runtime;
