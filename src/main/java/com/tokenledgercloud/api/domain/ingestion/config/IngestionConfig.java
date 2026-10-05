package com.tokenledgercloud.api.domain.ingestion.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionConfig {
}
