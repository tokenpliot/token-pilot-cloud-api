package io.tokenpilot.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.tokenpilot.autoconfigure.TokenPilotAutoConfiguration;
import io.tokenpilot.autoconfigure.TokenPilotSpringAiAutoConfiguration;
import io.tokenpilot.client.DropReason;
import io.tokenpilot.client.TokenPilotClient;
import io.tokenpilot.client.tokenpilot.autoconfigure.TokenPilotClientAutoConfiguration;
import io.tokenpilot.core.LedgerListener;
import io.tokenpilot.core.domain.CostRecordedEvent;

/**
 * Automated E2E verification for Issue #13:
 * 1) Real token usage reaches POST /api/v2/observe/events without prompt/completion leakage.
 * 2) Unpriced models and model-mismatch calls are forwarded with pricing_reconciliation metadata.
 * 3) Server 503 triggers bounded SDK retry and succeeds on the next attempt.
 * 4) Revoked API key (401) is dropped immediately as REQUEST_REFUSED without retry while provider answer is unaffected.
 * 5) Unreachable server drops as RETRIES_EXHAUSTED and never blocks or fails the provider call.
 */
class ObservePilotE2ETest {

    private static final String MODEL = "gpt-4o-2024-08-06";
    private static final String SECRET_PROMPT = "confidential customer prompt 9981";

    private HttpServer server;
    private final List<String> receivedPaths = new CopyOnWriteArrayList<>();
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private final List<String> receivedApiKeys = new CopyOnWriteArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile int statusToReturn = 201;
    private volatile int failFirstNWith503 = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v2/observe/events", exchange -> {
            int attempt = requestCount.incrementAndGet();
            receivedPaths.add(exchange.getRequestURI().getPath());
            receivedApiKeys.add(exchange.getRequestHeaders().getFirst("X-API-Key"));
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            receivedBodies.add(new String(bodyBytes, StandardCharsets.UTF_8));

