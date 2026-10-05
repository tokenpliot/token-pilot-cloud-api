package com.tokenledgercloud.api.domain.usage.dto;

/** Outcome of an idempotent create: the stored log and whether it already existed. */
public record UsageLogCreateResult(UsageLogResponse log, boolean duplicate) {
}
