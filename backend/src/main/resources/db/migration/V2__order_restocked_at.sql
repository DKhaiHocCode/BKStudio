-- T-027: forfeited-to-stock puts the figure back on sale at most once.
-- The endpoint runs: UPDATE customer_order SET restocked_at = ? WHERE id = ? AND restocked_at IS NULL AND status = 'FORFEITED'
-- and only increments stock_qty when that update affects 1 row.
ALTER TABLE customer_order ADD COLUMN restocked_at timestamptz;

ALTER TABLE customer_order
    ADD CONSTRAINT chk_order_restocked_only_forfeited CHECK ((restocked_at IS NOT NULL) <= (status = 'FORFEITED'));
