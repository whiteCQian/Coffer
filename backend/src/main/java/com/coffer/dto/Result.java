package com.coffer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 API 响应封装。
 *
 * @param <T> data 数据类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /** 业务状态码，0 表示成功。 */
    private int code;

    /** 提示信息。 */
    private String msg;

    /** 业务数据。 */
    private T data;

    /**
     * 成功响应。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return Result
     */
    public static <T> Result<T> success(T data) {
        return Result.<T>builder().code(0).msg("success").data(data).build();
    }

    /**
     * 成功响应（无数据）。
     *
     * @param <T> 数据类型
     * @return Result
     */
    public static <T> Result<T> success() {
        return Result.<T>builder().code(0).msg("success").build();
    }

    /**
     * 失败响应。
     *
     * @param code 业务状态码
     * @param msg  错误信息
     * @param <T>  数据类型
     * @return Result
     */
    public static <T> Result<T> error(int code, String msg) {
        return Result.<T>builder().code(code).msg(msg).build();
    }
}
