package com.coffer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime endpoints used by the LOCAL model mode. */
@Data
@ConfigurationProperties(prefix = "coffer.runtime")
public class ModelRuntimeProperties {

    private LocalMode local = new LocalMode();

    @Data
    public static class LocalMode {
        private Endpoint chat = new Endpoint("http://127.0.0.1:11434/v1", "llama3.2", "");
        private Endpoint vision = new Endpoint("http://127.0.0.1:11434/v1", "llava", "");
        private Endpoint embedding = new Endpoint("http://127.0.0.1:11434/v1", "nomic-embed-text", "");
    }

    @Data
    public static class Endpoint {
        private String baseUrl;
        private String modelName;
        private String apiKey;

        public Endpoint() {
        }

        public Endpoint(String baseUrl, String modelName, String apiKey) {
            this.baseUrl = baseUrl;
            this.modelName = modelName;
            this.apiKey = apiKey;
        }
    }
}
