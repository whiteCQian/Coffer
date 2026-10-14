package com.coffer.model.runtime;

/** Only fixed, public configuration guidance may be returned to the browser. */
public final class ModelEndpointValidationException extends IllegalArgumentException {
    public enum Reason {
        INVALID_URL("Base URL 必须是有效的 HTTP(S) 地址，且不能包含账号、查询参数或片段"),
        HTTPS_REQUIRED("API 模式必须使用 HTTPS 模型地址，端口应为 443"),
        HOST_NOT_ALLOWED("API 模式只允许已批准的 HTTPS 模型端点；当前域名未在服务器允许列表中"),
        LOCAL_NOT_ALLOWED("本地模型端点不在部署允许列表中，请检查地址和端口"),
        INCOMPATIBLE_PATH("请填写 OpenAI 兼容 Base URL（/v1 或 /compatible-mode/v1），不能填写 DashScope 原生接口地址"),
        EMPTY_URL("请填写模型服务的 Base URL");

        private final String message;
        Reason(String message) { this.message = message; }
    }

    public ModelEndpointValidationException(Reason reason) { super(reason.message); }
}
