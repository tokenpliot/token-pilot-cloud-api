package com.tokenledgercloud.api.domain.ingestion.web;

import org.springframework.util.StringUtils;
import org.springframework.web.util.UrlPathHelper;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The ingestion POST endpoints that authenticate with a project API key, and how a request path is compared to them.
 *
 * <p>Every filter that singles out these endpoints must compare paths the same way, or a spelling such as
 * {@code /api/v2/observe/%65vents} reaches the controller while one filter treats it as a different path.
 */
public final class IngestionPaths {

	public static final String EVENT_PATH = "/api/ingestion/events";
	public static final String BATCH_PATH = "/api/ingestion/events/batch";
	public static final String OBSERVE_EVENT_PATH = "/api/v2/observe/events";
	/** Control-plane decision endpoint. It also authenticates with a project API key, but is not an ingestion endpoint. */
	public static final String DECISION_PATH = "/api/control/v1/decisions";

	private static final UrlPathHelper URL_PATH_HELPER = UrlPathHelper.defaultInstance;

	private IngestionPaths() {
	}

	/**
	 * The path as Spring MVC will match it: percent-decoded, ";param" and "//" removed, dot segments resolved, and
	 * without a trailing slash.
	 */
	public static String normalizedPath(HttpServletRequest request) {
		String path = StringUtils.cleanPath(URL_PATH_HELPER.getPathWithinApplication(request));
		if (path.length() > 1 && path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		return path;
	}

	/** Whether this is a POST to one of the ingestion endpoints (v1 single/batch, v2 observe). */
	public static boolean isIngestionPost(HttpServletRequest request) {
		// Case-sensitive like Spring MVC's method matching: "post" never reaches these controllers.
		if (!"POST".equals(request.getMethod())) {
			return false;
		}
		String path = normalizedPath(request);
		return EVENT_PATH.equals(path) || BATCH_PATH.equals(path) || OBSERVE_EVENT_PATH.equals(path);
	}

	/**
	 * Whether this is a POST to an endpoint that authenticates with a project API key in its own service: the ingestion
	 * endpoints and the control-plane decision endpoint. The member api_keys filter must not intercept their X-API-Key.
	 */
	public static boolean isProjectKeyPost(HttpServletRequest request) {
		if (isIngestionPost(request)) {
			return true;
		}
		return "POST".equals(request.getMethod()) && DECISION_PATH.equals(normalizedPath(request));
	}
}
