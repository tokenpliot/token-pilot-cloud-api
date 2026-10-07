package com.tokenledgercloud.api.domain.ingestion.dto;

public record RejectedIngestionItemResponse(
	int index,
	String requestId,
	String code,
	String message,
	boolean retryable
) {

	public RejectedIngestionItemResponse(int index, String requestId, String code, String message) {
		this(index, requestId, code, message, false);
	}
}
