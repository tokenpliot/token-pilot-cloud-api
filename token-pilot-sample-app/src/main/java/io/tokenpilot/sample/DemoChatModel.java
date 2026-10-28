package io.tokenpilot.sample;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class DemoChatModel implements ChatModel {

    private static final Usage DEFAULT_USAGE = new DefaultUsage(8, 3);

    private final AtomicInteger invocationCount = new AtomicInteger();
    private final AtomicBoolean blockNextCall = new AtomicBoolean();

    private volatile RuntimeException nextFailure;
    private volatile boolean usageAvailable = true;
    private volatile CountDownLatch providerEntered = new CountDownLatch(0);
    private volatile CountDownLatch providerRelease = new CountDownLatch(0);

    @Override
    public ChatResponse call(Prompt prompt) {
        invocationCount.incrementAndGet();

        RuntimeException failure = nextFailure;
        if (failure != null) {
            throw failure;
        }

        if (blockNextCall.compareAndSet(true, false)) {
            CountDownLatch entered = providerEntered;
            CountDownLatch release = providerRelease;
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("demo provider release timed out");
                }
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("demo provider wait interrupted", interrupted);
            }
        }

        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
                .model("gpt-4o-2024-08-06");
        if (usageAvailable) {
            metadata.usage(DEFAULT_USAGE);
        }

        return new ChatResponse(
                List.of(new Generation(new org.springframework.ai.chat.messages.AssistantMessage(
                        "Token Pilot demo response"
                ))),
                metadata.build()
        );
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    public int invocationCount() {
        return invocationCount.get();
    }

    public void failNextCall() {
        nextFailure = new IllegalStateException("demo provider failure");
    }

    public void timeoutNextCall() {
        nextFailure = new RuntimeException("demo provider timeout: response indeterminate after deadline");
    }

    public void omitUsageNextCall() {
        usageAvailable = false;
    }

    public void blockNextProviderCall() {
        providerEntered = new CountDownLatch(1);
        providerRelease = new CountDownLatch(1);
        blockNextCall.set(true);
    }

    public boolean awaitProviderEntry(long timeout, TimeUnit unit)
            throws InterruptedException {
        return providerEntered.await(timeout, unit);
    }

    public void releaseProvider() {
        providerRelease.countDown();
    }

    public void reset() {
        releaseProvider();
        invocationCount.set(0);
        blockNextCall.set(false);
        nextFailure = null;
        usageAvailable = true;
        providerEntered = new CountDownLatch(0);
        providerRelease = new CountDownLatch(0);
    }
}