package com.coffer.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / springdoc-openapi 接口文档配置。
 *
 * <p>提供 OpenAPI 文档元信息；文档 UI 地址为 {@code /doc.html}（knife4j-openapi3 自动配置），
 * 原生 swagger-ui 地址为 {@code /swagger-ui/index.html}。
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI cofferOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Coffer 智能文件管理系统 API")
                        .description("基于大模型 Agent 的智能文件存储管理系统后端接口文档")
                        .version("v1.0.0"));
    }
}
