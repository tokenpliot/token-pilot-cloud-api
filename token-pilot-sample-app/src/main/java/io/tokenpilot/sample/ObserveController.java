package io.tokenpilot.sample;

import io.tokenpilot.client.ClientStats;
import io.tokenpilot.client.TokenPilotClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 이슈 #13 Observe E2E 검증용 HTTP API. {@code observe} 프로필에서만 열린다. */
@RestController
@Profile("observe")
public class ObserveController {

    private static final String MODEL_ID = "gpt-4o-2024-08-06";
    // 두 번째 모델은 서버에 가격표를 등록하지 않아 중앙 원장에서 UNPRICED로 남는지 확인하는 용도
    private static final Set<String> ALLOWED_MODELS = Set.of(MODEL_ID, "gpt-4o-mini-2024-07-18");
    private static final int MAX_CALLS = 20;
    private static final Duration FLUSH_TIMEOUT = Duration.ofSeconds(5);

    private final ChatClient.Builder chatClientBuilder;
    private final ObserveCostTally costTally;
    private final Optional<TokenPilotClient> tokenPilotClient;

    public ObserveController(
            ChatClient.Builder chatClientBuilder,
            ObserveCostTally costTally,
            Optional<TokenPilotClient> tokenPilotClient
    ) {
        this.chatClientBuilder = chatClientBuilder;
        this.costTally = costTally;
        this.tokenPilotClient = tokenPilotClient;
    }

    /**
     * 가짜 provider를 {@code count}번 호출한다. 각 호출의 실제 사용량이 SDK를 통해 중앙으로 전송된다.
     * {@code model}은 application-observe.yml에 가격이 있는 모델만 쓸 수 있다.
     */
    @GetMapping("/test/token-pilot/observe/call")
    public Map<String, Object> call(
            @RequestParam(defaultValue = "1") int count,
            @RequestParam(defaultValue = MODEL_ID) String model
    ) {
        if (count < 1 || count > MAX_CALLS) {
            throw new IllegalArgumentException("count must be between 1 and " + MAX_CALLS);
        }
        if (!ALLOWED_MODELS.contains(model)) {
            throw new IllegalArgumentException("model must be one of " + ALLOWED_MODELS);
        }
        ChatClient chatClient = chatClientBuilder.clone().build();
        int answered = 0;
        for (int i = 0; i < count; i++) {
            String requestId = "observe-" + UUID.randomUUID();
            String answer = chatClient.prompt()
                    .user("Observe E2E request")
                    .options(ChatOptions.builder().model(model).maxTokens(16))
                    .advisors(advisors -> advisors
                            .param("tokenpilot.request.id", requestId)
                            .param("tenant_id", "observe-tenant")
                            .param("scenario", "observe-e2e"))
                    .call()
                    .content();
            if (answer != null && !answer.isBlank()) {
                answered++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("calls", count);
        // provider 응답이 Observe 전송 결과와 무관하게 그대로 돌아왔는지 확인하는 값
        result.put("providerAnswered", answered);
        result.putAll(snapshot());
        return result;
    }

    /**
     * 이벤트를 새로 만들지 않고 현재 통계만 돌려준다.
     * {@code flush=true}면 SDK 큐가 빌 때까지(최대 5초) 기다린 뒤 통계를 읽는다.
     */
    @GetMapping("/test/token-pilot/observe/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "false") boolean flush)
            throws InterruptedException {
        Map<String, Object> result = new LinkedHashMap<>();
        if (flush) {
            result.put("flushed", tokenPilotClient.isPresent() && tokenPilotClient.get().flush(FLUSH_TIMEOUT));
        }
        result.putAll(snapshot());
        return result;
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        // 샘플 앱(Token Pilot)이 기록한 이벤트 수와 누적 비용. 중앙 원장과 대조하는 기준값이다.
        result.put("localRecordedEvents", costTally.events());
        result.put("localCostUsd", costTally.totalUsd().toPlainString());
        result.put("observeEnabled", tokenPilotClient.isPresent());
        tokenPilotClient.ifPresent(client -> {
            ClientStats stats = client.stats();
            Map<String, Object> sdk = new LinkedHashMap<>();
            sdk.put("recorded", stats.recorded());
            sdk.put("created", stats.created());
            sdk.put("duplicates", stats.duplicates());
            sdk.put("retried", stats.retried());
            sdk.put("queued", stats.queued());
            sdk.put("droppedTotal", stats.droppedTotal());
            Map<String, Long> dropped = new LinkedHashMap<>();
            stats.dropped().forEach((reason, value) -> dropped.put(reason.name(), value));
            sdk.put("dropped", dropped);
            result.put("sdk", sdk);
        });
        return result;
    }
}
