package com.tokenledgercloud.api.domain.shadow.entity;

import java.time.LocalDateTime;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.*;
import com.tokenledgercloud.api.domain.decision.DecisionOutcome;
import com.tokenledgercloud.api.domain.shadow.dto.ShadowModels.*;

@Entity
@Table(name = "decision_execution_events", uniqueConstraints =
    @UniqueConstraint(name = "uk_execution_idempotency", columnNames = {"project_id", "key_hash"}))
@Immutable @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DecisionExecutionEvent {
    @Id @Column(length = 36) private String id;
    @Column(name = "organization_id", nullable = false, length = 36) private String organizationId;
    @Column(name = "project_id", nullable = false, length = 36) private String projectId;
    @Column(nullable = false, length = 20) private String environment;
    @Column(name = "request_id", nullable = false, length = 128) private String requestId;
    @Column(name = "decision_record_id", length = 36) private String decisionRecordId;
    @Column(name = "key_hash", nullable = false, length = 64) private String keyHash;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "reported_decision", nullable = false, length = 30) private DecisionOutcome reportedDecision;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "actual_execution", nullable = false, length = 20) private ActualExecution actualExecution;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "customer_override", nullable = false, length = 20) private CustomerOverride customerOverride;
    @Column(name = "occurred_at", nullable = false) private LocalDateTime occurredAt;
    @Column(name = "recorded_at", nullable = false) private LocalDateTime recordedAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "payload_json", nullable = false, columnDefinition = "json") private String payloadJson;

    public static DecisionExecutionEvent create(ExecutionView v, String org, String project, String decisionRecordId,
        String keyHash, String fingerprint, LocalDateTime occurredAt, LocalDateTime recordedAt, String payload) {
        var e = new DecisionExecutionEvent();
        e.id = v.id(); e.organizationId = org; e.projectId = project; e.environment = v.environment();
        e.requestId = v.requestId(); e.decisionRecordId = decisionRecordId; e.keyHash = keyHash; e.fingerprint = fingerprint;
        e.reportedDecision = v.reportedDecision(); e.actualExecution = v.actualExecution(); e.customerOverride = v.customerOverride();
        e.occurredAt = occurredAt; e.recordedAt = recordedAt; e.payloadJson = payload;
        return e;
    }
}
