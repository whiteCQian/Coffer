package com.coffer.auth.service;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
@Component
public class PasswordPolicy {
    private final boolean production;
    public PasswordPolicy(Environment environment) { production = environment.matchesProfiles("prod"); }
    public int minimumLength() { return production ? 12 : 6; }
    public boolean strongRequired() { return production; }
    public void validate(String password) {
        if (password == null || password.length() < minimumLength() || password.length() > 16)
            throw new AuthFailureException(HttpStatus.BAD_REQUEST, "密码长度须为 " + minimumLength() + "–16 个字符");
        if (production && (!password.matches("(?s).*[A-Z].*") || !password.matches("(?s).*[a-z].*")
                || !password.matches("(?s).*[0-9].*") || password.matches("(?is).*(password|qwerty|123456|letmein|coffer|admin).*")))
            throw new AuthFailureException(HttpStatus.BAD_REQUEST, "生产密码须为 12–16 个字符，包含大小写字母和数字，并避开常见弱口令");
    }
}
