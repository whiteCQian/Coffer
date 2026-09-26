package com.coffer.model.runtime;

public class ModelConsentRequiredException extends RuntimeException {
    public ModelConsentRequiredException() { super("请先确认本次 AI 任务的内容发送目标"); }
}
