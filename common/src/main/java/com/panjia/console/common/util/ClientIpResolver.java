package com.panjia.console.common.util;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 客户端真实 IP 解析器
 * <p>
 * ★ H2 安全修复：从右向左遍历 X-Forwarded-For，跳过可信代理 IP，
 * 取第一个非可信代理的 IP 作为真实客户端 IP。
 * <p>
 * 旧实现直接取 X-Forwarded-For 的第一个 IP（最左侧），
 * 攻击者只需在请求中伪造该头部即可绕过 IP 多实例检测。
 * <p>
 * 安全模型：
 * <ul>
 *   <li>可信代理（nginx）会将真实客户端 IP 追加到 X-Forwarded-For 末尾</li>
 *   <li>X-Forwarded-For: client, proxy1, proxy2 → 从右向左数，跳过可信代理</li>
 *   <li>第一个非可信代理的 IP 即为真实客户端 IP</li>
 *   <li>如果所有 IP 都是可信代理，说明请求来自可信代理自身（如健康检查），返回 remoteAddr</li>
 * </ul>
 */
@Slf4j
public class ClientIpResolver {

    private final List<String> trustedProxyCidrs;

    public ClientIpResolver(List<String> trustedProxyCidrs) {
        this.trustedProxyCidrs = trustedProxyCidrs != null ? trustedProxyCidrs : List.of();
    }

    /**
     * 解析客户端真实 IP
     *
     * @param req HTTP 请求
     * @return 客户端真实 IP
     */
    public String resolve(HttpServletRequest req) {
        String remoteAddr = req.getRemoteAddr();

        String xff = req.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) {
            // 无 X-Forwarded-For 头，可能是直连（非经过代理）
            // 如果 remoteAddr 是可信代理 IP，说明请求确实来自代理但代理未设置该头（异常配置）
            if (isTrustedProxy(remoteAddr)) {
                log.debug("[ClientIpResolver] X-Forwarded-For 缺失但 remoteAddr 是可信代理，可能 nginx 未设置该头");
            }
            return normalize(remoteAddr);
        }

        String[] ips = xff.split(",");
        // 从右向左遍历，跳过可信代理 IP
        for (int i = ips.length - 1; i >= 0; i--) {
            String candidate = ips[i].trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (!isTrustedProxy(candidate)) {
                // 第一个非可信代理的 IP → 真实客户端
                return normalize(candidate);
            }
        }

        // 所有 X-Forwarded-For 中的 IP 都是可信代理
        // 说明请求链路全部经过可信代理，取 remoteAddr（最后一个直接连接者）
        return normalize(remoteAddr);
    }

    /**
     * 判断指定 IP 是否属于可信代理范围
     */
    private boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String normalized = normalize(ip);
        for (String cidr : trustedProxyCidrs) {
            if (matchesCidr(normalized, cidr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断 IP 是否匹配 CIDR 范围
     * 支持 IPv4 单 IP 和 CIDR 格式（如 127.0.0.1 或 172.16.0.0/12）
     */
    private boolean matchesCidr(String ip, String cidr) {
        if (cidr == null || cidr.isBlank()) {
            return false;
        }
        // 不含 / → 精确匹配单 IP
        if (!cidr.contains("/")) {
            return ip.equals(cidr);
        }
        try {
            String[] parts = cidr.split("/");
            String network = parts[0];
            int prefix = Integer.parseInt(parts[1]);

            byte[] ipBytes = InetAddress.getByName(ip).getAddress();
            byte[] networkBytes = InetAddress.getByName(network).getAddress();

            // IPv4 和 IPv6 长度不同，长度不一致直接不匹配
            if (ipBytes.length != networkBytes.length) {
                return false;
            }

            // 逐位比较前 prefix 位
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (ipBytes[i] != networkBytes[i]) {
                    return false;
                }
            }
            if (remainingBits > 0 && fullBytes < ipBytes.length) {
                int mask = 0xFF << (8 - remainingBits);
                if ((ipBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            log.warn("[ClientIpResolver] CIDR 解析失败: ip={}, cidr={}", ip, cidr);
            return false;
        }
    }

    /**
     * 规范化 IP 地址（去除 IPv6 映射前缀）
     * 如 ::ffff:127.0.0.1 → 127.0.0.1
     */
    private String normalize(String ip) {
        if (ip == null) {
            return "unknown";
        }
        // 处理 IPv6 映射的 IPv4 地址（如 ::ffff:192.168.1.1）
        if (ip.startsWith("::ffff:")) {
            return ip.substring(7);
        }
        return ip;
    }
}
