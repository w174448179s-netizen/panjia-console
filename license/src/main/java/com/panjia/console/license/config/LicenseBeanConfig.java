package com.panjia.console.license.config;

import com.panjia.console.common.util.ClientIpResolver;
import com.panjia.console.license.security.JwtConfigProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * License 模块 Bean 配置
 * <p>
 * ★ H2 安全修复：注册 ClientIpResolver Bean，从 JwtConfigProperties 读取可信代理 CIDR。
 * ★ 自调用事务修复：注册 TransactionTemplate Bean，供 ActivateService 编程式事务管理。
 */
@Configuration
public class LicenseBeanConfig {

    @Bean
    public ClientIpResolver clientIpResolver(JwtConfigProperties config) {
        return new ClientIpResolver(config.getTrustedProxyCidrs());
    }

    /**
     * ★ 自调用事务修复：编程式事务模板
     * <p>
     * ActivateService.activate() 调用 this.doActivate()，Spring AOP 代理不拦截自调用，
     * 导致 doActivate 上的 @Transactional 失效，SELECT ... FOR UPDATE 在无事务下运行，
     * 行锁提交后立即释放，并发控制形同虚设。
     * <p>
     * 用 TransactionTemplate 显式包裹 doActivate 调用，事务边界清晰，
     * 且不需要自注入或拆分类。
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}

