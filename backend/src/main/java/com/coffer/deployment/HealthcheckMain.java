package com.coffer.deployment;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** No curl/JDK tooling required in the runtime image; only a fixed local probe. */
public final class HealthcheckMain {
    public static void main(String[] args) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var response = client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:8081/actuator/health/readiness"))
                .timeout(Duration.ofSeconds(4)).GET().build(), HttpResponse.BodyHandlers.discarding());
        System.exit(response.statusCode() == 200 ? 0 : 1);
    }
}
