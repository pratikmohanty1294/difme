-- Idempotent roles and databases on YugabyteDB YSQL for every component that uses it (run after each sync).
-- Codespace: YSQL auth is off (in-cluster traffic is mTLS via the mesh); production enables auth + OpenBao dynamic creds.
DO $$
DECLARE r text;
BEGIN
  FOREACH r IN ARRAY ARRAY['difme','difme_reader','difme_bulk','debezium',
                           'superset','feast','trino'] LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      EXECUTE format('CREATE ROLE %I LOGIN', r);
    END IF;
  END LOOP;
END $$;
ALTER ROLE debezium REPLICATION;
