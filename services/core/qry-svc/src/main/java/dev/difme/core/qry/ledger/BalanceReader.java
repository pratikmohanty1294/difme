package dev.difme.core.qry.ledger;

import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.Client;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.UInt128;
import dev.difme.core.consistency.Consistency;
import dev.difme.core.consistency.ConsistencyProfile;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Balances are STRICT reads straight from TigerBeetle — never cached (PDF §14). */
@ApplicationScoped
public class BalanceReader {
    public record Balance(String accountId, long postedMinor, long pendingMinor) {}

    private final Client client;

    public BalanceReader(@ConfigProperty(name = "difme.tigerbeetle.cluster-id") long clusterId,
                         @ConfigProperty(name = "difme.tigerbeetle.addresses") String addresses) {
        this.client = new Client(UInt128.asBytes(clusterId), addresses.split(","));
    }

    @ConsistencyProfile(Consistency.STRICT)
    public Optional<Balance> balance(UUID accountId) {
        IdBatch ids = new IdBatch(1);
        ids.add(UInt128.asBytes(accountId.getLeastSignificantBits(), accountId.getMostSignificantBits()));
        AccountBatch accounts = client.lookupAccounts(ids);
        if (!accounts.next()) return Optional.empty();
        long posted = accounts.getCreditsPosted(UInt128.LeastSignificant) - accounts.getDebitsPosted(UInt128.LeastSignificant);
        long pending = accounts.getCreditsPending(UInt128.LeastSignificant) - accounts.getDebitsPending(UInt128.LeastSignificant);
        return Optional.of(new Balance(accountId.toString(), posted, pending));
    }
}
