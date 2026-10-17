package com.coffer.model.runtime;

import com.coffer.config.EmbeddingProperties;
import com.coffer.entity.ModelProvider;
import com.coffer.governance.domain.GovernanceRunMode;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class ModelRuntimeConnectivityTesterTest {
    @Test
    void visionProbeContainsSyntheticPixelsAndTimeoutDoesNotImplyBadCredentials() throws Exception {
        var received = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"id\":\"fixture\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"red\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var tester = new ModelRuntimeConnectivityTester(new EmbeddingProperties());
            var endpoint = new ResolvedModelRuntimeEndpoint(GovernanceRunMode.LOCAL, ModelRuntimeCapability.VISION,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fixture", "", "CUSTOM", true, ModelProvider.QWEN_VL);
            assertThat(tester.test(endpoint).isSuccess()).isTrue();
            assertThat(received.get()).contains("image_url", "data:image/png;base64,");
            String timeout = org.springframework.test.util.ReflectionTestUtils.invokeMethod(tester, "safeMessage", new java.net.http.HttpTimeoutException("fixture"));
            assertThat(timeout).contains("超时").doesNotContain("Key", "密钥");
        } finally { server.stop(0); }
    }

    @Test
    void chatConnectionReportsProviderOutageAndRecoversWithoutChangingConfiguration() throws Exception {
        var status = new AtomicInteger(503);
        var calls = new AtomicInteger();
        var received = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // DeepSeek also accepts a bare origin as its Base URL; /v1 is not mandatory.
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String payload = status.get() == 200
                    ? "{\"id\":\"fixture\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}"
                    : "{\"error\":{\"code\":\"service_unavailable_error\",\"message\":\"synthetic-key PRIVATE_BODY_MARKER\"}}";
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var endpoint = new ResolvedModelRuntimeEndpoint(GovernanceRunMode.LOCAL, ModelRuntimeCapability.CHAT,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "deepseek-flash", "synthetic-key",
                    "CUSTOM", true, ModelProvider.DEEPSEEK);
            var tester = new ModelRuntimeConnectivityTester(new EmbeddingProperties());
            var unavailable = tester.test(endpoint);
            assertThat(unavailable.isSuccess()).isFalse();
            assertThat(unavailable.getMessage()).contains("HTTP 503", "暂时不可用")
                    .doesNotContain("密钥配置", "synthetic-key", "PRIVATE_BODY_MARKER");
            assertThat(calls.get()).isEqualTo(1);
            assertThat(new ObjectMapper().readTree(received.get()).path("model").asText()).isEqualTo("deepseek-flash");

            status.set(200);
            assertThat(tester.test(endpoint).isSuccess()).isTrue();
            assertThat(calls.get()).isEqualTo(2);

            status.set(401);
            var unauthorized = tester.test(endpoint);
            assertThat(unauthorized.isSuccess()).isFalse();
            assertThat(unauthorized.getMessage()).contains("API Key").doesNotContain("HTTP 503", "PRIVATE_BODY_MARKER");
            assertThat(calls.get()).isEqualTo(3);
        } finally { server.stop(0); }
    }

    @Test
    void missingEmbeddingKeyIsExplainedBeforeAnyNetworkRequest() {
        var tester = new ModelRuntimeConnectivityTester(new EmbeddingProperties());
        // This unreachable endpoint would fail differently if the tester contacted it.
        var endpoint = new ResolvedModelRuntimeEndpoint(GovernanceRunMode.API, ModelRuntimeCapability.EMBEDDING,
                "https://unreachable.invalid/v1", "text-embedding-v3", "", "DEFAULT", false, ModelProvider.QWEN_VL);
        var response = tester.test(endpoint);
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).contains("Embedding", "API Key", "对应");
    }

    @Test
    void embeddingConnectionCanBeTestedBeforeSemanticSearchIsEnabled() throws Exception {
        var properties = new EmbeddingProperties();
        properties.setDimensions(1024);
        properties.setTimeoutSeconds(3);
        var receivedBody = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var responseStatus = new AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            var vector = "0.1,".repeat(1023) + "0.1";
            var payload = responseStatus.get() == 200
                    ? "{\"object\":\"list\",\"model\":\"text-embedding-v3\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[" + vector + "]}],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}"
                    : "{\"error\":{\"message\":\"synthetic-embedding-key PRIVATE_BODY_MARKER\",\"type\":\"invalid_request_error\"}}";
            var body = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var endpoint = new ResolvedModelRuntimeEndpoint(GovernanceRunMode.LOCAL, ModelRuntimeCapability.EMBEDDING,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "text-embedding-v3", "synthetic-embedding-key", "CUSTOM", true, ModelProvider.QWEN_VL);
            var result = new ModelRuntimeConnectivityTester(properties).test(endpoint);
            assertThat(result.isSuccess()).isTrue();
            assertThat(properties.isEnabled()).isFalse();
            assertThat(authorization.get()).isEqualTo("Bearer synthetic-embedding-key");
            var request = new ObjectMapper().readTree(receivedBody.get());
            assertThat(request.get("model").asText()).isEqualTo("text-embedding-v3");
            assertThat(request.get("dimensions").asInt()).isEqualTo(1024);
            responseStatus.set(401);
            var authFailure = new ModelRuntimeConnectivityTester(properties).test(endpoint);
            assertThat(authFailure.isSuccess()).isFalse();
            assertThat(authFailure.getMessage()).contains("API Key").doesNotContain("synthetic-embedding-key", "PRIVATE_BODY_MARKER");
            responseStatus.set(404);
            var modelFailure = new ModelRuntimeConnectivityTester(properties).test(endpoint);
            assertThat(modelFailure.isSuccess()).isFalse();
            assertThat(modelFailure.getMessage()).contains("Embedding", "接口").doesNotContain("PRIVATE_BODY_MARKER");
        } finally {
            server.stop(0);
        }
    }
}
