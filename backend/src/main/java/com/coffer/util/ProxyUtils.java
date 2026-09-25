package com.coffer.util;

import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;

/**
 * Spring AOP 代理解包工具。
 *
 * <p>标注 {@code @LogModelCall} 的 Bean（如 {@code TagGenerationTool}）会被
 * {@code ModelCallLogger} 切面代理为 CGLIB 子类，LangChain4j 的 {@code @Tool}
 * 扫描基于代理类的反射方法，无法看到被覆盖方法上的注解，导致工具注册为空。
 * 在将工具实例交给 AiServices / ToolSpecifications 之前调用本工具解包到真实目标。
 */
public final class ProxyUtils {

    private ProxyUtils() {
    }

    /**
     * 若给定 Bean 是 AOP 代理，返回其真实目标实例；否则原样返回。
     *
     * @param bean Spring 管理的 Bean
     * @return 解包后的真实目标实例
     */
    public static Object unwrap(Object bean) {
        if (bean != null && AopUtils.isAopProxy(bean)) {
            Object target = AopProxyUtils.getSingletonTarget(bean);
            if (target != null) {
                return target;
            }
        }
        return bean;
    }
}
