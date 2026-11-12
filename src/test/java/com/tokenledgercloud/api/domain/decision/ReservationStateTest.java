package com.tokenledgercloud.api.domain.decision;

import static com.tokenledgercloud.api.domain.decision.ReservationState.COMMITTED;
import static com.tokenledgercloud.api.domain.decision.ReservationState.IN_FLIGHT;
import static com.tokenledgercloud.api.domain.decision.ReservationState.RECONCILIATION_REQUIRED;
import static com.tokenledgercloud.api.domain.decision.ReservationState.RELEASED;
import static com.tokenledgercloud.api.domain.decision.ReservationState.RESERVED;
import static com.tokenledgercloud.api.domain.decision.ReservationState.WRITTEN_OFF;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * 예약 상태 전이가 ADR 0001 §6과 control-plane-v1.yaml과 같은지 확인한다.
 */
class ReservationStateTest {

	@Test
	void transitionsFollowTheAdrDiagram() {
		assertThat(RESERVED.next()).containsExactlyInAnyOrder(IN_FLIGHT, RELEASED);
		assertThat(IN_FLIGHT.next()).containsExactlyInAnyOrder(COMMITTED, RECONCILIATION_REQUIRED, RELEASED);
		assertThat(RECONCILIATION_REQUIRED.next()).containsExactlyInAnyOrder(COMMITTED, WRITTEN_OFF);
		// 종료 상태에서는 어디로도 가지 못한다.
		assertThat(COMMITTED.next()).isEmpty();
		assertThat(RELEASED.next()).isEmpty();
		assertThat(WRITTEN_OFF.next()).isEmpty();
		// 미확인 예약을 해제(0원)로 바로 닫을 수는 없다.
		assertThat(RECONCILIATION_REQUIRED.canTransitionTo(RELEASED)).isFalse();
	}

	@Test
	void terminalStatesAndBudgetHoldsAreDisjoint() {
		for (ReservationState state : ReservationState.values()) {
			// 종료 상태는 예산을 잡고 있지 않고, 종료가 아닌 상태는 모두 예산을 잡고 있다. (미확인 포함)
			assertThat(state.holdsBudget()).as(state.name()).isEqualTo(!state.isTerminal());
		}
	}

	@Test
	void javaEnumMatchesTheOpenApiContract() throws IOException {
		JsonNode spec = new ObjectMapper(new YAMLFactory()).readTree(Path.of("docs/api/control-plane-v1.yaml").toFile());
		List<String> values = new ArrayList<>();
		spec.at("/components/schemas/ReservationState/enum").forEach(value -> values.add(value.asText()));

		assertThat(values).containsExactlyElementsOf(
				Arrays.stream(ReservationState.values()).map(Enum::name).toList());
	}
}
