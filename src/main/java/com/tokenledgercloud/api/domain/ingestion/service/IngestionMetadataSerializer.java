package com.tokenledgercloud.api.domain.ingestion.service;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenledgercloud.api.domain.ingestion.validation.IngestionMetadataPolicy;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

/**
 * 수집 요청의 metadata를 usage_events.metadata_json에 넣을 JSON으로 바꾼다. v1 수집과 Observe 수집(v2)이 함께 쓴다.
 * 검증 모드와 상관없이 여기서 금지 키(원문 프롬프트/응답 등)를 떨어뜨리므로 DB에 닿지 않고,
 * 본문 지문도 이 JSON으로 계산하므로 금지 키를 뺀 값으로 계산된다.
 */
// 스프링 빈으로 등록한다.
@Component
public class IngestionMetadataSerializer {

	// 금지 키 목록을 아는 정책 빈.
	private final IngestionMetadataPolicy metadataPolicy;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public IngestionMetadataSerializer(IngestionMetadataPolicy metadataPolicy) {
		this.metadataPolicy = metadataPolicy;
	}

	/**
	 * 금지 키를 뺀 metadata의 JSON. 없거나, 비었거나, 금지 키를 빼고 나니 비었으면 null이다.
	 *
	 * @throws ApiException JSON으로 바꿀 수 없으면 INVALID_INPUT
	 */
	public String toJson(Map<String, Object> metadata) {
		Map<String, Object> cleaned = metadataPolicy.sanitize(metadata).metadata();
		if (cleaned == null || cleaned.isEmpty()) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(cleaned);
		} catch (JsonProcessingException exception) {
			throw new ApiException(ErrorCode.INVALID_INPUT, "metadata must be JSON serializable.");
		}
	}
}
