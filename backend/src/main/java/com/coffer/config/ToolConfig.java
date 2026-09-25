package com.coffer.config;

import com.coffer.tool.FileParsingTool;
import com.coffer.tool.FileSearchTool;
import com.coffer.tool.TagGenerationTool;
import com.coffer.util.ProxyUtils;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Agent 工具注册配置：集中管理所有 {@code @Tool} 工具实例，
 * 供 AiServices 装配与工具规格提取使用。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ToolConfig {

    private final FileParsingTool fileParsingTool;
    private final TagGenerationTool tagGenerationTool;
    private final FileSearchTool fileSearchTool;

    /**
     * 注册全部 Agent 工具实例（集中注册器），可直接传给
     * {@code AiServices.tools(Collection)} 完成工具装配。
     *
     * @return 工具实例列表
     */
    @Bean
    public List<Object> toolInstances() {
        List<Object> toolInstances = List.of(fileParsingTool, tagGenerationTool, fileSearchTool);
        List<String> names = toolInstances.stream()
                .map(tool -> tool.getClass().getSimpleName())
                .toList();
        log.info("已注册 {} 个 Agent 工具: {}", toolInstances.size(), names);
        return toolInstances;
    }

    /**
     * 从工具实例自动扫描 {@code @Tool} 注解并提取工具规格描述。
     *
     * <p>1.18.1 无 {@code toolSpecificationsFrom(List)} 重载，直接传 List 会命中
     * {@code (Object)} 重载导致空结果；故逐个实例提取后扁平合并。
     * 实例经 {@link ProxyUtils#unwrap} 解包 AOP 代理，避免 CGLIB 子类丢失 {@code @Tool} 注解。
     *
     * @param toolInstances 已注册的工具实例列表
     * @return 工具规格列表
     */
    @Bean
    public List<ToolSpecification> toolSpecifications(List<Object> toolInstances) {
        return toolInstances.stream()
                .flatMap(tool -> ToolSpecifications.toolSpecificationsFrom(ProxyUtils.unwrap(tool)).stream())
                .toList();
    }
}
