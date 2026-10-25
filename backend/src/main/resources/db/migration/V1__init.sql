-- BKStudio V1: initial schema (from docs/schema-draft.sql, PostgreSQL 16).
-- Conventions: money = integer US cents; time = timestamptz (UTC); ids = bigint identity.
-- Every constraint and index is named: pk_*, uq_*, fk_*, chk_*, idx_*.

-- ============================================================
-- Settings & terms
-- ============================================================
CREATE TABLE app_setting (
    key         text NOT NULL,
    value       text NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_app_setting PRIMARY KEY (key)
);

CREATE TABLE terms_document (
    version       text NOT NULL,                 -- e.g. '2026-11-01'
    content_md    text NOT NULL,
    published_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_terms_document PRIMARY KEY (version)
);

-- ============================================================
-- Catalog
-- ============================================================
CREATE TABLE product (
    id           bigint GENERATED ALWAYS AS IDENTITY,
    slug         text NOT NULL,
    name         text NOT NULL,
    series       text,                               -- franchise, e.g. 'Xenoblade Chronicles 2'
    description  text NOT NULL DEFAULT '',           -- plain text / restricted markdown, sanitized on render
    -- UNLISTED = commission / private product: reachable only by direct link, hidden from catalog, search, sitemap
    status       text NOT NULL DEFAULT 'DRAFT',
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      int NOT NULL DEFAULT 0,             -- optimistic locking (JPA @Version)
    CONSTRAINT pk_product PRIMARY KEY (id),
    CONSTRAINT uq_product_slug UNIQUE (slug),
    CONSTRAINT chk_product_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT chk_product_name_length CHECK (length(name) BETWEEN 1 AND 200),
    CONSTRAINT chk_product_status CHECK (status IN ('DRAFT','ACTIVE','UNLISTED','ARCHIVED'))
);

CREATE TABLE product_image (
    id           bigint GENERATED ALWAYS AS IDENTITY,
    product_id   bigint NOT NULL,
    storage_key  text NOT NULL,                      -- random key prefix in R2; sizes derived from it
    alt_text     text NOT NULL DEFAULT '',
    width        int NOT NULL,
    height       int NOT NULL,
    sort_order   int NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_product_image PRIMARY KEY (id),
    CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE CASCADE,
    CONSTRAINT uq_product_image_storage_key UNIQUE (storage_key),
    CONSTRAINT chk_product_image_width_positive CHECK (width > 0),
    CONSTRAINT chk_product_image_height_positive CHECK (height > 0)
);
CREATE INDEX idx_product_image_product ON product_image(product_id, sort_order);

-- One variant per scale. A variant can have ready units (stock_qty) AND/OR be made to order.
CREATE TABLE product_variant (
    id                     bigint GENERATED ALWAYS AS IDENTITY,
    product_id             bigint NOT NULL,
    scale                  text NOT NULL,                     -- '1/7', '1/6'
    price_cents            int  NOT NULL,
    stock_qty              int  NOT NULL DEFAULT 0,           -- ready units available to sell
    made_to_order_enabled  boolean NOT NULL DEFAULT false,
    made_to_order_quota    int,                               -- NULL = unlimited; 1 = one-off commission / limited run
    active                 boolean NOT NULL DEFAULT true,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    version                int NOT NULL DEFAULT 0,
    CONSTRAINT pk_product_variant PRIMARY KEY (id),
    CONSTRAINT fk_product_variant_product FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE RESTRICT,
    CONSTRAINT uq_product_variant_product_scale UNIQUE (product_id, scale),
    CONSTRAINT chk_product_variant_scale_format CHECK (scale ~ '^1/[0-9]{1,2}$'),
    CONSTRAINT chk_product_variant_price_positive CHECK (price_cents > 0),
    CONSTRAINT chk_product_variant_stock_nonneg CHECK (stock_qty >= 0),
    CONSTRAINT chk_product_variant_mto_quota_nonneg CHECK (made_to_order_quota >= 0)
);

