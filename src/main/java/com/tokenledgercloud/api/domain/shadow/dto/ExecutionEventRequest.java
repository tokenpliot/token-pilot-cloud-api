package com.tokenledgercloud.api.domain.shadow.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tokenledgercloud.api.domain.decision.*;
import com.tokenledgercloud.api.domain.shadow.dto.ShadowModels.*;

/** Unknown values (including prompt) are discarded immediately and the request is rejected. */
public class ExecutionEventRequest {
    public String schemaVersion;
    public String requestId;
    public String environment;
    public String decisionId;
    public DecisionOutcome reportedDecision;
    public ActualExecution actualExecution;
    public CustomerOverride customerOverride;
    public EnforcementMode customerMode;
    public String occurredAt;
    public String reasonCode;
    @JsonIgnore private boolean unknownField;
    @JsonAnySetter public void unknown(String name, Object ignored) { unknownField = true; }
    public boolean hasUnknownFields() { return unknownField; }
    public ExecutionCommand command() {
        return new ExecutionCommand(schemaVersion, requestId, environment, decisionId, reportedDecision, actualExecution,
            customerOverride, customerMode, occurredAt, reasonCode);
    }
}
