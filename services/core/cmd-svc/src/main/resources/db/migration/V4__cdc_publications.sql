-- CDC publications for the Debezium connectors (deploy/platform/streaming/debezium/connectors.yaml).
-- Created here by the table owner: CREATE PUBLICATION ... FOR TABLE requires table ownership, which the
-- debezium role doesn't have (and shouldn't). The connectors run with publication.autocreate.mode=disabled.
CREATE PUBLICATION dbz_core_outbox FOR TABLE core.outbox;
CREATE PUBLICATION dbz_settlements FOR TABLE settlement.reservation, settlement.instruction;

-- Read access for snapshots and schema discovery; replication itself uses the REPLICATION attribute (db-init).
GRANT USAGE ON SCHEMA core, settlement TO debezium;
GRANT SELECT ON core.outbox, settlement.reservation, settlement.instruction TO debezium;
