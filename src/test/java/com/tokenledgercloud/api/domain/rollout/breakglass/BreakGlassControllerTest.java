package com.tokenledgercloud.api.domain.rollout.breakglass;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassGrantResponse;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassUsageResponse;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import com.tokenledgercloud.api.global.exception.GlobalExceptionHandler;

/** 입력 검증과 응답 형식만 확인한다. 권한·규칙은 BreakGlassServiceTest가 맡는다. */
@ExtendWith(MockitoExtension.class)
class BreakGlassControllerTest {

	@Mock
	private BreakGlassService breakGlassService;

	@Mock
	private BreakGlassUsageService breakGlassUsageService;

	@InjectMocks
	private BreakGlassController controller;

	private MockMvc mockMvc() {
		return MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	private static final String VALID_BODY = """
			{"projectId": "p-1", "environment": "prod", "reason": "provider 장애 대응", "durationMinutes": 30}
			""";

	@Test
	void issueReturns201WithGrant() throws Exception {
		OffsetDateTime issuedAt = OffsetDateTime.of(2026, 11, 10, 9, 0, 0, 0, ZoneOffset.UTC);
		given(breakGlassService.issue(any(), any(), any()))
				.willReturn(new BreakGlassGrantResponse("g-1", "p-1", "prod", BreakGlassStatus.ACTIVE,
						"provider 장애 대응", "operator", issuedAt, issuedAt.plusMinutes(30), null, null, null));

		mockMvc().perform(post("/api/rollout/break-glass")
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.grantId").value("g-1"))
				.andExpect(jsonPath("$.data.status").value("ACTIVE"))
				.andExpect(jsonPath("$.data.expiresAt").value("2026-11-10T09:30:00Z"));
	}

	@Test
	void usageReturnsWithinAndOutsideWindowSummaries() throws Exception {
		OffsetDateTime issuedAt = OffsetDateTime.of(2026, 11, 10, 9, 0, 0, 0, ZoneOffset.UTC);
		var within = new BreakGlassUsageResponse.Summary(2, 300, new BigDecimal("0.250000"), 1, 0,
				BigDecimal.ZERO, 0, Map.of("SUCCEEDED", 2L));
		var outside = new BreakGlassUsageResponse.Summary(0, 0, BigDecimal.ZERO, 0, 0,
				BigDecimal.ZERO, 0, Map.of());
		given(breakGlassUsageService.getUsage(any(), eq("g-1"), any()))
				.willReturn(new BreakGlassUsageResponse("g-1", "p-1", "prod", BreakGlassStatus.ACTIVE, issuedAt,
						issuedAt.plusMinutes(30), within, outside, issuedAt.plusMinutes(10)));

		mockMvc().perform(get("/api/rollout/break-glass/g-1/usage"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.withinWindow.events").value(2))
				.andExpect(jsonPath("$.data.withinWindow.actualCostUsd").value(0.25))
				.andExpect(jsonPath("$.data.withinWindow.callStatusCounts.SUCCEEDED").value(2))
				.andExpect(jsonPath("$.data.windowEnd").value("2026-11-10T09:30:00Z"));
	}

	@Test
	void serviceRoleRejectionIsReturnedAs403() throws Exception {
		given(breakGlassService.issue(any(), any(), any())).willThrow(new ApiException(ErrorCode.FORBIDDEN));

		mockMvc().perform(post("/api/rollout/break-glass")
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_BODY))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("COMMON-403"));
	}

	@Test
	void missingReasonOrDurationOver24HoursIsRejected() throws Exception {
		for (String body : new String[] {
				"""
				{"projectId": "p-1", "environment": "prod", "reason": " ", "durationMinutes": 30}
				""",
				"""
				{"projectId": "p-1", "environment": "prod", "reason": "사유", "durationMinutes": 1441}
				""",
				"""
				{"projectId": "p-1", "environment": "prod", "reason": "사유"}
				"""}) {
			mockMvc().perform(post("/api/rollout/break-glass")
							.contentType(MediaType.APPLICATION_JSON)
							.content(body))
					.andExpect(status().isBadRequest());
		}

		verifyNoInteractions(breakGlassService);
	}
}
