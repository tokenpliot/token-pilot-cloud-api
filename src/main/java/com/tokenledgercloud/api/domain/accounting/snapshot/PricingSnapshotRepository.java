package com.tokenledgercloud.api.domain.accounting.snapshot;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 가격 스냅샷 저장소. 스냅샷은 추가만 하므로 삭제/수정용 메서드는 만들지 않는다.
 */
public interface PricingSnapshotRepository extends JpaRepository<PricingSnapshot, String> {

    // (공급자, 모델, 통화, 버전)으로 스냅샷 하나를 찾는다. UNIQUE 제약이 있어서 최대 한 건이다.
    Optional<PricingSnapshot> findByProviderAndModelAndCurrencyAndVersion(
            String provider, String model, String currency, String version);
}
