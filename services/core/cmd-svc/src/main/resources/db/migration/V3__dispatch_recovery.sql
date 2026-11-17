-- ADR-017: how an accepted payment is dispatched, and when dispatch finished. The sweeper re-dispatches payments that
-- are ACCEPTED with dispatched_at NULL (crash or failure between commit and side effect).
ALTER TABLE core.payment ADD COLUMN IF NOT EXISTS dispatch varchar(12);
ALTER TABLE core.payment ADD COLUMN IF NOT EXISTS dispatched_at timestamptz;
CREATE INDEX IF NOT EXISTS payment_undispatched ON core.payment (updated_at) WHERE status = 'ACCEPTED' AND dispatched_at IS NULL;
