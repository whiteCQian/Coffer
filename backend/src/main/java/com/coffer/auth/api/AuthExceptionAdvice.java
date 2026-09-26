package com.coffer.auth.api;

import com.coffer.auth.service.AuthFailureException;
import com.coffer.dto.Result;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionAdvice {

    @ExceptionHandler(AuthFailureException.class)
    public ResponseEntity<Result<Void>> authFailure(AuthFailureException exception) {
        return ResponseEntity.status(exception.status())
                .body(Result.error(exception.status().value(), exception.getMessage()));
    }
}
