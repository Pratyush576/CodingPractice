-- Local Services Marketplace schema. No migration framework — idempotent
-- CREATE TABLE IF NOT EXISTS, re-run on every app boot (same discipline as
-- cabreservation/src/main/resources/schema.sql).

CREATE TABLE IF NOT EXISTS customers (
    customer_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    default_payment_method_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS pros (
    pro_id TEXT PRIMARY KEY,
    business_name TEXT NOT NULL,
    email TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    verification_status TEXT NOT NULL DEFAULT 'UNVERIFIED',
    rating NUMERIC,
    years_in_business INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per Pro in Phase 1 (single-category Pros) — a documented
-- simplification; one row per (pro, category) is a data change later, not a
-- schema redesign (DESIGN.md §11).
CREATE TABLE IF NOT EXISTS pro_profiles (
    pro_id TEXT PRIMARY KEY REFERENCES pros(pro_id),
    category_id TEXT NOT NULL,
    service_area_lat DOUBLE PRECISION NOT NULL,
    service_area_lng DOUBLE PRECISION NOT NULL,
    service_area_radius_km DOUBLE PRECISION NOT NULL,
    starting_price NUMERIC,
    min_budget NUMERIC,
    max_job_size TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS categories (
    category_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    questionnaire_schema JSONB NOT NULL,
    monetization_model TEXT NOT NULL   -- LEAD_BASED | INSTANT_BOOK
);

CREATE TABLE IF NOT EXISTS requests (
    request_id TEXT PRIMARY KEY,
    customer_id TEXT NOT NULL,
    category_id TEXT NOT NULL,
    answers JSONB NOT NULL,
    location_lat DOUBLE PRECISION NOT NULL,
    location_lng DOUBLE PRECISION NOT NULL,
    desired_timing TEXT,
    status TEXT NOT NULL DEFAULT 'OPEN',   -- OPEN/HIRED/COMPLETED/CANCELLED
    hired_quote_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS leads (
    lead_id TEXT PRIMARY KEY,
    request_id TEXT NOT NULL,
    pro_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'DELIVERED',   -- DELIVERED/UNLOCKED/QUOTED/WON/LOST/EXPIRED
    credit_cost NUMERIC NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    unlocked_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_leads_by_pro ON leads (pro_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_leads_by_request ON leads (request_id);

-- DESIGN.md §4.3's idempotency guarantee: this primary key is what "unlock,
-- twice, is a no-op" is built on.
CREATE TABLE IF NOT EXISTS lead_unlocks (
    lead_id TEXT NOT NULL,
    pro_id TEXT NOT NULL,
    credit_cost NUMERIC NOT NULL,
    unlocked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (lead_id, pro_id)
);

-- The CAS'd running balance — same version-column pattern as cabreservation's
-- drivers.version. credit_transactions below is the append-only audit trail,
-- not what the balance check reads.
CREATE TABLE IF NOT EXISTS pro_credit_balances (
    pro_id TEXT PRIMARY KEY REFERENCES pros(pro_id),
    balance NUMERIC NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS credit_transactions (
    transaction_id TEXT PRIMARY KEY,
    pro_id TEXT NOT NULL,
    type TEXT NOT NULL,             -- PURCHASE / DEDUCTION / REFUND
    amount NUMERIC NOT NULL,
    lead_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS quotes (
    quote_id TEXT PRIMARY KEY,
    lead_id TEXT NOT NULL UNIQUE,
    price NUMERIC NOT NULL,
    message TEXT,
    status TEXT NOT NULL DEFAULT 'PENDING',   -- PENDING/ACCEPTED/DECLINED/EXPIRED
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS messages (
    message_id TEXT PRIMARY KEY,
    request_id TEXT NOT NULL,
    sender_id TEXT NOT NULL,
    sender_type TEXT NOT NULL,
    body TEXT NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Phase 2+ tables, created now for schema stability.
CREATE TABLE IF NOT EXISTS instant_book_slots (
    slot_id TEXT PRIMARY KEY,
    pro_id TEXT NOT NULL,
    category_id TEXT NOT NULL,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    price NUMERIC NOT NULL,
    status TEXT NOT NULL DEFAULT 'OPEN'   -- OPEN/BOOKED
);
CREATE TABLE IF NOT EXISTS bookings (
    booking_id TEXT PRIMARY KEY,
    slot_id TEXT NOT NULL,
    customer_id TEXT NOT NULL,
    pro_id TEXT NOT NULL,
    price NUMERIC NOT NULL,
    status TEXT NOT NULL,
    payment_id TEXT
);
CREATE TABLE IF NOT EXISTS payments (
    payment_id TEXT PRIMARY KEY,
    booking_id TEXT NOT NULL UNIQUE,
    amount NUMERIC NOT NULL,
    status TEXT NOT NULL,
    gateway_reference TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS payouts (
    payout_id TEXT PRIMARY KEY,
    booking_id TEXT NOT NULL UNIQUE,
    pro_id TEXT NOT NULL,
    amount NUMERIC NOT NULL,
    status TEXT NOT NULL,
    provider_reference TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS reviews (
    review_id TEXT PRIMARY KEY,
    request_id TEXT NOT NULL,
    customer_id TEXT NOT NULL,
    pro_id TEXT NOT NULL,
    rating INTEGER NOT NULL,
    text TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (request_id, customer_id)
);

-- Seed a couple of real categories so Phase 1 has something to post
-- Requests against without a category-management UI yet.
INSERT INTO categories (category_id, name, questionnaire_schema, monetization_model) VALUES
    ('house-cleaning', 'House Cleaning', '{"fields":["squareFootage","frequency"]}', 'LEAD_BASED'),
    ('handyman', 'Handyman', '{"fields":["jobDescription","estimatedHours"]}', 'INSTANT_BOOK')
ON CONFLICT (category_id) DO NOTHING;
