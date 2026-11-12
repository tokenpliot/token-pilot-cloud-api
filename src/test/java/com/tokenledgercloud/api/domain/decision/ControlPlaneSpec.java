package com.tokenledgercloud.api.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/** docs/api/control-plane-v1.yaml의 스키마로 실제 응답을 검증하는 테스트 도우미. */
final class ControlPlaneSpec {

	private static final JsonNode SPEC = load();
	private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
	private static final SchemaValidatorsConfig CONFIG = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
	private static final ObjectMapper JSON = new ObjectMapper();

	private ControlPlaneSpec() {
	}

	private static JsonNode load() {
		try {
			return new ObjectMapper(new YAMLFactory()).readTree(Path.of("docs/api/control-plane-v1.yaml").toFile());
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	/** 응답 본문이 {@code components/schemas/<schemaName>}을 만족하는지 확인한다. */
	static JsonNode assertValid(String schemaName, String body) {
		JsonNode value;
		try {
			value = JSON.readTree(body);
		} catch (IOException exception) {
			throw new AssertionError("Response body is not JSON: " + body, exception);
		}
		ObjectNode root = SPEC.deepCopy();
		root.put("$ref", "#/components/schemas/" + schemaName);
		JsonSchema schema = FACTORY.getSchema(root, CONFIG);
		Set<ValidationMessage> errors = schema.validate(value);
		assertThat(errors).as("%s violations for %s", schemaName, body).isEmpty();
		return value;
	}
}
