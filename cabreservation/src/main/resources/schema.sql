CREATE TABLE IF NOT EXISTS riders (
    rider_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    default_payment_method_id TEXT,
    rating NUMERIC,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS drivers (
    driver_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    vehicle_id TEXT,
    status TEXT NOT NULL DEFAULT 'OFFLINE', -- AVAILABLE / PENDING_OFFER / ON_TRIP / OFFLINE (DESIGN.md §4.3)
    rating NUMERIC,
    last_lat DOUBLE PRECISION,
    last_lng DOUBLE PRECISION,
    last_ping_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0, -- optimistic-concurrency guard for the CAS in DriverService (§4.3)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS vehicles (
    vehicle_id TEXT PRIMARY KEY,
    driver_id TEXT NOT NULL REFERENCES drivers(driver_id),
    plate TEXT NOT NULL,
    make TEXT,
    model TEXT,
    product_type TEXT NOT NULL DEFAULT 'STANDARD',
    car_icon TEXT NOT NULL DEFAULT 'BLUE'
);
-- schema.sql has no separate migration mechanism — it's just re-run on every startup — so an
-- already-existing vehicles table (from before car_icon existed) needs this to actually gain the column.
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS car_icon TEXT NOT NULL DEFAULT 'BLUE';

CREATE TABLE IF NOT EXISTS trips (
    trip_id TEXT PRIMARY KEY,
    rider_id TEXT NOT NULL,
    driver_id TEXT,
    status TEXT NOT NULL, -- DESIGN.md §4.4 state machine values
    pickup_lat DOUBLE PRECISION NOT NULL,
    pickup_lng DOUBLE PRECISION NOT NULL,
    dropoff_lat DOUBLE PRECISION NOT NULL,
    dropoff_lng DOUBLE PRECISION NOT NULL,
    offered_driver_id TEXT,
    offer_expires_at TIMESTAMPTZ,
    fare_estimate NUMERIC, -- NULL until Phase 2's PricingStrategy lands
    fare_final NUMERIC,
    version BIGINT NOT NULL DEFAULT 0, -- optimistic-concurrency guard for status transitions
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    matched_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ, -- set when IN_PROGRESS begins; actual (matched_at..completed_at unavailable) driven-time input to the final fare
    completed_at TIMESTAMPTZ
);
-- schema.sql has no separate migration mechanism — it's just re-run on every startup — so an
-- already-existing trips table (from before started_at existed) needs this to actually gain the column.
ALTER TABLE trips ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ;

-- Backs MatchOfferTimeoutSweeper's poll query (find offers past their expiry, still MATCHING).
CREATE INDEX IF NOT EXISTS idx_trips_matching_sweep ON trips (status, offer_expires_at) WHERE status = 'MATCHING';

-- Phase 2+ tables, created now for schema stability.
CREATE TABLE IF NOT EXISTS payments (
    payment_id TEXT PRIMARY KEY,
    trip_id TEXT NOT NULL UNIQUE, -- the real idempotency guarantee (DESIGN.md §6/§4.7) — not just application logic
    amount NUMERIC NOT NULL,
    status TEXT NOT NULL,
    gateway_reference TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS payouts (
    payout_id TEXT PRIMARY KEY,
    trip_id TEXT NOT NULL UNIQUE,
    driver_id TEXT NOT NULL,
    amount NUMERIC NOT NULL,
    status TEXT NOT NULL,
    provider_reference TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Backs the driver earnings view (GET /v1/drivers/me/payouts) — one lookup per driver, most recent first.
CREATE INDEX IF NOT EXISTS idx_payouts_driver ON payouts (driver_id, created_at DESC);

CREATE TABLE IF NOT EXISTS invoices (
    invoice_id TEXT PRIMARY KEY,
    trip_id TEXT NOT NULL,
    rider_id TEXT NOT NULL,
    total NUMERIC NOT NULL,
    status TEXT NOT NULL, -- ISSUED / PAYMENT_FAILED (DESIGN.md §4.7)
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS invoice_line_items (
    invoice_id TEXT NOT NULL REFERENCES invoices(invoice_id),
    line_type TEXT NOT NULL, -- BASE / DISTANCE / TIME / TOLLS / TAX / TIP / DISCOUNT
    amount NUMERIC NOT NULL
);

CREATE TABLE IF NOT EXISTS ratings (
    trip_id TEXT NOT NULL,
    rater_id TEXT NOT NULL,
    ratee_id TEXT NOT NULL,
    score INTEGER NOT NULL,
    comment TEXT,
    PRIMARY KEY (trip_id, rater_id)
);
