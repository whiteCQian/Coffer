package com.coffer.model.runtime;

import com.coffer.governance.domain.GovernanceRunMode;

/** User-actionable mode errors with fixed messages and no credential or endpoint material. */
public final class ModelModeNotReadyException extends IllegalArgumentException {
    private ModelModeNotReadyException(String message) {
        super(message);
    }

    public static ModelModeNotReadyException currentNotValidated(GovernanceRunMode mode) {
        return new ModelModeNotReadyException("当前启用的" + label(mode)
                + "尚未通过完整校验。请在设置→模型连接中保存 Chat 和 Vision 配置，点击“测试当前模式”；如需使用其他模式，校验通过后点击“启用该模式”。");
    }

    public static ModelModeNotReadyException targetNotValidated(GovernanceRunMode mode) {
        return new ModelModeNotReadyException("目标运行模式尚未通过完整校验：" + label(mode)
                + "。请先保存 Chat 和 Vision 配置，再点击“测试当前模式”。");
    }

    public static ModelModeNotReadyException mismatch(GovernanceRunMode active) {
        return new ModelModeNotReadyException("请求的模型模式与当前启用的" + label(active)
                + "不一致。请在设置→模型连接中校验并启用本次需要的模式，再重新提交。");
    }

    private static String label(GovernanceRunMode mode) {
        return mode == GovernanceRunMode.LOCAL ? "本地模式（LOCAL）" : "API 模式";
    }
}
