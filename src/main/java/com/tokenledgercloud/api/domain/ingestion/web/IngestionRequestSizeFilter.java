package com.tokenledgercloud.api.domain.ingestion.web;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import com.tokenledgercloud.api.domain.ingestion.config.IngestionProperties;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rejects oversized ingestion bodies with 413 before authentication or JSON parsing.
 *
 * <p>A declared {@code Content-Length} over the limit is refused without reading the body. Otherwise at most
 * limit+1 bytes are read (so chunked requests are bounded too) and handed on unchanged, which keeps memory use
 * within the configured limit. Only the two ingestion POST endpoints are affected.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class IngestionRequestSizeFilter extends OncePerRequestFilter {

	static final String EVENT_PATH = "/api/ingestion/events";
	static final String BATCH_PATH = "/api/ingestion/events/batch";

	private static final UrlPathHelper URL_PATH_HELPER = UrlPathHelper.defaultInstance;

	private final IngestionProperties properties;

	public IngestionRequestSizeFilter(IngestionProperties properties) {
		this.properties = properties;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"POST".equalsIgnoreCase(request.getMethod()) || limitFor(request) < 0;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		int limit = limitFor(request);

		if (request.getContentLengthLong() > limit) {
			reject(response, limit);
			return;
		}

		byte[] body = request.getInputStream().readNBytes(limit + 1);
		if (body.length > limit) {
			reject(response, limit);
			return;
		}

		chain.doFilter(new CachedBodyRequest(request, body), response);
	}

	/** The byte limit for this request's path, or -1 when the path is not an ingestion endpoint. */
	private int limitFor(HttpServletRequest request) {
		// Compare the path the way Spring MVC will match it: percent-decoded, ";param" and "//" removed, dot
		// segments resolved. The raw request URI would let e.g. /api/ingestion/even%74s skip the limit.
		String path = StringUtils.cleanPath(URL_PATH_HELPER.getPathWithinApplication(request));
		if (path.length() > 1 && path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		if (EVENT_PATH.equals(path)) {
			return properties.maxEventBytes();
		}
		if (BATCH_PATH.equals(path)) {
			return properties.maxBatchBytes();
		}
		return -1;
	}

	/** Same envelope as {@code ApiResponse}; the message is fixed text and never echoes the request. */
	private void reject(HttpServletResponse response, int limit) throws IOException {
		ErrorCode errorCode = ErrorCode.PAYLOAD_TOO_LARGE;
		String json = "{\"success\":false,\"code\":\"" + errorCode.getCode() + "\",\"message\":\""
			+ "Request body exceeds the limit of " + limit + " bytes.\",\"data\":null,\"errors\":[],\"timestamp\":\""
			+ LocalDateTime.now() + "\"}";
		response.setStatus(errorCode.getStatus().value());
		response.setContentType("application/json");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(json);
	}

	private static final class CachedBodyRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		CachedBodyRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public ServletInputStream getInputStream() {
			ByteArrayInputStream source = new ByteArrayInputStream(body);
			return new ServletInputStream() {
				@Override
				public int read() {
					return source.read();
				}

				@Override
				public int read(byte[] buffer, int offset, int length) {
					return source.read(buffer, offset, length);
				}

				@Override
				public boolean isFinished() {
					return source.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener listener) {
					throw new UnsupportedOperationException("Asynchronous reads are not supported.");
				}
			};
		}

		@Override
		public BufferedReader getReader() {
			String encoding = getCharacterEncoding();
			Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
			return new BufferedReader(new InputStreamReader(getInputStream(), charset));
		}

		@Override
		public int getContentLength() {
			return body.length;
		}

		@Override
		public long getContentLengthLong() {
			return body.length;
		}
	}
}
