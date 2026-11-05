package com.tokenledgercloud.api.domain.shadow.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import com.tokenledgercloud.api.domain.accounting.entity.UsageAccountingValue;
import com.tokenledgercloud.api.domain.accounting.snapshot.PricingSnapshot;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;

public interface ShadowLedgerRepository extends Repository<UsageLog, String> {
    interface Row {
        UsageLog getUsage();
        UsageAccountingValue getAccounting();
        PricingSnapshot getPrice();
    }

    // One statement freezes the latest ACTUAL revision and its immutable price reference for every sampled event.
    @Query("""
        select u as usage, v as accounting, p as price from UsageLog u
        left join UsageAccountingValue v on v.usageEventId = u.id and v.valueKind = 'ACTUAL'
            and v.revision = (select max(v2.revision) from UsageAccountingValue v2
                where v2.usageEventId = u.id and v2.valueKind = 'ACTUAL')
        left join PricingSnapshot p on p.id = v.pricingSnapshotId
        where u.organizationId = :organizationId and u.projectId = :projectId
            and u.occurredAt >= :from and u.occurredAt < :to
        order by u.occurredAt, u.id
        """)
    List<Row> month(String organizationId, String projectId, LocalDateTime from, LocalDateTime to, Pageable page);
}
