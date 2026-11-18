package dev.difme.core.cmd.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.DynamicUpdate;
import java.time.Instant;
import java.util.UUID;

/** Write-side aggregate (Hibernate ORM + Panache, @Version optimistic locking — PDF §5.4, §11.3). */
@Entity
// Status changes write only the changed columns. A full-row UPDATE also rewrites the indexed columns (idempotency_key,
// the partial index on status/dispatched_at), which on YugabyteDB adds index tablets to the transaction (measured).
@DynamicUpdate
@Table(name = "payment", schema = "core")
public class PaymentEntity extends PanacheEntityBase {
    @Id @Column(name = "payment_id") public UUID paymentId;
    @Column(name = "idempotency_key", nullable = false, unique = true) public UUID idempotencyKey;
    @Column(name = "debit_account_id", nullable = false) public UUID debitAccountId;
    @Column(name = "credit_account_id", nullable = false) public UUID creditAccountId;
    @Column(name = "amount_minor", nullable = false) public long amountMinor;
    @Column(nullable = false, length = 3) public String currency;
    @Column(nullable = false) public String channel;
    @Column(name = "target_bank") public String targetBank;
    @Column(nullable = false) public String status;
    @Column(name = "workflow_id") public String workflowId;
    @Column(name = "ledger_transfer_id") public String ledgerTransferId;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
    @Column(length = 12) public String dispatch;                  // SAGA | DIRECT (ADR-017)
    @Column(name = "dispatched_at") public Instant dispatchedAt;
    @Version public long version;
}
