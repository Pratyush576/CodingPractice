-- Booking Service schema — LLD.md §2 "Communication & storage".
-- Idempotent: run at app startup, safe to re-run.

-- A minimal Party (LLD.md §15 references the full model) — just enough for a
-- real Shipper/Operator account instead of a free-text tenant ID.
CREATE TABLE IF NOT EXISTS parties (
    party_id      TEXT PRIMARY KEY,
    tenant_id     TEXT NOT NULL,
    role          TEXT NOT NULL,
    name          TEXT NOT NULL,
    email         TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS bookings (
    tenant_id             TEXT NOT NULL,
    booking_id            TEXT NOT NULL,
    status                TEXT NOT NULL,
    mode_preference       TEXT NOT NULL,
    incoterm              TEXT NOT NULL,
    load_type             TEXT NOT NULL,
    origin_node_id        TEXT NOT NULL,
    destination_node_id   TEXT NOT NULL,
    shipper_id            TEXT NOT NULL,
    consignee_id          TEXT NOT NULL,
    notify_party_id       TEXT,
    contract_id           TEXT,
    required_pickup_by    TIMESTAMPTZ,
    required_delivery_by  TIMESTAMPTZ,
    total_weight_kg       NUMERIC,
    total_volume_cbm      NUMERIC,
    version               BIGINT NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, booking_id)
);

-- CREATE TABLE IF NOT EXISTS is a no-op against an already-existing bookings
-- table, so a new column needs its own statement — this runs safely against
-- both a fresh database and one with existing rows.
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS capacity_offering_id TEXT;

-- An Operator looks up/lists bookings across every tenant (LLD.md §2's
-- "Implemented" callout) — the composite PK (tenant_id, booking_id) can't
-- serve a WHERE booking_id = ? query efficiently since tenant_id is its
-- leading column, so booking_id needs its own index.
CREATE UNIQUE INDEX IF NOT EXISTS idx_bookings_booking_id ON bookings (booking_id);

CREATE TABLE IF NOT EXISTS cargo_line_items (
    tenant_id           TEXT NOT NULL,
    booking_id          TEXT NOT NULL,
    line_id             TEXT NOT NULL,
    hs_code             TEXT,
    description         TEXT NOT NULL,
    country_of_origin   TEXT,
    quantity            NUMERIC NOT NULL,
    unit_of_measure     TEXT NOT NULL,
    line_weight_kg      NUMERIC,
    line_value_amount   NUMERIC,
    line_value_currency TEXT,
    dg_class            TEXT,
    un_number           TEXT,
    packing_group       TEXT,
    PRIMARY KEY (tenant_id, booking_id, line_id),
    FOREIGN KEY (tenant_id, booking_id) REFERENCES bookings (tenant_id, booking_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS container_requirements (
    tenant_id      TEXT NOT NULL,
    booking_id     TEXT NOT NULL,
    container_type TEXT NOT NULL,
    quantity       INTEGER NOT NULL,
    PRIMARY KEY (tenant_id, booking_id, container_type),
    FOREIGN KEY (tenant_id, booking_id) REFERENCES bookings (tenant_id, booking_id) ON DELETE CASCADE
);

-- Supply — LLD.md §4 Matching Engine. Cross-tenant: a Booking from any
-- tenant can match a CapacityOffering from any other — Operators (and
-- therefore Matching) are tenant-agnostic, only Shipper visibility is
-- scoped to one tenant. See LLD.md §4's "Implemented" callout.
CREATE TABLE IF NOT EXISTS capacity_offerings (
    tenant_id            TEXT NOT NULL,
    offering_id          TEXT NOT NULL,
    operator_id          TEXT NOT NULL,
    mode                 TEXT NOT NULL,
    origin_node_id       TEXT NOT NULL,
    destination_node_id  TEXT NOT NULL,
    total_weight_kg      NUMERIC,
    available_weight_kg  NUMERIC,
    total_volume_cbm     NUMERIC,
    available_volume_cbm NUMERIC,
    rate_amount          NUMERIC NOT NULL,
    rate_currency        TEXT NOT NULL,
    valid_from           TIMESTAMPTZ,
    valid_until          TIMESTAMPTZ,
    status               TEXT NOT NULL,
    version              BIGINT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, offering_id)
);

CREATE TABLE IF NOT EXISTS capacity_offering_containers (
    tenant_id           TEXT NOT NULL,
    offering_id         TEXT NOT NULL,
    container_type      TEXT NOT NULL,
    total_quantity      INTEGER NOT NULL,
    available_quantity  INTEGER NOT NULL,
    PRIMARY KEY (tenant_id, offering_id, container_type),
    FOREIGN KEY (tenant_id, offering_id) REFERENCES capacity_offerings (tenant_id, offering_id) ON DELETE CASCADE
);

-- Not tenant_id-first: findCandidates() searches across every tenant now, so
-- tenant_id is no longer a useful leading column for this index. Dropped and
-- recreated, not just "IF NOT EXISTS" — a same-named index with the old
-- (tenant_id-first) column list already exists on any already-running
-- database, and "IF NOT EXISTS" would silently leave that stale definition
-- in place rather than redefining it.
DROP INDEX IF EXISTS idx_capacity_offerings_lane;
CREATE INDEX idx_capacity_offerings_lane
    ON capacity_offerings (mode, origin_node_id, destination_node_id, status);

-- Mirrors idx_bookings_booking_id — reserve()/find() now look up an
-- offering by ID alone, regardless of which tenant created it.
CREATE UNIQUE INDEX IF NOT EXISTS idx_capacity_offerings_offering_id ON capacity_offerings (offering_id);

-- Transactional outbox — LLD.md §1.3 "Consistency Mechanisms". One table
-- shared by every aggregate this service owns; a domain write and its
-- outbox row always commit in the same transaction.
CREATE TABLE IF NOT EXISTS outbox (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id   TEXT NOT NULL,
    event_type     TEXT NOT NULL,
    payload        JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
