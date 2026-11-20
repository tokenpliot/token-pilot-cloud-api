package com.tokenledgercloud.api.domain.decision;

import java.util.Optional;

import org.springframework.data.repository.Repository;

public interface DecisionRecordRepository extends Repository<DecisionRecord, String> {

	DecisionRecord saveAndFlush(DecisionRecord record);

	/** 멱등 범위는 (프로젝트, 판정 엔드포인트)다. 다른 프로젝트의 같은 키는 찾지 않는다. */
	Optional<DecisionRecord> findByProjectIdAndIdempotencyKey(String projectId, String idempotencyKey);

	/** 같은 requestId에 이미 판정이 있는지. context는 (org, project, env, requestId)당 하나다. */
	Optional<DecisionRecord> findByPolicyRequestContextId(String policyRequestContextId);
}
