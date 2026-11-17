package dev.difme.core.common.process;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Input for AccountProvisioningWorkflow, built from the BPMN process variables. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProvisioningInput(String processInstanceId, String businessKey, String accountType, String currency,
                                String riskLevel) {}
