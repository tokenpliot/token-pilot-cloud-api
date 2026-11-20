package com.tokenledgercloud.api.domain.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import com.tokenledgercloud.api.domain.shadow.controller.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:shadow-disabled;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "spring.flyway.enabled=false",
    "token-pilot.ingestion.max-event-bytes=2048"
})
class ShadowApiDisabledHttpTest {
    @Value("${local.server.port}") int port;
    @Autowired ApplicationContext context;

    @Test
    void controllersDefaultOffAndExecutionEndpointReturns404() throws Exception {
        assertThat(context.getBeanNamesForType(ShadowController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ExecutionEventController.class)).isEmpty();
        var response = post("/api/control/v1/execution-events", "{}");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("outcome");
    }

    @Test
    void sizeLimitAppliesBeforeAuthenticationIncludingEncodedPaths() throws Exception {
        for (String path : List.of("/api/control/v1/execution-events", "/api/control/v1/execution-events/",
            "/api/control/v1/execution%2Devents")) {
            assertThat(post(path, "x".repeat(2049)).statusCode()).as(path).isEqualTo(413);
        }
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
