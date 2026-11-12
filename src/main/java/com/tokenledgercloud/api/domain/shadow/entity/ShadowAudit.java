package com.tokenledgercloud.api.domain.shadow.entity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Append-only simulation and rollout audit. Never drives customer enforcement. */
@Entity
@Table(name = "shadow_audits", uniqueConstraints = {
    @UniqueConstraint(name = "uk_shadow_idempotency", columnNames = {"organization_id", "project_id", "kind", "key_hash"}),
    @UniqueConstraint(name = "uk_shadow_rollout_revision", columnNames = {"organization_id", "project_id", "kind", "environment_key", "revision"})
})
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShadowAudit {
    public enum Kind { RUN, ROLLOUT }
    @Id @Column(length = 36) private String id;
    @Column(name = "organization_id", nullable = false, length = 36) private String organizationId;
    @Column(name = "project_id", nullable = false, length = 36) private String projectId;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20) private Kind kind;
    @Column(name = "environment_key", nullable = false, length = 20) private String environmentKey;
    @Column(name = "key_hash", nullable = false, length = 64) private String keyHash;
    @Column(nullable = false, length = 64) private String fingerprint;
    private Long revision;
    @Column(name = "actor_id", nullable = false, length = 128) private String actorId;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "payload_json", nullable = false, columnDefinition = "json")
    private String payloadJson;

    public static ShadowAudit create(String id, String org, String project, Kind kind, String environment,
        String keyHash, String fingerprint, Long revision, String actor, String payload) {
        ShadowAudit a = new ShadowAudit();
        a.id = id; a.organizationId = org; a.projectId = project; a.kind = kind;
        a.environmentKey = environment == null ? "" : environment;
        a.keyHash = keyHash; a.fingerprint = fingerprint; a.revision = revision;
        a.actorId = actor; a.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        a.payloadJson = payload;
        return a;
    }
}
