-- T-029: production progress shown to the customer of a MADE_TO_ORDER order.
-- Six customer-facing stages; only the three inside IN_PRODUCTION are stored here.
-- The other three (awaiting approval, deposit received, finished) are derived from customer_order.status.
-- NOT enforced by the DB: rows only for fulfillment_type = 'MADE_TO_ORDER' orders, and stages approved in order.
-- A CHECK cannot look at another table; the progress service enforces both (no trigger by design).

-- One row per approved stage. Double approve: INSERT ... ON CONFLICT (order_id, stage) DO NOTHING.
CREATE TABLE order_progress (
    order_id     bigint NOT NULL,
    stage        text NOT NULL,
    note         text NOT NULL DEFAULT '',          -- optional admin note; the fixed stage explanation lives in code
    approved_at  timestamptz NOT NULL DEFAULT now(),
    approved_by  bigint NOT NULL,                   -- admin_user.id; only admins approve stages
    CONSTRAINT pk_order_progress PRIMARY KEY (order_id, stage),
    -- orders are never deleted (RESTRICT, same as order_item / payment_request)
    CONSTRAINT fk_order_progress_order FOREIGN KEY (order_id) REFERENCES customer_order(id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_progress_admin FOREIGN KEY (approved_by) REFERENCES admin_user(id) ON DELETE RESTRICT,
    CONSTRAINT chk_order_progress_stage CHECK (stage IN ('RAW_PRINT','PREP','PAINT')),
    CONSTRAINT chk_order_progress_note_length CHECK (char_length(note) <= 500)
);

-- WIP photos, at most 5 per stage: sort_order 0..4 plus unique (order_id, stage, sort_order).
CREATE TABLE order_progress_image (
    id           bigint GENERATED ALWAYS AS IDENTITY,
    order_id     bigint NOT NULL,
    stage        text NOT NULL,
    storage_key  text NOT NULL,                      -- random key prefix in R2; sizes derived from it
    width        int NOT NULL,
    height       int NOT NULL,
    sort_order   int NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_order_progress_image PRIMARY KEY (id),
    CONSTRAINT fk_order_progress_image_progress FOREIGN KEY (order_id, stage)
        REFERENCES order_progress(order_id, stage) ON DELETE CASCADE,
    CONSTRAINT uq_order_progress_image_storage_key UNIQUE (storage_key),
    CONSTRAINT uq_order_progress_image_slot UNIQUE (order_id, stage, sort_order),
    CONSTRAINT chk_order_progress_image_sort_order CHECK (sort_order BETWEEN 0 AND 4),
    CONSTRAINT chk_order_progress_image_width_positive CHECK (width > 0),
    CONSTRAINT chk_order_progress_image_height_positive CHECK (height > 0)
);
-- Lookup by (order_id, stage) is served by the index behind uq_order_progress_image_slot (leading columns).
