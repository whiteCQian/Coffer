package com.coffer.config;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** Shared model transport with redirects disabled so a trusted endpoint cannot bounce to another host. */
public final class SafeModelHttpClient {

    private SafeModelHttpClient() { }

    public static HttpClientBuilder builder() {
        var jdkClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10));
        return JdkHttpClient.builder().httpClientBuilder(jdkClient)
                .connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(90));
    }
}
