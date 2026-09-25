package com.coffer.agent;

import com.coffer.annotation.LogModelCall;
import com.coffer.memory.ChatMemoryProvider;
import com.coffer.model.provider.ChatProvider;
import com.coffer.model.runtime.ModeAwareChatProvider;
import com.coffer.model.runtime.ModelRuntimeModeService;
import com.coffer.tool.FileParsingTool;
import com.coffer.tool.FileSearchTool;
import com.coffer.tool.TagGenerationTool;
import com.coffer.util.PromptTemplateUtil;
import com.coffer.util.ProxyUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 编排核心服务：负责协调 Agent 与所有工具的工作流，作为对话调用的统一入口。
 *
 * <p>通过 {@link AiServices} 原生编排：装配 {@code parse_file / generate_tags / search_files /
 * get_recent_uploads}（后两者同属 {@link FileSearchTool} 实例：关键词搜索 + 最近上传队列）
 * 与 Redis 会话记忆（{@link ChatMemoryProvider}），对话由 {@link Assistant} 代理执行。
 * 系统提示词经 {@link PromptTemplateUtil} 动态生成（基础系统提示词 + 用户查询/最近历史上下文块）。
 */
@Slf4j
@Service
public class AiAgentService {

    /** 系统提示词中的动态上下文占位符，由 {@link PromptTemplateUtil#buildSystemPromptWithContext} 替换。 */
    private static final String CONTEXT_PLACEHOLDER = "{context}";

    private final ChatProvider chatProvider;
    private final ChatMemoryProvider chatMemoryProvider;
    private final FileParsingTool fileParsingTool;
    private final TagGenerationTool tagGenerationTool;
    private final FileSearchTool fileSearchTool;
    private final PromptTemplateUtil promptTemplateUtil;

    /** Production provider uses this to pin the whole multi-step Agent call to one mode. */
    @Autowired(required = false)
    private ModelRuntimeModeService runtimeModeService;

    /** 基础系统提示词，来自配置 {@code coffer.system-prompt}。 */
    @Value("${coffer.system-prompt:}")
    private String systemPrompt;

    /** AiServices 代理，@PostConstruct 一次性装配，避免每次对话重复 build。 */
    private Assistant assistant;

    public AiAgentService(ChatProvider chatProvider,
                          ChatMemoryProvider chatMemoryProvider,
                          FileParsingTool fileParsingTool,
                          TagGenerationTool tagGenerationTool,
                          FileSearchTool fileSearchTool,
                          PromptTemplateUtil promptTemplateUtil) {
        this.chatProvider = chatProvider;
        this.chatMemoryProvider = chatMemoryProvider;
        this.fileParsingTool = fileParsingTool;
        this.tagGenerationTool = tagGenerationTool;
        this.fileSearchTool = fileSearchTool;
        this.promptTemplateUtil = promptTemplateUtil;
    }

    /**
     * 初始化：一次性装配 Agent 代理并打印调试日志。
     */
    @PostConstruct
    public void init() {
        this.assistant = buildAgent();
        log.info("AiAgentService 初始化完成，Agent 代理已装配: tools=[parse_file, generate_tags, search_files, get_recent_uploads], memory=MessageWindowChatMemory(RedisChatMemoryStore)");
    }

    /**
     * 对话统一入口：拼接用户上下文后调用 Agent，异常时返回友好提示，不向上抛出未捕获异常。
     *
     * @param userMessage 用户消息
     * @param sessionId   会话 ID
     * @return Agent 回复文本；参数非法或调用异常时返回提示信息
     */
    @LogModelCall
    public String chat(String userMessage, String sessionId) {
        if (userMessage == null || userMessage.isBlank()) {
            return "消息不能为空，请提供需要处理的内容";
        }
        if (sessionId == null || sessionId.isBlank()) {
            return "会话 ID 不能为空";
        }
        return chatWithCurrentMode(userMessage, sessionId);
    }

    private String chatWithCurrentMode(String userMessage, String sessionId) {
        try {
            if (runtimeModeService != null && chatProvider instanceof ModeAwareChatProvider) {
                return runtimeModeService.withSnapshot(runtimeModeService.requireActiveMode(),
                        () -> invokeAssistant(userMessage, sessionId));
            }
            return invokeAssistant(userMessage, sessionId);
        } catch (Exception e) {
            log.error("Agent 对话失败 sessionId={}: {}", sessionId, e.getMessage(), e);
            return "抱歉，我暂时无法处理你的请求，请稍后重试";
        }
    }

