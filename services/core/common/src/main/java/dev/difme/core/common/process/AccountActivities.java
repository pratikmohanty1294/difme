package dev.difme.core.common.process;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** Account steps hosted by cmd-svc (it owns the TigerBeetle client). Idempotent: account id derives from the business key. */
@ActivityInterface
public interface AccountActivities {
    String TASK_QUEUE = "accounts-activities";

    @ActivityMethod
    String createLedgerAccount(ProvisioningInput input, long dailyLimitMinor);

    @ActivityMethod
    long dailyLimit(String channel, String riskLevel);
}
