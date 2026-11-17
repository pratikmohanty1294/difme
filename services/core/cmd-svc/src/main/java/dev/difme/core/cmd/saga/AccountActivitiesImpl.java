package dev.difme.core.cmd.saga;

import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateAccountResult;
import com.tigerbeetle.CreateAccountResultBatch;
import com.tigerbeetle.UInt128;
import dev.difme.core.common.process.AccountActivities;
import dev.difme.core.common.process.ProvisioningInput;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

/** Account steps for AccountProvisioningWorkflow. Limits come from the DMN business config via process-gateway. */
@ApplicationScoped
public class AccountActivitiesImpl implements AccountActivities {
    public static final int ACCOUNT_CODE_CUSTOMER = 10;

    @RegisterRestClient(configKey = "process-gateway")
    @Path("/v1/decisions/payment-limits")
    public interface LimitsClient {
        record LimitRequest(String channel, String riskLevel) {}
        record LimitResponse(long dailyLimitMinor) {}
        @POST LimitResponse evaluate(LimitRequest r);
    }

    private final Client client;
    private final int ledger;
    @Inject @RestClient LimitsClient limits;

    public AccountActivitiesImpl(@ConfigProperty(name = "difme.tigerbeetle.cluster-id") long clusterId,
                                 @ConfigProperty(name = "difme.tigerbeetle.addresses") String addresses,
                                 @ConfigProperty(name = "difme.tigerbeetle.ledger") int ledger) {
        this.client = new Client(UInt128.asBytes(clusterId), addresses.split(","));
        this.ledger = ledger;
    }

    @Override
    public long dailyLimit(String channel, String riskLevel) {
        return limits.evaluate(new LimitsClient.LimitRequest(channel, riskLevel)).dailyLimitMinor();
    }

    @Override
    public String createLedgerAccount(ProvisioningInput in, long dailyLimitMinor) {
        UUID id = UUID.nameUUIDFromBytes(("account/" + in.businessKey()).getBytes(StandardCharsets.UTF_8));
        AccountBatch batch = new AccountBatch(1);
        batch.add();
        batch.setId(UInt128.asBytes(id.getLeastSignificantBits(), id.getMostSignificantBits()));
        batch.setLedger(ledger);
        batch.setCode(ACCOUNT_CODE_CUSTOMER);
        CreateAccountResultBatch errors = client.createAccounts(batch);
        while (errors.next()) {
            CreateAccountResult r = errors.getResult();
            if (r != CreateAccountResult.Exists) throw new IllegalStateException("ledger rejected account: " + r.name());
        }
        return id.toString();
    }
}
