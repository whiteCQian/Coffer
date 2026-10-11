package com.coffer.auth.service;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
@Component
public class PasswordPolicy {
    public int minimumLength() { return 8; }
    public boolean strongRequired() { return false; }
    public void validate(String password) {
        if (password == null || password.length() < minimumLength() || password.length() > 16)
            throw new AuthFailureException(HttpStatus.BAD_REQUEST, "密码长度须为 " + minimumLength() + "–16 个字符");
    }
}
