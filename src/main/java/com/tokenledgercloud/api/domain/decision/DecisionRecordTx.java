package com.tokenledgercloud.api.domain.decision;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 판정 기록의 저장과 충돌 후 조회를 각각 자기 트랜잭션에서 실행한다(UsageLogWriter와 같은 방식).
 *
 * <p>유니크 위반은 insert 자신의 트랜잭션만 롤백하므로 호출자가 잡고 이어 갈 수 있다. 조회는 새 트랜잭션에서 실행해
 * 동시에 들어와 이긴 요청이 커밋한 행을 본다. 트랜잭션 프록시를 거치도록 별도 빈으로 둔다.
 */
@Component
@RequiredArgsConstructor
public class DecisionRecordTx {

	private final DecisionRecordRepository records;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public DecisionRecord insert(DecisionRecord record) {
		// 커밋 시점이 아니라 여기서 유니크 위반이 나도록 flush한다.
		return records.saveAndFlush(record);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<DecisionRecord> findByKey(String projectId, String idempotencyKey) {
		return records.findByProjectIdAndIdempotencyKey(projectId, idempotencyKey);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<DecisionRecord> findByContext(String policyRequestContextId) {
		return records.findByPolicyRequestContextId(policyRequestContextId);
	}
}
