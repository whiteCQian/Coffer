package com.coffer.config;

import dev.langchain4j.data.message.SystemMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI Agent 相关配置。
 *
 * <p>提供系统提示词 {@link SystemMessage} Bean，内容可通过
 * {@code coffer.system-prompt} 配置项覆盖，便于调整 Agent 角色设定。
 */
@Slf4j
@Configuration
public class AiAgentConfig {

    /** 系统提示词，默认值见占位符，可在 application.yml 中通过 coffer.system-prompt 覆盖。 */
    @Value("${coffer.system-prompt:你是 Coffer 智能文件管家，擅长总结与分类}")
    private String systemPrompt;

    /**
     * 系统提示词 Bean，供 AiServices 构建 Agent 时使用。
     *
     * @return SystemMessage 系统提示词消息
     */
    @Bean
    public SystemMessage systemMessage() {
        log.debug("初始化 Agent 系统提示词，字符数={}", systemPrompt == null ? 0 : systemPrompt.length());
        return SystemMessage.from(systemPrompt);
    }
}
