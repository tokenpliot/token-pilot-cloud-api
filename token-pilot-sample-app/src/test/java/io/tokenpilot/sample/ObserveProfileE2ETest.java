package io.tokenpilot.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 이슈 #13: 실제 앱 구성(프로필)으로 띄운 샘플 앱이 SDK를 통해 가짜 수집 서버로 이벤트를 보내는지 검증한다.
 * {@link ObservePilotE2ETest}는 자동 구성만 조립해서 보지만, 이 테스트는 application*.yml까지 포함한 실제 기동 상태를 본다.
 */
class ObserveProfileE2ETest {

    private static final HttpServer SERVER;
    private static final List<String> BODIES = new CopyOnWriteArrayList<>();

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SERVER.createContext("/api/v2/observe/events", exchange -> {
            BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"success\":true,\"code\":\"SUCCESS\",\"data\":{\"accepted\":true,\"duplicate\":false}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        SERVER.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        SERVER.start();
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    static void observeProperties(DynamicPropertyRegistry registry) {
        registry.add("token-pilot.client.endpoint", () -> "http://localhost:" + SERVER.getAddress().getPort());
        registry.add("token-pilot.client.api-key", () -> "tpk_test_secret");
    }

    static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** observe 프로필(예산 꺼짐): 호출마다 실제 사용량 이벤트가 전송된다. */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("observe")
    @DirtiesContext
    class ObserveProfile {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            observeProperties(registry);
        }

        @LocalServerPort
        int port;

        @Test
        void eachProviderCallIsForwardedWithActualUsage() throws Exception {
            BODIES.clear();

            HttpResponse<String> call = get(port, "/test/token-pilot/observe/call?count=3");
            HttpResponse<String> stats = get(port, "/test/token-pilot/observe/stats?flush=true");

            assertThat(call.statusCode()).isEqualTo(200);
            assertThat(call.body()).contains("\"providerAnswered\":3");
            assertThat(stats.body())
                    .contains("\"flushed\":true")
                    .contains("\"localRecordedEvents\":3")
                    // 8 * 0.00015/1K + 3 * 0.00060/1K = 0.000003 USD/호출
                    .contains("\"localCostUsd\":\"0.000009")
                    .contains("\"observeEnabled\":true")
                    .contains("\"recorded\":3")
                    .contains("\"created\":3")
                    .contains("\"droppedTotal\":0");
            assertThat(BODIES).hasSize(3).allSatisfy(body -> assertThat(body)
                    .contains("\"projectKey\":\"demo-project\"")
                    .contains("\"model\":\"gpt-4o-2024-08-06\"")
                    .contains("\"callStatus\":\"SUCCEEDED\"")
                    // DemoChatModel이 돌려주는 실제 사용량 (8/3)
                    .contains("\"promptTokens\":8")
                    .contains("\"completionTokens\":3")
                    .contains("\"scenario\":\"observe-e2e\"")
                    .doesNotContain("Observe E2E request"));
        }
    }

    /**
     * 현재 제약을 고정하는 테스트: demo 프로필(예산 켜짐)은 Token Pilot 예약·정산 경로를 타서
     * LedgerListener가 호출되지 않으므로 SDK로 아무것도 전송되지 않는다.
     * SDK가 ReservationAccountingListener를 구독하도록 바뀌면 이 테스트가 실패하며, 그때 기대값을 바꾼다.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("demo")
    @DirtiesContext
    class DemoProfileKnownGap {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            observeProperties(registry);
        }

        @LocalServerPort
        int port;

        @Test
        void budgetAccountingPathCurrentlyForwardsNothing() throws Exception {
            BODIES.clear();

            HttpResponse<String> response = get(port, "/test/token-pilot/demo/reconciliation-success");

            assertThat(response.body())
                    .contains("\"status\":\"PASS\"")
                    .contains("\"accountingState\":\"COMMITTED\"");
            Thread.sleep(1_500);
            assertThat(BODIES).as("budget path does not reach LedgerListener yet").isEmpty();
        }
    }
}
