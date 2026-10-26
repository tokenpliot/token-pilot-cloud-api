package com.tokenledgercloud.api.domain.policy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class PolicyRequests {
	private PolicyRequests() {}

	public record Replace(
		@NotNull @Min(0) Long expectedVersion,
		@NotNull @Valid PolicyRules rules,
		@NotBlank @Size(max = 500) String reason
	) {}

	public record ImportBudget(@NotBlank @Size(max = 500) String reason) {}

	public record Rollback(
		@NotNull @Min(1) Long expectedVersion,
		@NotNull @Min(1) Long restoreVersion,
		@NotBlank @Size(max = 500) String reason
	) {}
}
