package com.tokenledgercloud.api.domain.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

final class PolicyJson {
	private static final ObjectMapper MAPPER = new ObjectMapper()
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

	private PolicyJson() {}

	static String write(Object value) {
		try {
			return MAPPER.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Cannot serialize policy snapshot", e);
		}
	}

	static PolicyRules rules(String json) {
		try {
			return MAPPER.treeToValue(MAPPER.readTree(json).get("rules"), PolicyRules.class);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Invalid stored policy snapshot", e);
		}
	}
}
