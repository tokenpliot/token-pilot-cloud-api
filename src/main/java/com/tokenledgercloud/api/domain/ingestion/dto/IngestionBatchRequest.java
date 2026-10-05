package com.tokenledgercloud.api.domain.ingestion.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import com.tokenledgercloud.api.domain.ingestion.validation.SupportedSchemaVersion;

public record IngestionBatchRequest(
	@NotBlank String projectKey,
	@NotBlank String environment,
	@NotEmpty @Size(max = 100) List<IngestionEventItemRequest> items,
	@SupportedSchemaVersion String schemaVersion
) {

	/** Shape without {@code schemaVersion}; an absent schemaVersion is accepted. */
	public IngestionBatchRequest(String projectKey, String environment, List<IngestionEventItemRequest> items) {
		this(projectKey, environment, items, null);
	}
}
