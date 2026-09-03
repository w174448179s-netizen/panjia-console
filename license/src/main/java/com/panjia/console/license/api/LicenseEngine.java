package com.panjia.console.license.api;

import com.panjia.console.license.api.dto.AlertSyncResult;
import com.panjia.console.license.api.dto.BlacklistView;
import com.panjia.console.license.api.dto.CreateLicenseRequest;
import com.panjia.console.license.api.dto.CreateLicenseResult;
import com.panjia.console.license.api.dto.HeartbeatSnapshot;
import com.panjia.console.license.api.dto.RestoreResult;

/**
 * 授权引擎接口 —— customer 调 license 的唯一入口
 * <p>
 * ★ 包边界铁律：customer 包只允许通过此接口调用 license 能力；
 * 禁止 customer 直接 import license 的 Service / Repository / 实体。
 * <p>
 * 这条规则是"以后好拆"的唯一前提，Code Review 必查。
 * 将来若拆成两个服务，把此接口实现换成 HTTP 调用即可，业务逻辑不改。
 */
public interface LicenseEngine {

    // ================= 签发 / 吊销 / 恢复 / 换机 =================

    /**
     * 签发授权（★ V1.2 改名，原 createAuthCode）
     * <p>
     * ★ F1 冻结规则：幂等 —— 同一 requestId 重复调用返回同一授权。
     *
     * @param req 签发请求（含 requestId 幂等键）
     * @return 签发结果（authCode + licenseId + licenseVersion）
     */
    CreateLicenseResult createLicense(CreateLicenseRequest req);

    /**
     * 吊销授权
     * <p>
     * 置 status=REVOKED + 写 t_blacklist(reason=REVOKE)
     *
     * @param authCode 授权码
     * @param reason   吊销原因
     */
    void revoke(String authCode, String reason);

    /**
     * 恢复授权（旧 JWT 失效）
     * <p>
     * 原子完成：status REVOKED→ACTIVE + 删除 REVOKE 黑名单 + 新增 license_content(+1)
     *
     * @param authCode 授权码
     * @param reason   恢复原因
     * @return 恢复结果（含新版本号）
     */
    RestoreResult restore(String authCode, String reason);

    /**
     * 换机（使当前指纹失效，授权进入 REBINDING）
     * <p>
     * ★ F2 冻结规则：内含 FOR UPDATE，锁顺序与 activate 一致。
     *
     * @param authCode 授权码
     */
    void invalidateFingerprint(String authCode);

    /**
     * ★ V1.4：取消换机（运营手动退出 REBINDING）
     * <p>
     * 客户放弃换机时，把授权从 REBINDING 切回 ACTIVE。
     * <p>
     * 前置：status == REBINDING，否则抛 IllegalStateException → 400
     * <p>
     * 指纹分支（互斥，先锁 auth_code 再锁 fingerprint）：
     * a) 存在 ACTIVE 绑定 → 保留不动
     * b) 无 ACTIVE 但有 INVALIDATED → 恢复最近一条 INVALIDATED 为 ACTIVE
     * c) 均无（异常态）→ 告警 T3_INTEGRITY + 抛异常
     * <p>
     * 并清理该 authCode 的 t_multi_instance_pending；不动 t_blacklist / license_content
     *
     * @param authCode 授权码
     * @param reason   取消原因
     */
    void cancelRebinding(String authCode, String reason);

    // ================= 黑名单 / 测试码 =================

    /**
     * 从黑名单移除
     * <p>
     * 仅 MULTI_INSTANCE / MANUAL 可移除；
     * REVOKE 原因不可通过此方法移除，必须走 restore。
     *
     * @param authCode 授权码
     */
    void removeFromBlacklist(String authCode);

    /**
     * 测试码失效
     *
     * @param testCode 测试码
     */
    void expireTestCode(String testCode);

    // ================= 看板查询 =================

    /**
     * 同步告警（ID cursor 分页）
     * <p>
     * customer 模块以 ID cursor 拉取告警，(source, source_id) 唯一约束幂等。
     *
     * @param afterId 上次同步的最后一条 ID（从 0 开始）
     * @param limit   本次拉取条数
     * @return 同步结果
     */
    AlertSyncResult syncAlerts(long afterId, int limit);

    /**
     * 获取指定客户的心跳快照
     *
     * @param customerNo 客户编号
     * @return 心跳快照（在线状态等）
     */
    HeartbeatSnapshot getHeartbeatSnapshot(String customerNo);

    /**
     * 获取指定客户的黑名单视图
     *
     * @param customerNo 客户编号
     * @return 黑名单视图（不在黑名单中返回 null）
     */
    BlacklistView getBlacklist(String customerNo);
}
