package com.tokenledgercloud.api.domain.accounting.snapshot;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.tokenledgercloud.api.domain.pricing.entity.PricingPlan;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 가격표(pricing_plans)를 불변 스냅샷으로 복사해 두는 서비스.
 * 같은 버전을 다시 요청하면 새로 만들지 않고 기존 스냅샷을 돌려주고, 같은 버전인데 내용이 다르면 거절한다.
 * 아직 어떤 API나 수집 경로와도 연결하지 않았다.
 */
// 스프링 서비스 빈으로 등록한다.
@Service
// final 필드를 받는 생성자를 롬복이 만들어 준다.
@RequiredArgsConstructor
public class PricingSnapshotService {

	// 트랜잭션 단위 작업을 맡는 빈.
	private final PricingSnapshotTx snapshotTx;

	/**
	 * 가격표를 스냅샷으로 남기고 돌려준다. 이미 같은 스냅샷이 있으면 그것을 돌려준다.
	 *
	 * @param rateUnitTokens 요율의 토큰 단위. 출처를 확인한 값을 넘겨야 한다. (기존 요율을 임의로 100만 토큰당으로 가정하지 않는다)
	 * @throws ApiException 같은 버전에 다른 내용이 오면 IDEMPOTENCY_CONFLICT
	 */
	public PricingSnapshot capture(PricingPlan plan, long rateUnitTokens, String actorType, String actorId) {
		// 저장할 후보를 만든다. 입력이 잘못되면 여기서 예외가 난다.
		PricingSnapshot candidate = PricingSnapshot.fromPlan(plan, rateUnitTokens, actorType, actorId);

		// 1. 같은 (공급자, 모델, 통화, 버전)이 이미 있는지 확인한다.
		Optional<PricingSnapshot> existing = snapshotTx.find(
				candidate.getProvider(), candidate.getModel(), candidate.getCurrency(), candidate.getVersion());
		if (existing.isPresent()) {
			return resolve(existing.get(), candidate);
		}
		try {
			// 2. 없으면 저장한다.
			return snapshotTx.insert(candidate);
		} catch (DataIntegrityViolationException exception) {
			// 3. 동시에 들어온 같은 요청이 먼저 저장했다면 UNIQUE 제약 때문에 여기로 온다. 다시 읽어서 비교한다.
			Optional<PricingSnapshot> raced = snapshotTx.find(
					candidate.getProvider(), candidate.getModel(), candidate.getCurrency(), candidate.getVersion());
			if (raced.isPresent()) {
				return resolve(raced.get(), candidate);
			}
			// 중복이 아닌 다른 무결성 오류는 그대로 던진다.
			throw exception;
		}
	}

	// 이미 저장된 스냅샷과 후보를 비교한다. 내용이 같으면 기존 것을, 다르면 충돌 예외를 낸다.
	private PricingSnapshot resolve(PricingSnapshot stored, PricingSnapshot candidate) {
		if (!stored.getChecksum().equals(candidate.getChecksum())) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		return stored;
	}
}