            int status = (attempt <= failFirstNWith503) ? 503 : statusToReturn;
            String responseJson = switch (status) {
                case 201 -> "{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"관찰 이벤트 수집 성공\",\"data\":{\"eventId\":\"evt-1\",\"accepted\":true,\"duplicate\":false}}";
                case 200 -> "{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"이미 수집된 이벤트\",\"data\":{\"eventId\":\"evt-1\",\"accepted\":true,\"duplicate\":true}}";
                case 401 -> "{\"success\":false,\"code\":\"COMMON-401\",\"message\":\"유효하지 않거나 폐기된 API 키입니다.\",\"data\":null}";
                case 503 -> "{\"success\":false,\"code\":\"INGESTION-503\",\"message\":\"일시적 서버 오류\",\"data\":null}";
                default -> "{\"success\":false,\"code\":\"COMMON-400\",\"message\":\"잘못된 요청\",\"data\":null}";
            };
            byte[] responseBytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String serverUri() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private ApplicationContextRunner app(String... extraProperties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TokenPilotAutoConfiguration.class,
                        TokenPilotSpringAiAutoConfiguration.class,
                        TokenPilotClientAutoConfiguration.class))
                .withUserConfiguration(TestCustomerApp.class)
                .withPropertyValues(
                        "token-pilot.pricing.plans[0].model-id=" + MODEL,
                        "token-pilot.pricing.plans[0].currency=USD",
                        "token-pilot.pricing.plans[0].rates.PROMPT=0.00015",
                        "token-pilot.pricing.plans[0].rates.COMPLETION=0.00060",
                        "token-pilot.client.endpoint=" + serverUri(),
                        "token-pilot.client.ingestion-api=observe-v2",
                        "token-pilot.client.api-key=tpk_live_test_secret",
                        "token-pilot.client.project-key=demo-project",
                        "token-pilot.client.environment=demo",
                        "token-pilot.client.provider=openai",
                        "token-pilot.client.flush-interval=1h",
                        "token-pilot.client.forwarded-tags.tenant_id=tenant_id",
                        "token-pilot.client.forwarded-tags.scenario=scenario"
                )
                .withPropertyValues(extraProperties);
    }

    private static String callChat(ChatClient chatClient, String model) {
        return chatClient.prompt()
                .user(SECRET_PROMPT)
                .options(ChatOptions.builder().model(model).maxTokens(64))
                .advisors(advisor -> advisor
                        .param("tokenpilot.request.id", "req-e2e-1")
                        .param("tenant_id", "tenant-demo")
                        .param("scenario", "observe-e2e")
                        .param("unlisted_tag", "must-not-be-forwarded"))
                .call()
                .content();
    }

    @Test
    void observeV2SendsActualTokensAndForwardedTagsWithoutPromptText() {
        app().run(context -> {
            String answer = callChat(context.getBean(ChatClient.Builder.class).build(), MODEL);
            TokenPilotClient client = context.getBean(TokenPilotClient.class);
            assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

            assertThat(answer).isEqualTo("provider answer");
            assertThat(receivedPaths).containsExactly("/api/v2/observe/events");
            assertThat(receivedApiKeys).containsExactly("tpk_live_test_secret");
            assertThat(receivedBodies).singleElement().satisfies(body -> {
                assertThat(body)
                        .contains("\"projectKey\":\"demo-project\"")
                        .contains("\"environment\":\"demo\"")
                        .contains("\"schemaVersion\":\"observe-v1\"")
                        .contains("\"tokenSemantics\":\"INCLUSIVE_V1\"")
                        .contains("\"model\":\"" + MODEL + "\"")
                        .contains("\"callStatus\":\"SUCCEEDED\"")
                        .contains("\"promptTokens\":120")
                        .contains("\"completionTokens\":80")
                        .contains("\"app_request_id\":\"req-e2e-1\"")
                        .contains("\"tenant_id\":\"tenant-demo\"")
                        .contains("\"scenario\":\"observe-e2e\"")
                        .doesNotContain("unlisted_tag")
                        .doesNotContain("must-not-be-forwarded")
                        .doesNotContain(SECRET_PROMPT)
                        .doesNotContain("provider answer")
                        .doesNotContain("totalCostUsd");
            });

            CostRecordedEvent recorded = context.getBean(LedgerProbe.class).records.get(0);
            assertThat(recorded.usage().inputTokens()).isEqualTo(120L);
            assertThat(recorded.usage().outputTokens()).isEqualTo(80L);
            assertThat(client.stats().created()).isEqualTo(1L);
        });
    }

    @Test
    void unpricedAndModelMismatchCallsAreForwardedWithReconciliationMetadata() {
        app().run(context -> {
            ChatClient chatClient = context.getBean(ChatClient.Builder.class).build();
            callChat(chatClient, "gpt-unpriced-model");
            context.getBean(ConfigurableFakeModel.class).respondAsModel.set("gpt-4o-2024-11-20");
            callChat(chatClient, MODEL);

            TokenPilotClient client = context.getBean(TokenPilotClient.class);
            assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

            assertThat(receivedBodies).hasSize(2);
            assertThat(receivedBodies.get(0))
                    .contains("\"model\":\"gpt-unpriced-model\"")
                    .contains("\"pricing_reconciliation\":\"unpriced\"");
            assertThat(receivedBodies.get(1))
                    .contains("\"model\":\"gpt-4o-2024-11-20\"")
                    .contains("\"pricing_reconciliation\":\"reconciliation_required\"");
            assertThat(client.stats().created()).isEqualTo(2L);
        });
    }

    @Test
    void server503TriggersSdkRetryAndSucceedsWithoutDroppingEvent() {
        failFirstNWith503 = 1;

        app("token-pilot.client.max-attempts=3").run(context -> {
            String answer = callChat(context.getBean(ChatClient.Builder.class).build(), MODEL);
            TokenPilotClient client = context.getBean(TokenPilotClient.class);
            assertThat(client.flush(Duration.ofSeconds(10))).isTrue();

            assertThat(answer).isEqualTo("provider answer");
            assertThat(requestCount.get()).isEqualTo(2);
            assertThat(client.stats().retried()).isEqualTo(1L);
            assertThat(client.stats().created()).isEqualTo(1L);
            assertThat(client.stats().droppedTotal()).isZero();
        });
    }

    @Test
    void revokedApiKey401IsDroppedWithoutRetryAndDoesNotFailProviderCall() {
        statusToReturn = 401;

        app("token-pilot.client.max-attempts=3").run(context -> {
            String answer = callChat(context.getBean(ChatClient.Builder.class).build(), MODEL);
            TokenPilotClient client = context.getBean(TokenPilotClient.class);
            assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

            assertThat(answer).isEqualTo("provider answer");
            assertThat(requestCount.get()).as("401 REVOKED key must not be retried").isEqualTo(1);
            assertThat(client.stats().dropped().get(DropReason.REQUEST_REFUSED)).isEqualTo(1L);
            assertThat(client.stats().droppedTotal()).isEqualTo(1L);
            assertThat(client.stats().retried()).isZero();
        });
    }

    @Test
    void unreachableServerDoesNotBlockOrFailProviderCall() {
        app("token-pilot.client.endpoint=http://127.0.0.1:1",
            "token-pilot.client.max-attempts=1",
            "token-pilot.client.request-timeout=200ms").run(context -> {
            String answer = callChat(context.getBean(ChatClient.Builder.class).build(), MODEL);
            TokenPilotClient client = context.getBean(TokenPilotClient.class);
            assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

            assertThat(answer).isEqualTo("provider answer");
            assertThat(client.stats().dropped().get(DropReason.RETRIES_EXHAUSTED)).isEqualTo(1L);
            assertThat(client.stats().droppedTotal()).isEqualTo(1L);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestCustomerApp {

        @Bean
        ConfigurableFakeModel fakeChatModel() {
            return new ConfigurableFakeModel();
        }

        @Bean
        ChatClient.Builder chatClientBuilder(
                ChatModel chatModel,
                ObjectProvider<ChatClientBuilderCustomizer> customizers
        ) {
            ChatClient.Builder builder = ChatClient.builder(chatModel);
            customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
            return builder;
        }

        @Bean
        LedgerProbe ledgerProbe() {
            return new LedgerProbe();
        }
    }

    static final class ConfigurableFakeModel implements ChatModel {

        final AtomicReference<String> respondAsModel = new AtomicReference<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            String model = respondAsModel.get() != null
                    ? respondAsModel.get()
                    : prompt.getOptions().getModel();
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage("provider answer"))),
                    ChatResponseMetadata.builder()
                            .model(model)
                            .usage(new DefaultUsage(120, 80))
                            .build()
            );
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    static final class LedgerProbe implements LedgerListener {

        final List<CostRecordedEvent> records = new CopyOnWriteArrayList<>();

        @Override
        public void onRecord(CostRecordedEvent event) {
            records.add(event);
        }
    }
}