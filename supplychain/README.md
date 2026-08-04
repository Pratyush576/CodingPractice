# Supply Chain Orchestration Platform — local dev

The first real implementation slice of the design at
[`../lib/src/main/java/org/pk/practices/design/supplychain/`](../lib/src/main/java/org/pk/practices/design/supplychain/DESIGN.md)
([DESIGN.md](../lib/src/main/java/org/pk/practices/design/supplychain/DESIGN.md),
[LLD.md](../lib/src/main/java/org/pk/practices/design/supplychain/LLD.md)).

**What's implemented:** Booking Service (LLD.md §2) — `createDraft`, `submit`,
`amend`, `cancel`, `get`, `list`, backed by Postgres, with a transactional
outbox relaying `BookingSubmitted`/`BookingConfirmed` to Kafka (LLD.md §1.3) —
plus a cross-tenant slice of Matching Engine (LLD.md §4): Operators list
`CapacityOffering`s, a Shipper can find candidate offerings for a `SUBMITTED`
booking and reserve one — from *any* tenant's supply, not just their own —
which confirms the booking. See LLD.md §4's "Implemented" callout for exactly
what's simplified versus the full design (no Contract Service, no Planning
Engine, and one known consistency gap between the two repositories involved).
Compliance,
Planning/Replanning, Disruption, Milestone Processing, Visibility,
Communication, Billing, and Procurement are all still docs-only.

Runs entirely locally, at zero cost — see the design docs' cost/local-build
discussion for why this stack (Postgres + a single Kafka broker) was chosen
over the production-scale one in LLD.md §1.1.

## Run it

```bash
# 1. Start local infra (Postgres + single-broker Kafka, KRaft mode)
docker compose up -d      # from the repo root

# 2. Run the service (applies schema.sql automatically on startup)
./gradlew :supplychain:run
```

The app listens on `:7070` by default (7000 is taken by macOS's AirPlay
Receiver on most Macs — see `BookingServiceApp` for the env vars that
override host/port/credentials if yours differs).

## Accounts

Every actor is a real `Party` (LLD.md §15) — one of two roles:

- **SHIPPER** — can create bookings, and can only see/submit/amend/cancel
  their own (`booking.shipperId == their partyId`).
- **OPERATOR** — tenant-agnostic: sees and can act on every booking from
  every tenant, not just their own, but can't create one (a booking always
  belongs to the Shipper who created it) — and never sees a `DRAFT`, from
  anyone: that's still private, unsubmitted Shipper work, invisible to an
  Operator via the list, an explicit status filter, or a direct fetch by ID
  (all three just come back empty, not an error). Matching follows the same
  cross-tenant rule — a Booking can reserve capacity from a
  `CapacityOffering` created under a completely different tenant. The
  cross-tenant part was a deliberate reversal from an earlier same-tenant
  design once it became clear that model meant a new Operator account per
  tenant just to see anything — see LLD.md §4's "Implemented" callout for
  the full reasoning.

There's no email verification or MFA — passwords are hashed with
PBKDF2WithHmacSHA256 (salted, 120k iterations, JDK built-in, no new
dependency), and sessions are opaque bearer tokens held in memory
(`SessionManager`, 8-hour TTL) — they don't survive an app restart. This is
deliberately the minimal version of what LLD.md §15 documents (OIDC via
Keycloak/Auth0/Okta, mTLS for machine callers) — enough that "account" is
real instead of a free-text tenant ID, not the production security model.

## UI

Open **http://localhost:7070/** in a browser. It's a single static page
(`supplychain/src/main/resources/public/`, plain JS/fetch, no build step, no
framework — served directly by Javalin). Log in or create an account first;
the panel you see afterward depends on your role:

- **Create Booking** (Shipper only) — the full `createDraft` form, including
  dynamic cargo-line-item and container-requirement rows. `shipperId` is
  never a form field — it's always the logged-in Shipper.
- **Bookings** (both roles) — a Shipper sees only their own; an Operator sees
  every booking in the tenant. Actions: View/Submit/Amend/Cancel, plus
  **Find Matches** on a `SUBMITTED` booking, which lists candidate
  `CapacityOffering`s with a **Reserve** button per candidate — reserving
  confirms the booking.
- **Supply** (Operator only) — create a `CapacityOffering` (container-type
  pools for FCL demand, and/or weight/volume pools for LCL/Breakbulk demand)
  and see your own offerings with live available/total capacity.

This UI was built and verified via `curl` against every endpoint it calls
(including the role/ownership rejection paths below), but hasn't been
click-tested in an actual browser from this environment — worth exercising
once yourself before relying on it.

## Try it via curl

Register creates an account **and** logs you in — the response includes a
`token`, used as `Authorization: Bearer <token>` on every `/v1/bookings*` call.

