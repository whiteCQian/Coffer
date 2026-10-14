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
