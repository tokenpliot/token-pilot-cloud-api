package com.tokenledgercloud.api.domain.pricing.entity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.hibernate.Length;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "pricing_catalogs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PricingCatalog {

	@Id
	@Column(length = 36)
	private String id;

	@Column(name = "catalog_key", nullable = false, length = 50)
	private String catalogKey;

	@Column(nullable = false, length = 50)
	private String version;

	@Column(nullable = false, length = 20)
	private String format;

	@Column(nullable = false, length = 128)
	private String checksum;

	@Column(name = "is_active", nullable = false)
	private Boolean isActive;

	// V1 declares longtext; without LONG32, Hibernate's MySQL dialect expects tinytext and validate fails at startup.
	@Lob
	@Column(name = "generated_yaml", nullable = false, length = Length.LONG32)
	private String generatedYaml;

	@Column(name = "published_at")
	private LocalDateTime publishedAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@PrePersist
	void prePersist() {
		if (id == null || id.isBlank()) {
			id = UUID.randomUUID().toString();
		}

		if (format == null) {
			format = "yaml";
		}

		if (isActive == null) {
			isActive = true;
		}

		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

		if (createdAt == null) {
			createdAt = now;
		}

		if (updatedAt == null) {
			updatedAt = now;
		}
	}

	@PreUpdate
	void preUpdate() {
		updatedAt = LocalDateTime.now(ZoneOffset.UTC);
	}
}