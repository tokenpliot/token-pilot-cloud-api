package com.tokenledgercloud.api.domain.accounting.snapshot;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 가격 스냅샷의 트랜잭션 단위. 별도 빈으로 둬서 호출이 항상 트랜잭션 프록시를 거치게 한다.
 */
// 스프링 빈으로 등록한다.
@Component
// final 필드를 받는 생성자를 롬복이 만들어 준다.
@RequiredArgsConstructor
public class PricingSnapshotTx {

	// 가격 스냅샷 저장소.
	private final PricingSnapshotRepository repository;

	// 새 트랜잭션에서 저장한다. 중복 키 오류가 호출자의 트랜잭션을 망가뜨리지 않게 하려는 것이다.
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public PricingSnapshot insert(PricingSnapshot snapshot) {
		return repository.saveAndFlush(snapshot);
	}

	// 새 트랜잭션에서 읽는다. 동시에 들어온 다른 요청이 방금 커밋한 행도 보려면 새 트랜잭션이어야 한다.
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<PricingSnapshot> find(String provider, String model, String currency, String version) {
		return repository.findByProviderAndModelAndCurrencyAndVersion(provider, model, currency, version);
	}
}