    private String invokeAssistant(String userMessage, String sessionId) {
        String context = buildContextPrompt(userMessage, sessionId);
        return assistant.chat(context, userMessage, sessionId);
    }

    /**
     * 构建 Agent 代理：AiServices 原生编排，注册三个工具与 Redis 会话记忆。
     *
     * <p>工具实例经 {@link ProxyUtils#unwrap} 解包 AOP 代理
     * （{@code TagGenerationTool} 因 {@code @LogModelCall} 被 CGLIB 代理，
     * 不解包会导致 LangChain4j 扫描不到 {@code @Tool} 注解）。
     * 记忆经 {@link com.coffer.memory.ChatMemoryProvider} 提供原生
     * {@code MessageWindowChatMemory}，由 {@code RedisChatMemoryStore} 持久化到 Redis。
     */
    private Assistant buildAgent() {
        return AiServices.builder(Assistant.class)
                .chatModel(chatProvider)
                .tools(ProxyUtils.unwrap(fileParsingTool),
                        ProxyUtils.unwrap(tagGenerationTool),
                        ProxyUtils.unwrap(fileSearchTool))
                .chatMemoryProvider(memoryId -> chatMemoryProvider.getMemory((String) memoryId))
                .build();
    }

    /**
     * 动态生成完整系统提示词：基础系统提示词 + 「用户查询 + 最近对话历史」上下文块。
     */
    private String buildContextPrompt(String query, String sessionId) {
        List<String> recentHistory = readRecentHistory(sessionId);
        String searchContext = promptTemplateUtil.buildSearchContextPrompt(query, recentHistory);
        return promptTemplateUtil.buildSystemPromptWithContext(
                systemPrompt + "\n" + CONTEXT_PLACEHOLDER,
                Map.of("context", searchContext));
    }

    /**
     * 从会话记忆读取最近对话历史（纯文本行），失败时返回空列表不影响主流程。
     */
    private List<String> readRecentHistory(String sessionId) {
        try {
            ChatMemory memory = chatMemoryProvider.getMemory(sessionId);
            return memory.messages().stream()
                    .map(AiAgentService::messageText)
                    .filter(text -> text != null && !text.isBlank())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("读取会话历史失败（Redis 可能未就绪）sessionId={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 提取各类 ChatMessage 的纯文本；工具调用类消息返回 null（不计入上下文）。
     */
    private static String messageText(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return userMessage.singleText();
        }
        if (message instanceof AiMessage aiMessage) {
            return aiMessage.text();
        }
        if (message instanceof SystemMessage systemMessage) {
            return systemMessage.text();
        }
        return null;
    }

    /**
     * 对话代理接口：{@code @SystemMessage} 模板占位符 {@code {{context}}} 由
     * {@link V} 参数运行时注入（系统提示词经 PromptTemplateUtil 动态生成），
     * 会话记忆按 {@link MemoryId} 隔离。占位符后追加两条静态指令：
     * 置信度规则（标签建议时附带 高/中/低）与“最近上传”与“关键词搜索”两种查询的边界规则
     * （防止模型把 search_files 搜到的文件误判成最近上传，保证“最近/最新/刚刚上传”问题
     * 一律以 get_recent_uploads 的真实返回为准）。
     */
    @dev.langchain4j.service.SystemMessage("{{context}}\n"
            + "在生成标签建议时，请为每个标签附上置信度（高/中/低），以便用户决策。\n"
            + "文件检索有两个不同维度的工具：search_files 按关键词查找用户指定的文件；"
            + "get_recent_uploads 返回系统内按上传时间倒序的最近 8 个文件（先进先出队列，第一条即最近一次上传）。"
            + "只有用户询问“最近上传/最新上传/刚刚上传”类问题时才调用 get_recent_uploads，并以其真实返回为准作答，不得猜测或编造；"
            + "用 search_files 找到的文件只说明“它是匹配该关键词的文件”，未经 get_recent_uploads 确认为最近上传前，不得声称它就是最近（最新）上传的文件。"
            + "若用户要求读取或分析“刚上传/最新上传”的文件，先调用 get_recent_uploads 取得最新文件的文件ID，再调用 parse_file(文件ID) 读取内容。")
    public interface Assistant {
        String chat(@V("context") String context,
                    @dev.langchain4j.service.UserMessage String userMessage,
                    @MemoryId String sessionId);
    }
}
