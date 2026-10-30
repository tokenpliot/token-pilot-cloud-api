package com.tokenledgercloud.api.domain.apikey;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {
    // The auth filter reads the member after the transaction closes, so it must be loaded here, not lazily.
    @EntityGraph(attributePaths = "member")
    Optional<ApiKey> findByHashedKeyAndIsActiveTrue(String hashedKey);
    List<ApiKey> findByMemberId(String memberId);
    long countByMemberId(String memberId);
}