-- ============================================================
-- Production capacity (slots)
-- ============================================================
CREATE TABLE production_month (
    month       date NOT NULL,                                   -- first day of month
    capacity    int NOT NULL,
    allocated   int NOT NULL DEFAULT 0,
    CONSTRAINT pk_production_month PRIMARY KEY (month),
    CONSTRAINT chk_production_month_first_day CHECK (extract(day FROM month) = 1),
    CONSTRAINT chk_production_month_capacity_nonneg CHECK (capacity >= 0),
    CONSTRAINT chk_production_month_no_overbook CHECK (allocated >= 0 AND allocated <= capacity)  -- DB-level guarantee: never overbook
);

-- ============================================================
-- Orders
-- ============================================================
CREATE TABLE customer_order (
    id                 bigint GENERATED ALWAYS AS IDENTITY,
    public_code        text NOT NULL,                 -- human-friendly, e.g. 'BK-7F3K9Q' (not a secret)
    access_token_hash  bytea NOT NULL,                -- SHA-256 of the secret link token; token itself never stored
    idempotency_key    uuid NOT NULL,                 -- from client; retried checkout returns same order
    fulfillment_type   text NOT NULL,
    status             text NOT NULL,
    -- customer & shipping (minimum needed to ship)
    customer_name      text NOT NULL,
    customer_email     text NOT NULL,
    phone              text,
    ship_line1         text NOT NULL,
    ship_line2         text,
    ship_city          text NOT NULL,
    ship_region        text,
    ship_postal_code   text,
    ship_country       char(2) NOT NULL,              -- ISO 3166-1 alpha-2
    -- money (snapshot at order time)
    subtotal_cents     int NOT NULL,
    shipping_cents     int NOT NULL DEFAULT 0,
    currency           char(3) NOT NULL DEFAULT 'USD',
    -- terms evidence
    terms_version      text NOT NULL,
    terms_accepted_at  timestamptz NOT NULL,
    terms_accepted_ip  inet,
    -- fulfilment
    carrier            text,
    tracking_number    text,
    shipped_at         timestamptz,
    completed_at       timestamptz,
    cancel_reason      text,
    admin_note         text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            int NOT NULL DEFAULT 0,
    CONSTRAINT pk_customer_order PRIMARY KEY (id),
    CONSTRAINT uq_customer_order_public_code UNIQUE (public_code),
    CONSTRAINT uq_customer_order_access_token_hash UNIQUE (access_token_hash),
    CONSTRAINT chk_order_access_token_hash_length CHECK (octet_length(access_token_hash) = 32),
    CONSTRAINT uq_customer_order_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_customer_order_terms FOREIGN KEY (terms_version) REFERENCES terms_document(version),
    CONSTRAINT chk_order_fulfillment_type CHECK (fulfillment_type IN ('IN_STOCK','MADE_TO_ORDER')),
    CONSTRAINT chk_order_status CHECK (status IN (
        'AWAITING_PAYMENT','AWAITING_DEPOSIT','QUEUED','IN_PRODUCTION',
        'AWAITING_BALANCE','PAID_IN_FULL','SHIPPED','COMPLETED',
        'CANCELLED','CANCELLED_DEPOSIT_KEPT','CANCELLED_REFUNDED','FORFEITED')),
    CONSTRAINT chk_order_customer_name_length CHECK (length(customer_name) BETWEEN 1 AND 120),
    CONSTRAINT chk_order_customer_email_format CHECK (customer_email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    CONSTRAINT chk_order_ship_country_format CHECK (ship_country ~ '^[A-Z]{2}$'),
    CONSTRAINT chk_order_subtotal_positive CHECK (subtotal_cents > 0),
    CONSTRAINT chk_order_shipping_nonneg CHECK (shipping_cents >= 0),
    CONSTRAINT chk_order_currency_usd CHECK (currency = 'USD'),
    -- consistency rules
    CONSTRAINT chk_order_in_stock_status CHECK ((fulfillment_type = 'IN_STOCK') <= (status NOT IN ('AWAITING_DEPOSIT','QUEUED','IN_PRODUCTION','AWAITING_BALANCE','CANCELLED_DEPOSIT_KEPT','FORFEITED'))),
    CONSTRAINT chk_order_mto_status CHECK ((fulfillment_type = 'MADE_TO_ORDER') <= (status <> 'AWAITING_PAYMENT')),
    CONSTRAINT chk_order_shipped_needs_tracking CHECK ((status IN ('SHIPPED','COMPLETED')) <= (tracking_number IS NOT NULL AND shipped_at IS NOT NULL))
);
CREATE INDEX idx_customer_order_status_created ON customer_order(status, created_at);
CREATE INDEX idx_customer_order_email_lower ON customer_order(lower(customer_email));

CREATE TABLE order_item (
    id                 bigint GENERATED ALWAYS AS IDENTITY,
    order_id           bigint NOT NULL,
    variant_id         bigint NOT NULL,
    product_name       text NOT NULL,     -- snapshots: order stays correct if product is edited later
    scale              text NOT NULL,
    unit_price_cents   int  NOT NULL,
    quantity           int  NOT NULL,
    CONSTRAINT pk_order_item PRIMARY KEY (id),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES customer_order(id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_item_variant FOREIGN KEY (variant_id) REFERENCES product_variant(id) ON DELETE RESTRICT,
    CONSTRAINT chk_order_item_unit_price_positive CHECK (unit_price_cents > 0),
    CONSTRAINT chk_order_item_quantity_range CHECK (quantity BETWEEN 1 AND 5)
);
CREATE INDEX idx_order_item_order ON order_item(order_id);

-- One row per made-to-order unit = one slot.
CREATE TABLE queue_entry (
    id               bigint GENERATED ALWAYS AS IDENTITY,
    order_item_id    bigint NOT NULL,
    unit_index       int NOT NULL,
    production_month date NOT NULL,
    queued_at        timestamptz NOT NULL DEFAULT now(),   -- FIFO order inside a month
    status           text NOT NULL DEFAULT 'QUEUED',
    CONSTRAINT pk_queue_entry PRIMARY KEY (id),
    CONSTRAINT fk_queue_entry_order_item FOREIGN KEY (order_item_id) REFERENCES order_item(id) ON DELETE RESTRICT,
    CONSTRAINT fk_queue_entry_production_month FOREIGN KEY (production_month) REFERENCES production_month(month),
    CONSTRAINT uq_queue_entry_item_unit UNIQUE (order_item_id, unit_index),
    CONSTRAINT chk_queue_entry_unit_index CHECK (unit_index >= 1),
    CONSTRAINT chk_queue_entry_status CHECK (status IN ('QUEUED','IN_PRODUCTION','DONE','CANCELLED'))
);
CREATE INDEX idx_queue_entry_month_open ON queue_entry(production_month, queued_at) WHERE status IN ('QUEUED','IN_PRODUCTION');

-- ============================================================
-- Payments (ADR-0008)
-- ============================================================
CREATE TABLE payment_request (
    id             bigint GENERATED ALWAYS AS IDENTITY,
    order_id       bigint NOT NULL,
    type           text NOT NULL,
    amount_cents   int  NOT NULL,
    channel        text NOT NULL,
    payment_url    text NOT NULL,
    status         text NOT NULL DEFAULT 'PENDING',
    due_at         timestamptz NOT NULL,
    external_ref   text,                                  -- invoice / order id on the external platform
    confirmed_at   timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    version        int NOT NULL DEFAULT 0,
    CONSTRAINT pk_payment_request PRIMARY KEY (id),
    CONSTRAINT fk_payment_request_order FOREIGN KEY (order_id) REFERENCES customer_order(id) ON DELETE RESTRICT,
    CONSTRAINT chk_payment_request_type CHECK (type IN ('DEPOSIT','BALANCE','FULL')),
    CONSTRAINT chk_payment_request_amount_positive CHECK (amount_cents > 0),
    CONSTRAINT chk_payment_request_channel CHECK (channel IN ('ARTISTREE','SHOPEE_PROXY','OTHER')),
    CONSTRAINT chk_payment_request_url_https CHECK (payment_url ~ '^https://'),
    CONSTRAINT chk_payment_request_status CHECK (status IN ('PENDING','PAID','EXPIRED','CANCELLED')),
    CONSTRAINT chk_payment_request_paid_needs_ref CHECK ((status = 'PAID') = (confirmed_at IS NOT NULL AND external_ref IS NOT NULL))
);
-- At most one open request of each type per order.
CREATE UNIQUE INDEX uq_payment_request_open_per_type ON payment_request(order_id, type) WHERE status = 'PENDING';
-- At most one PAID request of each type per order (no double-counting).
CREATE UNIQUE INDEX uq_payment_request_paid_per_type ON payment_request(order_id, type) WHERE status = 'PAID';
-- The same external invoice cannot be used to confirm two payments.
CREATE UNIQUE INDEX uq_payment_request_channel_external_ref ON payment_request(channel, external_ref) WHERE external_ref IS NOT NULL;
CREATE INDEX idx_payment_request_due_pending ON payment_request(due_at) WHERE status = 'PENDING';

-- ============================================================
-- Email outbox (ADR-0009)
-- ============================================================
CREATE TABLE email_outbox (
    id               bigint GENERATED ALWAYS AS IDENTITY,
    dedupe_key       text NOT NULL,     -- e.g. 'order:42:balance-reminder-7d' -> a job re-run never sends twice
    to_email         text NOT NULL,
    template         text NOT NULL,
    payload          jsonb NOT NULL,
    status           text NOT NULL DEFAULT 'PENDING',
    attempts         int NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    last_error       text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    sent_at          timestamptz,
    CONSTRAINT pk_email_outbox PRIMARY KEY (id),
    CONSTRAINT uq_email_outbox_dedupe_key UNIQUE (dedupe_key),
    CONSTRAINT chk_email_outbox_status CHECK (status IN ('PENDING','SENT','FAILED'))
);
CREATE INDEX idx_email_outbox_pending ON email_outbox(next_attempt_at) WHERE status = 'PENDING';

-- ============================================================
-- Admin & audit (ADR-0006)
-- ============================================================
CREATE TABLE admin_user (
    id                     bigint GENERATED ALWAYS AS IDENTITY,
    email                  text NOT NULL,
    password_hash          text NOT NULL,          -- Argon2id
    totp_secret_encrypted  bytea,                  -- AES-GCM, key from env, never in DB
    totp_enabled           boolean NOT NULL DEFAULT false,
    failed_attempts        int NOT NULL DEFAULT 0,
    locked_until           timestamptz,
    last_login_at          timestamptz,
    created_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_admin_user PRIMARY KEY (id),
    CONSTRAINT chk_admin_user_totp_secret_when_enabled CHECK (totp_enabled = false OR totp_secret_encrypted IS NOT NULL)
);

CREATE UNIQUE INDEX uq_admin_user_email_lower ON admin_user (lower(email));

CREATE TABLE admin_recovery_code (
    id          bigint GENERATED ALWAYS AS IDENTITY,
    admin_id    bigint NOT NULL,
    code_hash   text NOT NULL,
    used_at     timestamptz,
    CONSTRAINT pk_admin_recovery_code PRIMARY KEY (id),
    CONSTRAINT fk_admin_recovery_code_admin FOREIGN KEY (admin_id) REFERENCES admin_user(id) ON DELETE CASCADE
);

CREATE TABLE audit_log (
    id           bigint GENERATED ALWAYS AS IDENTITY,
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    actor        text NOT NULL,
    actor_id     bigint,
    action       text NOT NULL,              -- e.g. 'PAYMENT_CONFIRMED', 'ORDER_TRANSITION'
    entity_type  text NOT NULL,
    entity_id    bigint NOT NULL,
    before       jsonb,
    after        jsonb,
    request_id   text,
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT chk_audit_log_actor CHECK (actor IN ('ADMIN','SYSTEM','CUSTOMER'))
);
CREATE INDEX idx_audit_log_entity ON audit_log(entity_type, entity_id, occurred_at);
