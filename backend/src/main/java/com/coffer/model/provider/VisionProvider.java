package com.coffer.model.provider;

/**
 * Coffer 视觉模型抽象。
 *
 * <p>视觉模型沿用 ChatModel 的多模态消息协议，但单独建模，避免业务层把
 * 图片分析能力和普通对话能力混为一个依赖。</p>
 */
public interface VisionProvider extends ChatProvider {
}
