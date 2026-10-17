package com.coffer.config;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounds the complete blocking response body, including providers that keep sending whitespace. */
final class DeadlineHttpClient extends HttpClient {
    private final HttpClient delegate;
    private DeadlineHttpClient(HttpClient delegate) { this.delegate = delegate; }

    static Builder builder(Builder delegate) { return new DeadlineBuilder(delegate); }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        var pending = delegate.sendAsync(request, handler);
        try {
            var timeout = request.timeout();
            return timeout.isPresent() ? pending.get(timeout.get().toNanos(), TimeUnit.NANOSECONDS) : pending.get();
        } catch (TimeoutException expired) {
            pending.cancel(true);
            throw new HttpTimeoutException("Model response deadline exceeded");
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            throw interrupted;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IOException("Model HTTP request failed", cause);
        }
    }

    // Streaming responses retain their existing event-driven lifecycle.
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return delegate.sendAsync(request, handler);
    }
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> pushHandler) { return delegate.sendAsync(request, handler, pushHandler); }
    @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
    @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
    @Override public Redirect followRedirects() { return delegate.followRedirects(); }
    @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
    @Override public SSLContext sslContext() { return delegate.sslContext(); }
    @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
    @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
    @Override public Version version() { return delegate.version(); }
    @Override public Optional<Executor> executor() { return delegate.executor(); }

    private static final class DeadlineBuilder implements Builder {
        private final Builder delegate;
        private DeadlineBuilder(Builder delegate) { this.delegate = delegate; }
        @Override public HttpClient build() { return new DeadlineHttpClient(delegate.build()); }
        @Override public Builder cookieHandler(CookieHandler value) { delegate.cookieHandler(value); return this; }
        @Override public Builder connectTimeout(Duration value) { delegate.connectTimeout(value); return this; }
        @Override public Builder sslContext(SSLContext value) { delegate.sslContext(value); return this; }
        @Override public Builder sslParameters(SSLParameters value) { delegate.sslParameters(value); return this; }
        @Override public Builder executor(Executor value) { delegate.executor(value); return this; }
        @Override public Builder followRedirects(Redirect value) { delegate.followRedirects(value); return this; }
        @Override public Builder version(Version value) { delegate.version(value); return this; }
        @Override public Builder priority(int value) { delegate.priority(value); return this; }
        @Override public Builder proxy(ProxySelector value) { delegate.proxy(value); return this; }
        @Override public Builder authenticator(Authenticator value) { delegate.authenticator(value); return this; }
    }
}
