package com.coffer.model.provider;

/** Deliberately excludes provider response bodies, headers, URLs and exception causes. */
public final class ModelInvocationException extends RuntimeException {
    public ModelInvocationException() { super("模型调用失败，请检查模型设置或稍后重试"); }
    public static <T> T safely(java.util.function.Supplier<T> action) {
        try { return action.get(); }
        catch (RuntimeException failure) { throw new ModelInvocationException(); }
    }
}
