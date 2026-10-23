package io.tokenpilot.sample;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.tokenpilot.core.LedgerListener;
import io.tokenpilot.core.domain.CostRecordedEvent;
import io.tokenpilot.core.domain.TokenType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "token-pilot.observe", name = "enabled", havingValue = "true")
public class ObserveLedgerListener implements LedgerListener {

    private static final Logger log = LoggerFactory.getLogger(ObserveLedgerListener.class);
    private static final String API_KEY_HEADER = "X-API-Key";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private final String baseUrl;
    private final String apiKey;
    private final String projectKey;
    private final String environment;
    private final String pricingVersion;

    public ObserveLedgerListener(
            MeterRegistry meterRegistry,
            @Value("${token-pilot.observe.base-url:http://localhost:8081}") String baseUrl,
            @Value("${token-pilot.observe.api-key:}") String apiKey,
            @Value("${token-pilot.observe.project-key:demo-project}") String projectKey,
            @Value("${token-pilot.observe.environment:demo}") String environment,
            @Value("${token-pilot.observe.pricing-version:2026-10-v1}") String pricingVersion
    ) {
        this.meterRegistry = meterRegistry;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.projectKey = projectKey;
        this.environment = environment;
        this.pricingVersion = pricingVersion;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void onRecord(CostRecordedEvent event) {
        if (event == null) {
            return;
        }

        long promptTokens = event.usage() != null ? event.usage().promptTokens() : 0L;
        long completionTokens = event.usage() != null ? event.usage().completionTokens() : 0L;
        long reasoningTokens = event.usage() != null ? event.usage().getCount(TokenType.REASONING) : 0L;
        long cachedTokens = event.usage() != null ? event.usage().getCount(TokenType.CACHE_READ_PROMPT) : 0L;
        long totalTokens = event.usage() != null ? event.usage().totalTokens() : 0L;
        BigDecimal totalCost = (event.cost() != null && event.cost().value() != null)
                ? event.cost().value()
                : BigDecimal.ZERO;

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (event.tags() != null) {
            metadata.putAll(event.tags());
        }
        metadata.put("callStatus", "SUCCESS");
        metadata.put("accountingState", "COMMITTED");
        if (event.usage() != null && event.usage().source() != null) {
            metadata.put("usageSource", event.usage().source().name());
        }

        String requestId = (event.tags() != null && event.tags().containsKey("requestId"))
                ? event.tags().get("requestId")
                : "req-" + UUID.randomUUID();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectKey", projectKey);
        payload.put("environment", environment);
        payload.put("requestId", requestId);
        payload.put("eventId", "evt-" + UUID.randomUUID());
        payload.put("provider", "openai");
        payload.put("model", event.modelId() != null ? event.modelId() : "unknown-model");
        payload.put("promptTokens", promptTokens);
        payload.put("completionTokens", completionTokens);
        payload.put("reasoningTokens", reasoningTokens);
        payload.put("cachedPromptTokens", cachedTokens);
        payload.put("totalTokens", totalTokens);
        payload.put("promptCostUsd", BigDecimal.ZERO);
        payload.put("completionCostUsd", BigDecimal.ZERO);
        payload.put("totalCostUsd", totalCost);
        payload.put("pricingVersion", pricingVersion);
        payload.put("sourceType", "sdk");
        payload.put("metadata", metadata);
        payload.put("occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString());

        sendAsync(baseUrl + "/api/ingestion/events", payload, "single");
    }

    /**
     * 호출 전 실패(PRE_CALL_BLOCKED) 또는 timeout/실패 후 결과 불명확(RECONCILIATION_REQUIRED) 흐름을 전송합니다.
     */
    public void sendNonSuccessFlow(String scenario, String model, String callStatus, String accountingState) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scenario", scenario);
        metadata.put("callStatus", callStatus);
        metadata.put("accountingState", accountingState);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectKey", projectKey);
        payload.put("environment", environment);
        payload.put("requestId", "req-" + scenario + "-" + UUID.randomUUID());
        payload.put("eventId", "evt-" + UUID.randomUUID());
        payload.put("provider", "openai");
        payload.put("model", model);
        payload.put("promptTokens", 0L);
        payload.put("completionTokens", 0L);
        payload.put("totalTokens", 0L);
        payload.put("promptCostUsd", BigDecimal.ZERO);
        payload.put("completionCostUsd", BigDecimal.ZERO);
        payload.put("totalCostUsd", BigDecimal.ZERO);
        payload.put("pricingVersion", pricingVersion);
        payload.put("sourceType", "sdk");
        payload.put("metadata", metadata);
        payload.put("occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString());

        sendAsync(baseUrl + "/api/ingestion/events", payload, "non_success");
    }

    /**
     * 배치 부분 실패(정상 1건 + 필수값 누락 1건)를 E2E로 전송하여 검증합니다.
     */
    public void sendBatchPartialFailureDemo(String model) {
        Map<String, Object> validItem = new LinkedHashMap<>();
        validItem.put("requestId", "req-batch-ok-" + UUID.randomUUID());
        validItem.put("eventId", "evt-" + UUID.randomUUID());
        validItem.put("provider", "openai");
        validItem.put("model", model);
        validItem.put("promptTokens", 100L);
        validItem.put("completionTokens", 50L);
        validItem.put("totalTokens", 150L);
        validItem.put("promptCostUsd", new BigDecimal("0.000015"));
        validItem.put("completionCostUsd", new BigDecimal("0.000030"));
        validItem.put("totalCostUsd", new BigDecimal("0.000045"));
        validItem.put("pricingVersion", pricingVersion);
        validItem.put("sourceType", "sdk");
        validItem.put("metadata", Map.of("scenario", "batch-partial-valid"));
        validItem.put("occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString());

        Map<String, Object> invalidItem = new LinkedHashMap<>();
        invalidItem.put("requestId", "req-batch-bad-" + UUID.randomUUID());
        invalidItem.put("provider", "openai");
        invalidItem.put("model", "");
        invalidItem.put("promptTokens", -1L);
        invalidItem.put("completionTokens", 0L);
        invalidItem.put("pricingVersion", pricingVersion);
        invalidItem.put("occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString());

        Map<String, Object> batchPayload = new LinkedHashMap<>();
        batchPayload.put("projectKey", projectKey);
        batchPayload.put("environment", environment);
        batchPayload.put("items", List.of(validItem, invalidItem));

        sendAsync(baseUrl + "/api/ingestion/events/batch", batchPayload, "batch");
    }

    private void sendAsync(String url, Map<String, Object> payload, String type) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            String jsonBody = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .header(API_KEY_HEADER, apiKey != null ? apiKey : "")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, ex) -> {
                        sample.stop(meterRegistry.timer("tokenpilot_observe_ingestion_latency", "type", type));
                        if (ex != null) {
                            meterRegistry.counter("tokenpilot_observe_ingestion_failure_total",
                                    "type", type, "reason", ex.getClass().getSimpleName()).increment();
                            log.warn("[Observe 통신 실패 - Non-blocking 격리 작동] ({}) {}", type, ex.toString());
                        } else if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            meterRegistry.counter("tokenpilot_observe_ingestion_success_total",
                                    "type", type, "status", String.valueOf(response.statusCode())).increment();
                            log.info("[Observe 전송 성공] ({}) Status: {}, Body: {}", type, response.statusCode(), response.body());
                        } else {
                            meterRegistry.counter("tokenpilot_observe_ingestion_failure_total",
                                    "type", type, "reason", "http_" + response.statusCode()).increment();
                            log.warn("[Observe 수집 거부] ({}) Status: {}, Body: {}", type, response.statusCode(), response.body());
                        }
                    });
        } catch (Exception e) {
            meterRegistry.counter("tokenpilot_observe_ingestion_failure_total",
                    "type", type, "reason", "serialization_error").increment();
            log.warn("[Observe 직렬화/요청 생성 실패] {}", e.toString());
        }
    }
}