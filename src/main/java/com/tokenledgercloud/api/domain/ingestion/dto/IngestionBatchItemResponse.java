package com.tokenledgercloud.api.domain.ingestion.dto;

/**
 * Per-item outcome of a batch, listed in request order. {@code usageEventId} is set for CREATED and DUPLICATE;
 * {@code code}/{@code message} for REJECTED. {@code retryable} tells the client whether re-sending the same
 * item can succeed (re-sending is always safe: the idempotency key turns it into a DUPLICATE).
 */
public record IngestionBatchItemResponse(
	int index,
	String requestId,
	IngestionItemStatus status,
	String usageEventId,
	String code,
	String message,
	boolean retryable
) {
}
