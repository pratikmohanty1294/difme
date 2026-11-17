package dev.difme.core.cmd.ledger;

import com.tigerbeetle.Client;
import com.tigerbeetle.CreateTransferResult;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.TransferFlags;
import com.tigerbeetle.UInt128;
import dev.difme.core.consistency.Consistency;
import dev.difme.core.consistency.ConsistencyProfile;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * TigerBeetle double-entry ledger (PDF §8, STRICT). Transfer ids are derived from payment id + step, so a Temporal
 * retry re-submits the same id and TigerBeetle answers "exists" — idempotent by construction (TLA+ Ledger.tla).
 */
@ApplicationScoped
public class LedgerClient {
    public static final int CODE_PAYMENT = 1;

    private final Client client;
    private final int ledger;

    public LedgerClient(@ConfigProperty(name = "difme.tigerbeetle.cluster-id") long clusterId,
                        @ConfigProperty(name = "difme.tigerbeetle.addresses") String addresses,
                        @ConfigProperty(name = "difme.tigerbeetle.ledger") int ledger) {
        this.client = new Client(UInt128.asBytes(clusterId), addresses.split(","));
        this.ledger = ledger;
    }

    @ConsistencyProfile(Consistency.STRICT)
    public String pending(UUID transferId, UUID debit, UUID credit, long amountMinor) {
        return submit(transferId, debit, credit, amountMinor, TransferFlags.PENDING, null);
    }

    @ConsistencyProfile(Consistency.STRICT)
    public String postPending(UUID transferId, UUID pendingId) {
        return submit(transferId, null, null, 0, TransferFlags.POST_PENDING_TRANSFER, pendingId);
    }

    @ConsistencyProfile(Consistency.STRICT)
    public String voidPending(UUID transferId, UUID pendingId) {
        return submit(transferId, null, null, 0, TransferFlags.VOID_PENDING_TRANSFER, pendingId);
    }

    @ConsistencyProfile(Consistency.STRICT)
    public String direct(UUID transferId, UUID debit, UUID credit, long amountMinor) {
        return submit(transferId, debit, credit, amountMinor, TransferFlags.NONE, null);
    }

    private String submit(UUID id, UUID debit, UUID credit, long amount, int flags, UUID pendingId) {
        TransferBatch batch = new TransferBatch(1);
        batch.add();
        batch.setId(bytes(id));
        if (debit != null) batch.setDebitAccountId(bytes(debit));
        if (credit != null) batch.setCreditAccountId(bytes(credit));
        if (pendingId != null) batch.setPendingId(bytes(pendingId));
        batch.setAmount(amount, 0L);
        batch.setLedger(ledger);
        batch.setCode(CODE_PAYMENT);
        batch.setFlags(flags);
        CreateTransferResultBatch errors = client.createTransfers(batch);
        while (errors.next()) {
            CreateTransferResult r = errors.getResult();
            if (r != CreateTransferResult.Exists) throw new LedgerRejected(r.name());
        }
        return id.toString();
    }

    private static byte[] bytes(UUID u) {
        return UInt128.asBytes(u.getLeastSignificantBits(), u.getMostSignificantBits());
    }

    void close(@Observes ShutdownEvent ev) {
        client.close();
    }

    public static final class LedgerRejected extends RuntimeException {
        public LedgerRejected(String reason) { super("ledger rejected transfer: " + reason); }
    }
}
