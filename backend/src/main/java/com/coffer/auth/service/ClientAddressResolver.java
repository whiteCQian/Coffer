package com.coffer.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Resolves the client address without trusting forwarding headers from arbitrary peers. */
@Component
public class ClientAddressResolver {

    private static final Pattern IPV4 = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]+" );

    @Value("${coffer.auth.trusted-proxies:}")
    private String trustedProxyConfig;

    public String clientKey(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) return "unknown";
        Set<String> trusted = trustedProxies();
        if (!trusted.contains(remote)) return remote;

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return remote;
        String[] chain = forwarded.split(",");
        for (int index = chain.length - 1; index >= 0; index--) {
            String candidate = chain[index].trim();
            if (!isIpLiteral(candidate)) return remote;
            if (!trusted.contains(candidate)) return candidate;
        }
        return remote;
    }

    private Set<String> trustedProxies() {
        if (trustedProxyConfig == null || trustedProxyConfig.isBlank()) return Set.of();
        return Arrays.stream(trustedProxyConfig.split(","))
                .map(String::trim)
                .filter(ClientAddressResolver::isIpLiteral)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isIpLiteral(String value) {
        return value != null && (IPV4.matcher(value).matches() || IPV6.matcher(value).matches());
    }
}
