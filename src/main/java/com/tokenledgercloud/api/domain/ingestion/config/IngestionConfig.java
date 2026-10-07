package com.tokenledgercloud.api.domain.ingestion.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.tokenledgercloud.api.domain.ingestion.validation.IngestionMetadataPolicy;

@Configuration
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionConfig {

	@Bean
	IngestionMetadataPolicy ingestionMetadataPolicy(IngestionProperties properties) {
		return new IngestionMetadataPolicy(properties);
	}
}
