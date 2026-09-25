package com.coffer.model.provider;

import dev.langchain4j.model.chat.ChatModel;

/**
 * Coffer 对话模型抽象。
 *
 * <p>继承 LangChain4j 的 {@link ChatModel} 是为了直接接入 AiServices，
 * 业务代码只依赖本接口，不感知具体模型供应商或协议实现。</p>
 */
public interface ChatProvider extends ChatModel {

    /**
     * 供应商标识，例如 DEEPSEEK、OPENAI 或本地服务标识。
     */
    String providerId();

    /**
     * 当前实际调用的模型标识。
     */
    String modelName();
}
