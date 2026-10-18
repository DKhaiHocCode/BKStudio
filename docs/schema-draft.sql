-- BKStudio — schema draft (Phase 3 design artifact)
-- Target: PostgreSQL 16. Will become Flyway migration V1__init.sql in Phase 4.
-- Conventions: money = integer US cents; time = timestamptz (UTC); ids = bigint identity.

-- ============================================================
-- Settings & terms
-- ============================================================
CREATE TABLE app_setting (
    key         text PRIMARY KEY,
    value       text NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE terms_document (
    version       text PRIMARY KEY,                 -- e.g. '2026-11-01'
    content_md    text NOT NULL,
    published_at  timestamptz NOT NULL DEFAULT now()
);

-- ============================================================
-- Catalog
-- ============================================================
CREATE TABLE product (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug         text NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name         text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    series       text,                               -- franchise, e.g. 'Xenoblade Chronicles 2'
    description  text NOT NULL DEFAULT '',           -- plain text / restricted markdown, sanitized on render
    -- UNLISTED = commission / private product: reachable only by direct link, hidden from catalog, search, sitemap
    status       text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE','UNLISTED','ARCHIVED')),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      int NOT NULL DEFAULT 0               -- optimistic locking (JPA @Version)
);

CREATE TABLE product_image (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id   bigint NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    storage_key  text NOT NULL UNIQUE,               -- random key prefix in R2; sizes derived from it
    alt_text     text NOT NULL DEFAULT '',
    width        int NOT NULL CHECK (width > 0),
    height       int NOT NULL CHECK (height > 0),
    sort_order   int NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX product_image_product_idx ON product_image(product_id, sort_order);

-- One variant per scale. A variant can have ready units (stock_qty) AND/OR be made to order.
CREATE TABLE product_variant (
    id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id             bigint NOT NULL REFERENCES product(id) ON DELETE RESTRICT,
    scale                  text NOT NULL CHECK (scale ~ '^1/[0-9]{1,2}$'),   -- '1/7', '1/6'
    price_cents            int  NOT NULL CHECK (price_cents > 0),
    stock_qty              int  NOT NULL DEFAULT 0 CHECK (stock_qty >= 0),  -- ready units available to sell
    made_to_order_enabled  boolean NOT NULL DEFAULT false,
    made_to_order_quota    int CHECK (made_to_order_quota >= 0),          -- NULL = unlimited; 1 = one-off commission / limited run
    active                 boolean NOT NULL DEFAULT true,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    version                int NOT NULL DEFAULT 0,
    UNIQUE (product_id, scale)
);

-- ============================================================
-- Production capacity (slots)
-- ============================================================
CREATE TABLE production_month (
    month       date PRIMARY KEY CHECK (extract(day FROM month) = 1),   -- first day of month
    capacity    int NOT NULL CHECK (capacity >= 0),
    allocated   int NOT NULL DEFAULT 0,
    CHECK (allocated >= 0 AND allocated <= capacity)                    -- DB-level guarantee: never overbook
);

-- ============================================================
-- Orders
-- ============================================================
CREATE TABLE customer_order (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_code        text NOT NULL UNIQUE,                 -- human-friendly, e.g. 'BK-7F3K9Q' (not a secret)
    access_token_hash  bytea NOT NULL UNIQUE,                -- SHA-256 of the secret link token; token itself never stored
    idempotency_key    uuid NOT NULL UNIQUE,                 -- from client; retried checkout returns same order
    fulfillment_type   text NOT NULL CHECK (fulfillment_type IN ('IN_STOCK','MADE_TO_ORDER')),
    status             text NOT NULL CHECK (status IN (
                          'AWAITING_PAYMENT','AWAITING_DEPOSIT','QUEUED','IN_PRODUCTION',
                          'AWAITING_BALANCE','PAID_IN_FULL','SHIPPED','COMPLETED',
                          'CANCELLED','CANCELLED_DEPOSIT_KEPT','CANCELLED_REFUNDED','FORFEITED')),
    -- customer & shipping (minimum needed to ship)
    customer_name      text NOT NULL CHECK (length(customer_name) BETWEEN 1 AND 120),
    customer_email     text NOT NULL CHECK (customer_email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    phone              text,
    ship_line1         text NOT NULL,
    ship_line2         text,
    ship_city          text NOT NULL,
    ship_region        text,
    ship_postal_code   text,
    ship_country       char(2) NOT NULL CHECK (ship_country ~ '^[A-Z]{2}$'),   -- ISO 3166-1 alpha-2
    -- money (snapshot at order time)
    subtotal_cents     int NOT NULL CHECK (subtotal_cents > 0),
    shipping_cents     int NOT NULL DEFAULT 0 CHECK (shipping_cents >= 0),
    currency           char(3) NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
    -- terms evidence
    terms_version      text NOT NULL REFERENCES terms_document(version),
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
    -- consistency rules
    CHECK ((fulfillment_type = 'IN_STOCK')      <= (status NOT IN ('AWAITING_DEPOSIT','QUEUED','IN_PRODUCTION','AWAITING_BALANCE','CANCELLED_DEPOSIT_KEPT','FORFEITED'))),
    CHECK ((fulfillment_type = 'MADE_TO_ORDER') <= (status <> 'AWAITING_PAYMENT')),
    CHECK ((status IN ('SHIPPED','COMPLETED')) <= (tracking_number IS NOT NULL AND shipped_at IS NOT NULL))
);
CREATE INDEX customer_order_status_idx ON customer_order(status, created_at);
CREATE INDEX customer_order_email_idx  ON customer_order(lower(customer_email));

CREATE TABLE order_item (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id           bigint NOT NULL REFERENCES customer_order(id) ON DELETE RESTRICT,
    variant_id         bigint NOT NULL REFERENCES product_variant(id) ON DELETE RESTRICT,
    product_name       text NOT NULL,     -- snapshots: order stays correct if product is edited later
    scale              text NOT NULL,
    unit_price_cents   int  NOT NULL CHECK (unit_price_cents > 0),
    quantity           int  NOT NULL CHECK (quantity BETWEEN 1 AND 5)
);
CREATE INDEX order_item_order_idx ON order_item(order_id);

-- One row per made-to-order unit = one slot.
CREATE TABLE queue_entry (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_item_id    bigint NOT NULL REFERENCES order_item(id) ON DELETE RESTRICT,
    unit_index       int NOT NULL CHECK (unit_index >= 1),
    production_month date NOT NULL REFERENCES production_month(month),
    queued_at        timestamptz NOT NULL DEFAULT now(),   -- FIFO order inside a month
    status           text NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','IN_PRODUCTION','DONE','CANCELLED')),
    UNIQUE (order_item_id, unit_index)
);
CREATE INDEX queue_entry_month_idx ON queue_entry(production_month, queued_at) WHERE status IN ('QUEUED','IN_PRODUCTION');

-- ============================================================
-- Payments (ADR-0008)
-- ============================================================
CREATE TABLE payment_request (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id       bigint NOT NULL REFERENCES customer_order(id) ON DELETE RESTRICT,
    type           text NOT NULL CHECK (type IN ('DEPOSIT','BALANCE','FULL')),
    amount_cents   int  NOT NULL CHECK (amount_cents > 0),
    channel        text NOT NULL CHECK (channel IN ('ARTISTREE','SHOPEE_PROXY','OTHER')),
    payment_url    text NOT NULL CHECK (payment_url ~ '^https://'),
    status         text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PAID','EXPIRED','CANCELLED')),
    due_at         timestamptz NOT NULL,
    external_ref   text,                                  -- invoice / order id on the external platform
    confirmed_at   timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    version        int NOT NULL DEFAULT 0,
    CHECK ((status = 'PAID') = (confirmed_at IS NOT NULL AND external_ref IS NOT NULL))
);
-- At most one open request of each type per order.
CREATE UNIQUE INDEX payment_request_one_pending ON payment_request(order_id, type) WHERE status = 'PENDING';
-- At most one PAID request of each type per order (no double-counting).
CREATE UNIQUE INDEX payment_request_one_paid    ON payment_request(order_id, type) WHERE status = 'PAID';
-- The same external invoice cannot be used to confirm two payments.
CREATE UNIQUE INDEX payment_request_external_ref ON payment_request(channel, external_ref) WHERE external_ref IS NOT NULL;
CREATE INDEX payment_request_due_idx ON payment_request(due_at) WHERE status = 'PENDING';

-- ============================================================
-- Email outbox (ADR-0009)
-- ============================================================
CREATE TABLE email_outbox (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dedupe_key       text NOT NULL UNIQUE,     -- e.g. 'order:42:balance-reminder-7d' → a job re-run never sends twice
    to_email         text NOT NULL,
    template         text NOT NULL,
    payload          jsonb NOT NULL,
    status           text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
    attempts         int NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    last_error       text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    sent_at          timestamptz
);
CREATE INDEX email_outbox_pending_idx ON email_outbox(next_attempt_at) WHERE status = 'PENDING';

-- ============================================================
-- Admin & audit (ADR-0006)
-- ============================================================
CREATE TABLE admin_user (
    id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email                  text NOT NULL UNIQUE,
    password_hash          text NOT NULL,          -- Argon2id
    totp_secret_encrypted  bytea,                  -- AES-GCM, key from env, never in DB
    totp_enabled           boolean NOT NULL DEFAULT false,
    failed_attempts        int NOT NULL DEFAULT 0,
    locked_until           timestamptz,
    last_login_at          timestamptz,
    created_at             timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE admin_recovery_code (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    admin_id    bigint NOT NULL REFERENCES admin_user(id) ON DELETE CASCADE,
    code_hash   text NOT NULL,
    used_at     timestamptz
);

CREATE TABLE audit_log (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    actor        text NOT NULL CHECK (actor IN ('ADMIN','SYSTEM','CUSTOMER')),
    actor_id     bigint,
    action       text NOT NULL,              -- e.g. 'PAYMENT_CONFIRMED', 'ORDER_TRANSITION'
    entity_type  text NOT NULL,
    entity_id    bigint NOT NULL,
    before       jsonb,
    after        jsonb,
    request_id   text
);
CREATE INDEX audit_log_entity_idx ON audit_log(entity_type, entity_id, occurred_at);
