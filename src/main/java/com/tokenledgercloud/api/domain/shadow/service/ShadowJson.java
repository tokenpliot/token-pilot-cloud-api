package com.tokenledgercloud.api.domain.shadow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public final class ShadowJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private ShadowJson() {}

    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize shadow audit", e); }
    }

    public static <T> T read(String value, Class<T> type) {
        try { return MAPPER.readValue(value, type); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored shadow audit", e); }
    }
}
