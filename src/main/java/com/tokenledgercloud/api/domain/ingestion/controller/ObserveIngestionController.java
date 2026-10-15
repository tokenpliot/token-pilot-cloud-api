package com.tokenledgercloud.api.domain.ingestion.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tokenledgercloud.api.domain.ingestion.dto.ObserveEventRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.ObserveEventResponse;
import com.tokenledgercloud.api.domain.ingestion.service.ObserveIngestionService;
import com.tokenledgercloud.api.global.response.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Observe 사용 이벤트 수집 API(v2). 기본은 꺼져 있다.
 * token-pilot.observe.ingestion.enabled=true로 켜야 이 컨트롤러가 등록되고, 꺼져 있으면 404가 나온다.
 * 기존 /api/ingestion/** 는 건드리지 않는다.
 */
// 설정으로 켠 경우에만 이 컨트롤러를 등록한다.
@ConditionalOnProperty(prefix = "token-pilot.observe.ingestion", name = "enabled", havingValue = "true")
// JSON을 돌려주는 컨트롤러로 등록한다.
@RestController
// 이 컨트롤러의 공통 경로.
@RequestMapping("/api/v2/observe/events")
// final 필드를 받는 생성자를 롬복이 만들어 준다.
@RequiredArgsConstructor
public class ObserveIngestionController {

	// 프로젝트 API 키를 담는 헤더 이름. 기존 수집 API와 같다.
	private static final String API_KEY_HEADER = "X-API-Key";

	// 수집 서비스.
	private final ObserveIngestionService ingestionService;

	// POST /api/v2/observe/events : 새 이벤트면 201, 이미 수집된 이벤트면 200.
	@PostMapping
	public ResponseEntity<ApiResponse<ObserveEventResponse>> collect(
			@RequestHeader(name = API_KEY_HEADER, required = false) String apiKey,
			@Valid @RequestBody ObserveEventRequest request) {
		ObserveEventResponse response = ingestionService.collect(apiKey, request);
		HttpStatus status = response.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
		String message = response.duplicate() ? "이미 수집된 이벤트" : "관찰 이벤트 수집 성공";
		return ResponseEntity.status(status).body(ApiResponse.success(message, response));
	}
}
