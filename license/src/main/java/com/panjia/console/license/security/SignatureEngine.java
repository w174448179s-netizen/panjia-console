package com.panjia.console.license.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.PublicKey;

/**
 * 签名引擎
 * <p>
 * 封装 JWT 签发和验签的统一入口，向上层 Service 提供简洁的 API。
 * <p>
 * 设计目的：隔离 JJWT 库的具体实现，便于未来替换或扩展。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignatureEngine {

    private final JwtIssuer jwtIssuer;
    private final JwtVerifier jwtVerifier;

    /**
     * 签发 JWT
     *
     * @param claims 载荷
     * @return JWT 字符串
     */
    public String issueJwt(LicenseJwtClaims claims) {
        return jwtIssuer.issueToken(claims);
    }

    /**
     * 验签并解析 JWT
     *
     * @param token JWT 字符串
     * @return 解析后的 claims DTO
     */
    public LicenseJwtClaims verifyAndParse(String token) {
        PublicKey publicKey = jwtIssuer.getPublicKey();
        var jws = jwtVerifier.verify(token, publicKey);
        return jwtIssuer.parseClaims(jws);
    }

    /**
     * 获取当前密钥版本
     */
    public int getCurrentKeyVersion() {
        return jwtIssuer.getCurrentKeyVersion();
    }

    /**
     * 获取当前公钥
     */
    public PublicKey getPublicKey() {
        return jwtIssuer.getPublicKey();
    }
}