```bash
# Register a Shipper (role: SHIPPER or OPERATOR)
curl -X POST http://localhost:7070/v1/auth/register -H "Content-Type: application/json" -d '{
  "tenantId": "acme-corp", "role": "SHIPPER", "name": "Alice", "email": "alice@acme.example", "password": "correct-horse-1"
}'
# -> {"token": "...", "partyId": "...", "tenantId": "acme-corp", "role": "SHIPPER", "name": "Alice"}

# Or log in with an existing account
curl -X POST http://localhost:7070/v1/auth/login -H "Content-Type: application/json" -d '{
  "email": "alice@acme.example", "password": "correct-horse-1"
}'

TOKEN=...   # the "token" field from either response above

# Create a draft — note there's no shipperId field; it's derived from the token
curl -X POST http://localhost:7070/v1/bookings \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{
    "modePreference": "OCEAN", "incoterm": "FOB", "loadType": "FCL",
    "originNodeId": "CNSHA", "destinationNodeId": "USORD", "consigneeId": "consignee-1",
    "requiredPickupBy": "2026-09-01T00:00:00Z", "requiredDeliveryBy": "2026-09-25T00:00:00Z",
    "containerRequirements": [{"containerType": "40HC", "quantity": 1}],
    "cargoLineItems": [{"description": "Laptop computers", "quantity": 500, "unitOfMeasure": "EA"}]
  }'

# Fetch it back (use the bookingId from the response above)
curl http://localhost:7070/v1/bookings/{bookingId} -H "Authorization: Bearer $TOKEN"

# List bookings — a Shipper sees only their own; an Operator sees every booking, every tenant
# (optionally ?status=DRAFT|SUBMITTED|CONFIRMED|CANCELLED)
curl http://localhost:7070/v1/bookings -H "Authorization: Bearer $TOKEN"

# Submit it — this is what publishes BookingSubmitted through the outbox
curl -X PUT http://localhost:7070/v1/bookings/{bookingId}/submit -H "Authorization: Bearer $TOKEN"

# Amend it — expectedVersion must match the version from the last response you got
curl -X PUT http://localhost:7070/v1/bookings/{bookingId}/amend \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"expectedVersion": 1, "notifyPartyId": "notify-party-1"}'

# Cancel it
curl -X DELETE "http://localhost:7070/v1/bookings/{bookingId}?reason=customer-request" \
  -H "Authorization: Bearer $TOKEN"

# Log out (invalidates the token server-side)
curl -X POST http://localhost:7070/v1/auth/logout -H "Authorization: Bearer $TOKEN"
```

A different Shipper's token gets `403 FORBIDDEN` on someone else's booking
(`404` on `GET`, so a Shipper can't even confirm another booking exists); an
Operator's token can act on any booking, from any tenant; no token, or an
invalid/expired one, gets `401 UNAUTHENTICATED`.

## Supply + Matching via curl

`OP_TOKEN` here is an **OPERATOR** token from the register/login calls above.

```bash
# Create an FCL capacity offering (an Operator's own supply)
curl -X POST http://localhost:7070/v1/capacity-offerings \
  -H "Content-Type: application/json" -H "Authorization: Bearer $OP_TOKEN" \
  -d '{
    "mode": "OCEAN", "originNodeId": "CNSHA", "destinationNodeId": "USORD",
    "containerCapacities": [{"containerType": "40HC", "totalQuantity": 2}],
    "rateAmount": 3500, "rateCurrency": "USD"
  }'
# -> {"offeringId": "...", "containerCapacities": [{"containerType":"40HC","totalQuantity":2,"availableQuantity":2}], ...}

# List your own offerings (Operator only)
curl http://localhost:7070/v1/capacity-offerings -H "Authorization: Bearer $OP_TOKEN"

# As the Shipper who owns a SUBMITTED booking on that same lane, find candidates
curl http://localhost:7070/v1/bookings/{bookingId}/candidates -H "Authorization: Bearer $TOKEN"

# Reserve one — this is what actually confirms the booking
curl -X POST http://localhost:7070/v1/bookings/{bookingId}/reserve \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"offeringId": "..."}'
# -> booking status flips to CONFIRMED, capacityOfferingId is set,
#    and the offering's available_quantity drops by the reserved amount
```

Only an `OPERATOR` may create an offering (`403` otherwise). Reserving against
an offering with insufficient remaining capacity returns
`422 INSUFFICIENT_CAPACITY`; heavy concurrent contention on the same offering
returns `409 CONFLICT` after a few CAS retries — see LLD.md §4's callout for
the one known gap (a lost race on the booking's own save *after* capacity is
already reserved leaves the reservation standing without the booking
reflecting it yet — surfaced as a conflict to retry, not silently lost).

## Watch the outbox reach Kafka

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:19092 --topic booking-events --from-beginning
```

## Inspect Postgres directly

```bash
docker compose exec postgres psql -U supplychain -d supplychain \
  -c "SELECT id, event_type, aggregate_id, published_at IS NOT NULL AS published FROM outbox ORDER BY id;"
```

## Stop everything

```bash
docker compose down          # add -v to also drop the Postgres volume
```
