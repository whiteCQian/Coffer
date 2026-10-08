package com.coffer.exception;

import com.coffer.dto.Result;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Legacy controllers use Result.error; their transport status must also represent failure. */
@RestControllerAdvice
public class UnifiedResultAdvice implements ResponseBodyAdvice<Object> {
    @Override public boolean supports(MethodParameter type, Class<? extends HttpMessageConverter<?>> converter) { return true; }
    @Override public Object beforeBodyWrite(Object body, MethodParameter type, MediaType mediaType,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof Result<?> result && result.getCode() >= 400 && result.getCode() <= 599)
            response.setStatusCode(HttpStatusCode.valueOf(result.getCode()));
        return body;
    }
}
