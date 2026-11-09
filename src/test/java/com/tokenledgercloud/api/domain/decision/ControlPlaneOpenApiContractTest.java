package com.tokenledgercloud.api.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * docs/api/control-plane-v1.yaml 계약 검증. 예제는 스키마를 만족해야 하고, 오류 응답은 ADR 0001의 매핑을 따라야 한다.
 */
class ControlPlaneOpenApiContractTest {

	private static final Path SPEC = Path.of("docs/api/control-plane-v1.yaml");
	private static final String JSON = "application/json";

	private static JsonNode spec;
	private static JsonSchemaFactory factory;
	private static SchemaValidatorsConfig config;

	private record Example(String location, int status, JsonNode schemaRef, JsonNode value) {
	}

	@BeforeAll
	static void loadSpec() throws IOException {
		spec = new ObjectMapper(new YAMLFactory()).readTree(SPEC.toFile());
		factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
		config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
	}

	@Test
	void everyExampleMatchesItsSchema() {
		List<Example> examples = collectExamples();
		List<String> failures = new ArrayList<>();
		for (Example example : examples) {
			Set<ValidationMessage> errors = schemaFor(example.schemaRef()).validate(example.value());
			errors.forEach(error -> failures.add(example.location() + ": " + error.getMessage()));
		}

		assertThat(examples).as("examples in spec").hasSizeGreaterThanOrEqualTo(15);
		assertThat(failures).isEmpty();
	}

	@Test
	void errorResponseExamplesFollowClientFailureMapping() {
		List<Example> errorExamples = collectExamples().stream()
			.filter(example -> example.status() >= 400)
			.toList();

		assertThat(errorExamples).isNotEmpty();
		for (Example example : errorExamples) {
			assertThat(DecisionOutcome.forErrorStatus(example.status()).isPolicyDenial()).as(example.location()).isFalse();
			JsonNode outcomeNode = example.value().at("/data/outcome");
			if (outcomeNode.isMissingNode()) {
				// 경로 미등록 404만 본문에 outcome이 없다. 클라이언트는 상태코드만으로 판정한다.
				assertThat(example.schemaRef().get("$ref").asText()).as(example.location())
					.isEqualTo("#/components/schemas/PlainErrorEnvelope");
				continue;
			}
			DecisionOutcome outcome = DecisionOutcome.valueOf(outcomeNode.asText());
			assertThat(outcome.isPolicyDenial()).as(example.location()).isFalse();
			assertThat(outcome).as(example.location()).isEqualTo(DecisionOutcome.forErrorStatus(example.status()));
		}
	}

	@Test
	void disabledDecisionApiIsDocumentedAs404MappedToIndeterminate() {
		JsonNode notFound = spec.at("/paths").get("/api/control/v1/decisions").at("/post/responses/404");

		assertThat(notFound.get("$ref").asText()).isEqualTo("#/components/responses/DecisionApiDisabled");
		assertThat(DecisionOutcome.forErrorStatus(404)).isEqualTo(DecisionOutcome.INDETERMINATE);
	}

	@Test
	void retryAtIsDistinctFromOverloadRetryAfter() {
		JsonNode decision = spec.at("/components/schemas/DecisionResponse/properties");

		assertThat(decision.has("retryAt")).isTrue();
		assertThat(decision.has("retryAfter")).isFalse();
		assertThat(decision.has("retryAfterSeconds")).isFalse();
	}

	@Test
	void newDecisionFieldsAreOptionalSoOlderClientsKeepWorking() {
		JsonNode required = spec.at("/components/schemas/DecisionResponse/required");
		List<String> names = new ArrayList<>();
		required.forEach(node -> names.add(node.asText()));

		assertThat(names).doesNotContain("policies", "requiredCostUsd", "remainingMonthlyBudgetUsd", "retryAt");
	}

	@Test
	void reasonCodesProducedByTheEvaluatorAreDocumented() {
		String description = spec.at("/components/schemas/DecisionReason/description").asText();

		assertThat(description).contains("MONTHLY_BUDGET_EXCEEDED", "REQUEST_COST_LIMIT_EXCEEDED",
			"MONTHLY_SOFT_LIMIT_EXCEEDED", "REQUEST_COST_SOFT_LIMIT_EXCEEDED", "POLICY_INACTIVE",
			"POLICY_NOT_CONFIGURED", "PRICING_NOT_FOUND", "PRICING_UNAVAILABLE");
	}

	@Test
	void successfulDecisionCannotCarryControlPlaneUnavailable() {
		JsonNode example = firstExample("/api/control/v1/decisions", "200").deepCopy();
		((ObjectNode) example.at("/data")).put("outcome", DecisionOutcome.CONTROL_PLANE_UNAVAILABLE.name());

		assertThat(schemaFor(ref("DecisionEnvelope")).validate(example)).isNotEmpty();
	}

