-- T-050: personal data of an order is cleared 2 years after the order ended (DATABASE.md §6).
-- The job runs: UPDATE customer_order SET <personal columns> = placeholder/NULL, anonymised_at = ?
--   WHERE id = ? AND anonymised_at IS NULL AND status IN (<terminal>) AND COALESCE(completed_at, updated_at) < ?
-- so a re-run skips rows that are already done. NOT NULL / CHECK columns keep a fixed placeholder; no constraint changes.
ALTER TABLE customer_order ADD COLUMN anonymised_at timestamptz;
