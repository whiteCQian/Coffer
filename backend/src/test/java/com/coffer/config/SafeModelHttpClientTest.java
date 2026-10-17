package com.coffer.config;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.http.client.HttpRequest;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SafeModelHttpClientTest {
    @Test
    void deadlineIncludesResponseBodyAndCancelsWhitespaceKeepalive() throws Exception {
        var headersSent = new CountDownLatch(1);
        var disconnected = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(' ');
                exchange.getResponseBody().flush();
                headersSent.countDown();
                for (int i = 0; i < 200; i++) {
                    Thread.sleep(25);
                    exchange.getResponseBody().write(' ');
                    exchange.getResponseBody().flush();
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException cancelled) { /* Expected: the timed-out HTTP exchange is cancelled. */ }
            finally { exchange.close(); disconnected.countDown(); }
        });
        server.start();
        try {
            var client = SafeModelHttpClient.builder().readTimeout(Duration.ofMillis(400)).build();
            var request = HttpRequest.builder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions")
                    .method(dev.langchain4j.http.client.HttpMethod.POST).body("{}").build();
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertThatThrownBy(() -> client.execute(request)).isInstanceOf(dev.langchain4j.exception.TimeoutException.class));
            assertThat(headersSent.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(disconnected.await(2, TimeUnit.SECONDS)).isTrue();
        } finally { server.stop(0); }
    }

    @Test
    void modelRedirectsRemainDisabled() throws Exception {
        var reachedTarget = new java.util.concurrent.atomic.AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/private");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/private", exchange -> {
            reachedTarget.set(true); exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        try {
            var client = SafeModelHttpClient.builder().readTimeout(Duration.ofSeconds(2)).build();
            var request = HttpRequest.builder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect")
                    .method(dev.langchain4j.http.client.HttpMethod.GET).build();
            assertThatThrownBy(() -> client.execute(request)).isInstanceOfSatisfying(
                    dev.langchain4j.exception.HttpException.class, failure -> assertThat(failure.statusCode()).isEqualTo(302));
            assertThat(reachedTarget).isFalse();
        } finally { server.stop(0); }
    }
}