	@Test
	void errorResponseCannotCarryPolicyDenial() {
		JsonNode example = spec.at("/components/responses/ControlPlaneUnavailable/content")
			.get(JSON).at("/examples/ledgerDown/value").deepCopy();
		((ObjectNode) example.at("/data")).put("outcome", DecisionOutcome.DENY_BUDGET.name());

		assertThat(schemaFor(ref("ErrorEnvelope")).validate(example)).isNotEmpty();
	}

	@Test
	void requestSchemasRejectRawPromptAndCompletionText() {
		ObjectNode decision = requestExample("/api/control/v1/decisions").deepCopy();
		decision.put("prompt", "Summarize this customer's complaint: ...");
		ObjectNode usage = requestExample("/api/control/v1/usage-events").deepCopy();
		usage.put("completion", "Dear customer, ...");

		assertThat(schemaFor(ref("DecisionRequest")).validate(decision)).isNotEmpty();
		assertThat(schemaFor(ref("UsageEvent")).validate(usage)).isNotEmpty();
	}

	@Test
	void outcomeAndModeEnumsMatchJavaContract() {
		assertThat(enumValues("DecisionOutcome")).containsExactlyElementsOf(names(DecisionOutcome.values()));
		assertThat(enumValues("EnforcementMode")).containsExactlyElementsOf(names(EnforcementMode.values()));
		assertThat(spec.at("/components/schemas/EnforcementMode/default").asText())
			.isEqualTo(EnforcementSettings.defaults().mode().name());
		assertThat(enumValues("EvaluatedOutcome")).containsExactlyElementsOf(
			Arrays.stream(DecisionOutcome.values())
				.filter(outcome -> outcome.httpStatus() == 200)
				.map(Enum::name)
				.toList()
		);
		assertThat(enumValues("ErrorOutcome")).containsExactlyInAnyOrder(
			DecisionOutcome.INDETERMINATE.name(),
			DecisionOutcome.CONTROL_PLANE_UNAVAILABLE.name()
		);
	}

	private static List<Example> collectExamples() {
		List<Example> examples = new ArrayList<>();
		spec.get("paths").properties().forEach(path -> path.getValue().properties().forEach(operation -> {
			String location = operation.getKey().toUpperCase() + " " + path.getKey();
			JsonNode requestContent = operation.getValue().at("/requestBody/content").get(JSON);
			if (requestContent != null) {
				addExamples(examples, location + " request", 0, requestContent);
			}
			operation.getValue().get("responses").properties().forEach(response -> {
				JsonNode content = resolve(response.getValue()).at("/content").get(JSON);
				if (content != null) {
					int status = Integer.parseInt(response.getKey());
					addExamples(examples, location + " " + status, status, content);
				}
			});
		}));
		return examples;
	}

	private static void addExamples(List<Example> examples, String location, int status, JsonNode content) {
		JsonNode schemaRef = content.get("schema");
		content.path("examples").properties().forEach(example -> examples.add(
			new Example(location + " #" + example.getKey(), status, schemaRef, example.getValue().get("value"))
		));
	}

	private static JsonNode resolve(JsonNode node) {
		JsonNode ref = node.get("$ref");
		return ref == null ? node : spec.at(ref.asText().substring(1));
	}

	private static JsonSchema schemaFor(JsonNode schemaRef) {
		ObjectNode root = spec.deepCopy();
		root.set("$ref", schemaRef.get("$ref"));
		return factory.getSchema(root, config);
	}

	private static JsonNode ref(String schemaName) {
		return new ObjectMapper().createObjectNode().put("$ref", "#/components/schemas/" + schemaName);
	}

	private static JsonNode firstExample(String path, String status) {
		JsonNode content = resolve(spec.at("/paths").get(path).at("/post/responses").get(status)).at("/content").get(JSON);
		return content.get("examples").elements().next().get("value");
	}

	private static ObjectNode requestExample(String path) {
		JsonNode content = spec.at("/paths").get(path).at("/post/requestBody/content").get(JSON);
		return (ObjectNode) content.get("examples").elements().next().get("value");
	}

	private static List<String> enumValues(String schemaName) {
		List<String> values = new ArrayList<>();
		spec.at("/components/schemas/" + schemaName + "/enum").forEach(value -> values.add(value.asText()));
		return values;
	}

	private static List<String> names(Enum<?>[] values) {
		return Arrays.stream(values).map(Enum::name).collect(Collectors.toList());
	}
}
