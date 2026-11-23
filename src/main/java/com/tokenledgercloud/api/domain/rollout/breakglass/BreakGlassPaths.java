package com.tokenledgercloud.api.domain.rollout.breakglass;

import com.tokenledgercloud.api.domain.ingestion.web.IngestionPaths;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 프로젝트 API 키로 인증하는 break-glass 경로. (이슈 #19)
 * 회원 api_keys 필터(ApiKeyAuthenticationFilter)가 이 경로의 X-API-Key를 회원 키로 가로채지 않도록 판별한다.
 * 판정·수집 API와 같이 Spring MVC가 매칭하는 방식으로 정규화한 경로를 비교하므로 %61ctive 같은 철자도 같게 본다.
 */
public final class BreakGlassPaths {

	public static final String ACTIVE_PATH = "/api/rollout/break-glass/active";

	private BreakGlassPaths() {
	}

	/** SDK의 유효 조회(GET /active)인지. 다른 break-glass API는 회원 JWT를 쓴다. */
	public static boolean isProjectKeyGet(HttpServletRequest request) {
		return "GET".equals(request.getMethod()) && ACTIVE_PATH.equals(IngestionPaths.normalizedPath(request));
	}
}
