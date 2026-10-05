package com.tokenledgercloud.api.domain.ingestion.dto;

import java.util.List;

/**
 * {@code acceptedCount} keeps its original meaning (created plus duplicates); {@code createdCount},
 * {@code duplicateCount} and {@code items} are additions.
 */
public record IngestionBatchResponse(
	int acceptedCount,
	int rejectedCount,
	List<RejectedIngestionItemResponse> rejectedItems,
	int createdCount,
	int duplicateCount,
	List<IngestionBatchItemResponse> items
) {

	public IngestionBatchResponse(
		int acceptedCount,
		int rejectedCount,
		List<RejectedIngestionItemResponse> rejectedItems
	) {
		this(acceptedCount, rejectedCount, rejectedItems, acceptedCount, 0, List.of());
	}
}
