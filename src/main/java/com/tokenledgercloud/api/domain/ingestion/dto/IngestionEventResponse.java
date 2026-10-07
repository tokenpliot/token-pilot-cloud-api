package com.tokenledgercloud.api.domain.ingestion.dto;

public record IngestionEventResponse(
	String eventId,
	boolean accepted,
	boolean duplicate,
	String requestId
) {

	public IngestionEventResponse(String eventId, boolean accepted) {
		this(eventId, accepted, false, null);
	}
}
