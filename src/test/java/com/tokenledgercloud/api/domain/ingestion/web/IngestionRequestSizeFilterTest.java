package com.tokenledgercloud.api.domain.ingestion.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.tokenledgercloud.api.domain.ingestion.config.IngestionProperties;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;

class IngestionRequestSizeFilterTest {

	private static final int EVENT_LIMIT = 100;
	private static final int BATCH_LIMIT = 200;

	private final IngestionRequestSizeFilter filter =
		new IngestionRequestSizeFilter(new IngestionProperties(EVENT_LIMIT, BATCH_LIMIT, List.of(), false));

	/** Records what the next filter in the chain sees. */
	private static final class Chain implements jakarta.servlet.FilterChain {
		boolean called;
		HttpServletRequest seen;
		String body;

		@Override
		public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
			throws IOException {
			called = true;
			seen = (HttpServletRequest) request;
			body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static MockHttpServletRequest post(String path, String body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
		request.setContentType("application/json");
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}

	/** A request whose length is not declared (chunked); fails if the body is read when it must not be. */
	private static MockHttpServletRequest chunked(String path, String body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", path) {
			@Override
			public long getContentLengthLong() {
				return -1;
			}

			@Override
			public int getContentLength() {
				return -1;
			}
		};
		request.setContentType("application/json");
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}

	@Test
	void bodyWithinTheLimitReachesTheNextFilterUnchanged() throws Exception {
		String body = "{\"a\":\"" + "x".repeat(50) + "\"}";
		Chain chain = new Chain();

		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(post("/api/ingestion/events", body), response, chain);

		assertThat(chain.called).isTrue();
		assertThat(chain.body).isEqualTo(body);
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void bodyExactlyAtTheLimitIsAcceptedAndOneByteMoreIsRejected() throws Exception {
		Chain atLimit = new Chain();
		filter.doFilter(post("/api/ingestion/events", "x".repeat(EVENT_LIMIT)), new MockHttpServletResponse(), atLimit);
		Chain over = new Chain();
		MockHttpServletResponse rejected = new MockHttpServletResponse();
		filter.doFilter(post("/api/ingestion/events", "x".repeat(EVENT_LIMIT + 1)), rejected, over);

		assertThat(atLimit.called).isTrue();
		assertThat(over.called).isFalse();
		assertThat(rejected.getStatus()).isEqualTo(413);
	}

	@Test
	void declaredLengthOverTheLimitIsRejectedWithoutReadingTheBody() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ingestion/events") {
			@Override
			public ServletInputStream getInputStream() {
				throw new AssertionError("the body must not be read when Content-Length is already over the limit");
			}

			@Override
			public long getContentLengthLong() {
				return EVENT_LIMIT + 1;
			}
		};
		Chain chain = new Chain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		assertThat(chain.called).isFalse();
		assertThat(response.getStatus()).isEqualTo(413);
	}

	@Test
	void chunkedBodyOverTheLimitIsRejectedAndWithinTheLimitPasses() throws Exception {
		Chain over = new Chain();
		MockHttpServletResponse rejected = new MockHttpServletResponse();
		filter.doFilter(chunked("/api/ingestion/events", "x".repeat(EVENT_LIMIT + 1)), rejected, over);

		Chain within = new Chain();
		filter.doFilter(chunked("/api/ingestion/events", "y".repeat(EVENT_LIMIT)), new MockHttpServletResponse(),
			within);

		assertThat(over.called).isFalse();
		assertThat(rejected.getStatus()).isEqualTo(413);
		assertThat(within.called).isTrue();
		assertThat(within.body).isEqualTo("y".repeat(EVENT_LIMIT));
	}

	@Test
	void rejectionIsTheStandardEnvelopeAndNeverEchoesTheRequest() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(post("/api/ingestion/events", "SECRET-PROMPT-" + "x".repeat(EVENT_LIMIT)), response,
			new Chain());

		assertThat(response.getStatus()).isEqualTo(413);
		assertThat(response.getContentType()).startsWith("application/json");
		String json = response.getContentAsString();
		assertThat(json).contains("\"success\":false", "\"code\":\"INGESTION-413\"", "100 bytes", "\"errors\":[]");
		assertThat(json).doesNotContain("SECRET-PROMPT");
	}

	@Test
	void batchEndpointUsesItsOwnLimit() throws Exception {
		String body = "x".repeat(150);
		Chain event = new Chain();
		MockHttpServletResponse eventResponse = new MockHttpServletResponse();
		filter.doFilter(post("/api/ingestion/events", body), eventResponse, event);
		Chain batch = new Chain();
		MockHttpServletResponse batchResponse = new MockHttpServletResponse();
		filter.doFilter(post("/api/ingestion/events/batch", body), batchResponse, batch);
		Chain batchOver = new Chain();
		MockHttpServletResponse batchOverResponse = new MockHttpServletResponse();
		filter.doFilter(post("/api/ingestion/events/batch", "x".repeat(BATCH_LIMIT + 1)), batchOverResponse, batchOver);

		assertThat(event.called).isFalse();
		assertThat(eventResponse.getStatus()).isEqualTo(413);
		assertThat(batch.called).isTrue();
		assertThat(batchOver.called).isFalse();
		assertThat(batchOverResponse.getStatus()).isEqualTo(413);
	}

	@Test
	void otherPathsAndMethodsAreNeverTouched() throws Exception {
		String huge = "x".repeat(BATCH_LIMIT * 5);
		for (MockHttpServletRequest request : List.of(
			post("/api/members", huge),
			post("/internal/usage-logs", huge),
			post("/api/ingestion/events/other", huge),
			post("/api/ingestion", huge),
			new MockHttpServletRequest("GET", "/api/ingestion/events"),
			new MockHttpServletRequest("PUT", "/api/ingestion/events"))) {
			Chain chain = new Chain();
			MockHttpServletResponse response = new MockHttpServletResponse();

			filter.doFilter(request, response, chain);

			assertThat(chain.called).as(request.getMethod() + " " + request.getRequestURI()).isTrue();
			assertThat(chain.seen).isSameAs(request);
			assertThat(response.getStatus()).isEqualTo(200);
		}
	}

	@Test
	void trailingSlashAndContextPathAreHandled() throws Exception {
		Chain slash = new Chain();
		MockHttpServletResponse slashResponse = new MockHttpServletResponse();
		filter.doFilter(post("/api/ingestion/events/", "x".repeat(EVENT_LIMIT + 1)), slashResponse, slash);

		MockHttpServletRequest withContext = post("/app/api/ingestion/events", "x".repeat(EVENT_LIMIT + 1));
		withContext.setContextPath("/app");
		Chain context = new Chain();
		MockHttpServletResponse contextResponse = new MockHttpServletResponse();
		filter.doFilter(withContext, contextResponse, context);

		assertThat(slashResponse.getStatus()).isEqualTo(413);
		assertThat(contextResponse.getStatus()).isEqualTo(413);
		assertThat(slash.called || context.called).isFalse();
	}

	@Test
	void downstreamCanReadTheBodyAsAReaderWithMultibyteCharacters() throws Exception {
		String body = "{\"note\":\"한글 값\"}";
		MockHttpServletRequest request = post("/api/ingestion/events", body);
		request.setCharacterEncoding("UTF-8");
		AtomicReference<String> read = new AtomicReference<>();
		AtomicReference<Long> length = new AtomicReference<>();

		filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
			read.set(req.getReader().readLine());
			length.set(req.getContentLengthLong());
		});

		assertThat(read.get()).isEqualTo(body);
		assertThat(length.get()).isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
	}
}
