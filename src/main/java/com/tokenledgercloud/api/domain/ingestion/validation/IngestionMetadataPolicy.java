package com.tokenledgercloud.api.domain.ingestion.validation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.tokenledgercloud.api.domain.ingestion.config.IngestionProperties;

/**
 * Metadata rules from the Control Plane contract (flat map, at most 16 entries, keys
 * {@code ^[a-z][a-z0-9_.-]{0,63}$}, string values up to 256 chars) plus a forbidden-key list that keeps raw prompt
 * and completion text out. Looks at keys and value shapes only; it never reports or logs a value.
 *
 * <p>Strict mode rejects a forbidden key ({@link #evaluate}); the default mode drops it ({@link #sanitize}).
 */
public final class IngestionMetadataPolicy {

	static final int MAX_ENTRIES = 16;
	static final int MAX_VALUE_LENGTH = 256;
	private static final Pattern KEY_FORMAT = Pattern.compile("^[a-z][a-z0-9_.-]{0,63}$");

	public enum Violation {
		TOO_MANY_ENTRIES,
		INVALID_KEY_FORMAT,
		NON_STRING_VALUE,
		VALUE_TOO_LONG
	}

	/**
	 * @param forbiddenKey the configured forbidden key that matched, or null
	 * @param violations   format violations found (empty when the metadata is well-formed)
	 * @param offending    number of offending entries, for logging
	 */
	public record Evaluation(String forbiddenKey, Set<Violation> violations, int offending) {
	}

	/**
	 * @param metadata the metadata without any forbidden key (null when the input was null or ends up empty)
	 * @param removed  number of forbidden entries dropped, at any depth
	 */
	public record Sanitized(Map<String, Object> metadata, int removed) {
	}

	private final Map<String, String> forbiddenKeysByLowerCase = new HashMap<>();

	public IngestionMetadataPolicy(IngestionProperties properties) {
		for (String key : properties.forbiddenMetadataKeys()) {
			if (key != null && !key.isBlank()) {
				forbiddenKeysByLowerCase.put(key.trim().toLowerCase(Locale.ROOT), key.trim());
			}
		}
	}

	public Evaluation evaluate(Map<String, Object> metadata) {
		if (metadata == null || metadata.isEmpty()) {
			return new Evaluation(null, EnumSet.noneOf(Violation.class), 0);
		}

		String forbidden = findForbiddenKey(metadata);
		if (forbidden != null) {
			return new Evaluation(forbidden, EnumSet.noneOf(Violation.class), 0);
		}

		Set<Violation> violations = EnumSet.noneOf(Violation.class);
		int offending = 0;
		if (metadata.size() > MAX_ENTRIES) {
			violations.add(Violation.TOO_MANY_ENTRIES);
			offending += metadata.size() - MAX_ENTRIES;
		}
		for (Map.Entry<String, Object> entry : metadata.entrySet()) {
			boolean entryOffends = false;
			if (entry.getKey() == null || !KEY_FORMAT.matcher(entry.getKey()).matches()) {
				violations.add(Violation.INVALID_KEY_FORMAT);
				entryOffends = true;
			}
			Object value = entry.getValue();
			if (!(value instanceof String text)) {
				violations.add(Violation.NON_STRING_VALUE);
				entryOffends = true;
			} else if (text.length() > MAX_VALUE_LENGTH) {
				violations.add(Violation.VALUE_TOO_LONG);
				entryOffends = true;
			}
			if (entryOffends) {
				offending++;
			}
		}
		return new Evaluation(null, violations, offending);
	}

	/**
	 * Returns a copy without forbidden keys (case-insensitive, any depth, including maps inside lists). The input is
	 * not modified. A forbidden key is dropped together with its whole value.
	 */
	public Sanitized sanitize(Map<String, Object> metadata) {
		if (metadata == null) {
			return new Sanitized(null, 0);
		}
		if (forbiddenKeysByLowerCase.isEmpty()) {
			return new Sanitized(metadata.isEmpty() ? null : metadata, 0);
		}
		int[] removed = {0};
		Map<String, Object> cleaned = cleanMap(metadata, removed);
		return new Sanitized(cleaned.isEmpty() ? null : cleaned, removed[0]);
	}

	private Map<String, Object> cleanMap(Map<?, ?> map, int[] removed) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			if (entry.getKey() instanceof String key && forbiddenKeysByLowerCase.containsKey(key.toLowerCase(Locale.ROOT))) {
				removed[0]++;
				continue;
			}
			@SuppressWarnings("unchecked")
			Map.Entry<String, Object> typed = (Map.Entry<String, Object>) entry;
			out.put(typed.getKey(), clean(entry.getValue(), removed));
		}
		return out;
	}

	private Object clean(Object node, int[] removed) {
		if (node instanceof Map<?, ?> map) {
			return cleanMap(map, removed);
		}
		if (node instanceof Collection<?> items) {
			List<Object> out = new ArrayList<>(items.size());
			for (Object item : items) {
				out.add(clean(item, removed));
			}
			return out;
		}
		return node;
	}

	/** Searches keys at every depth, including maps inside lists. */
	private String findForbiddenKey(Object node) {
		if (forbiddenKeysByLowerCase.isEmpty()) {
			return null;
		}
		if (node instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (entry.getKey() instanceof String key) {
					String configured = forbiddenKeysByLowerCase.get(key.toLowerCase(Locale.ROOT));
					if (configured != null) {
						return configured;
					}
				}
				String nested = findForbiddenKey(entry.getValue());
				if (nested != null) {
					return nested;
				}
			}
		} else if (node instanceof Collection<?> items) {
			for (Object item : items) {
				String nested = findForbiddenKey(item);
				if (nested != null) {
					return nested;
				}
			}
		}
		return null;
	}
}
