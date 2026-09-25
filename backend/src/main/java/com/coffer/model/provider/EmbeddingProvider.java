package com.coffer.model.provider;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;

/**
 * Coffer 文本向量模型抽象。
 *
 * <p>只暴露当前向量索引和检索需要的最小能力，业务代码不直接依赖
 * LangChain4j 的具体 EmbeddingModel 实现。</p>
 */
public interface EmbeddingProvider {

    /**
     * 将一段文本编码为向量。
     */
    Response<Embedding> embed(String text);

    /**
     * 供应商标识。
     */
    String providerId();

    /**
     * 当前实际调用的模型标识。
     */
    String modelName();

    /**
     * 向量维度。
     */
    int dimension();
}
