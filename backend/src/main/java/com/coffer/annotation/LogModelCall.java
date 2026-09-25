package com.coffer.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 模型调用日志切点注解。
 *
 * <p>标注在需要记录 Token 消耗的方法上（通常为调用 DeepSeek 模型的方法），
 * 由 {@code ModelCallLogger} 切面统一拦截。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogModelCall {
}
