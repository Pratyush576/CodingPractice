# Cab Reservation Platform — local dev

The first real implementation slice of the design at
[`../lib/src/main/java/org/pk/practices/design/cabreservation/`](../lib/src/main/java/org/pk/practices/design/cabreservation/DESIGN.md).

**What's implemented (Phase 0/1 of the phased build plan):** rider/driver
registration and login (a lightweight PBKDF2 + bearer-token layer DESIGN.md
itself doesn't specify — added for a real account model), a driver
online/offline/location-ping flow backed by a real Redis geospatial index
(H3 for region sharding, Redis `GEOADD`/`GEOSEARCH` for the proximity
query — DESIGN.md §4.2), and the full trip request → matching → lifecycle
spine (§4.1, §4.3, §4.4) with the double-dispatch race fixed for real via a
Postgres-backed optimistic-concurrency CAS on driver status, not just
described in prose. Every trip-returning endpoint responds with an enriched
`TripView` rather than the raw trip row — riders see the assigned driver's
name/rating and vice versa, and `GET /v1/trips` lists a caller's own trip
history (past rides for a rider, past trips for a driver).

**Not yet implemented** (see the DESIGN.md and the phased plan for what's
next): fare/pricing (`POST /v1/trips` takes no fare — `fareEstimate`/
`fareFinal` stay `null`), payment/payout/invoicing (§4.6/§4.7), real-time
WebSocket tracking (§4.5 — the browser UI polls instead), ratings (§4.8),
and the observability instrumentation in §7.

Runs entirely locally, at zero cost — Postgres + Redis via Docker, matching
this repo's `supplychain` module's local-dev philosophy.

## Run it

```bash
# 1. Start local infra (Postgres on :5433, Redis on :6379)
docker compose up -d cabreservation-postgres cabreservation-redis

# 2. Run the service (applies schema.sql automatically on startup)
./gradlew :cabreservation:run
```

The app listens on `:7071` by default (env vars `CABRESERVATION_JDBC_URL`,
`CABRESERVATION_JDBC_USER`, `CABRESERVATION_JDBC_PASSWORD`,
`CABRESERVATION_REDIS_HOST`, `CABRESERVATION_REDIS_PORT`,
`CABRESERVATION_PORT` override the defaults).

## Accounts

Every actor registers as one of two account types — `Rider` and `Driver`
are genuinely distinct entities/tables in this design, not one polymorphic
identity with a role flag:

- **Rider** — can request trips, view/cancel their own.
- **Driver** — can go online/offline, send location pings, and
  accept/reject trip offers. Going online *requires* a location — a driver
  can't be "available but unlocatable" (§4.3). At registration, a driver also
  picks a **car icon color** (Blue/Red/Green/Gold/Purple) — purely cosmetic,
  it's how that driver's marker renders on a rider's map instead of a
  generic pin — and can change it anytime afterward via
  `PATCH /v1/drivers/me/car-icon` (`GET /v1/drivers/me` returns the driver's
  own current choice, e.g. to pre-fill a picker).

Sessions are opaque bearer tokens held in memory (`SessionManager`, 8-hour
TTL) — they don't survive an app restart. This is the minimal version of a
real account model, same spirit as `supplychain`'s own auth layer, not a
production security model.

## UI

Open **http://localhost:7071/** in a browser. Log in or create an account
first (choose Rider or Driver); the panel you see afterward depends on your
role:

- **Rider** — a live map (Leaflet + OpenStreetMap tiles, free, no API key)
  with pickup/dropoff pins that seed from your browser's real location if
  you grant permission. Set either pin three ways: drag it directly, click
  "Choose pickup/dropoff on map" then click anywhere on the map, or type
  coordinates straight into the "Request a Trip" form — all three stay in
  sync, and the form fields remain the actual source of truth sent to the
  server. The driver's marker renders as a small car icon in whatever color
  that driver has currently chosen (registration default, or a later
  update — see Accounts above), not a generic pin — the same shape used for
  the outstanding offer's driver during `MATCHING` and the assigned driver
  afterward. A status panel polls
  the trip every second, shows its lifecycle progression, draws the driver's
  live position on the map once one's assigned (or offered, during
  `MATCHING`), and offers a "Cancel trip" button for as long as the trip is
  still cancellable (`REQUESTED`/`MATCHING`/`MATCHED`/`DRIVER_ARRIVING` — it
  disappears once a trip is `IN_PROGRESS`), plus a "Past Rides" history panel.
- **Driver** — a live map showing your own position, continuously tracked
  from the browser's real GPS via `watchPosition` (dragging the marker or
  editing the lat/lng fields still overrides it manually — useful for
  simulating multiple drivers from one machine, since real GPS would
  otherwise report the same spot for all of them); an availability panel
  (go online, go offline, send a location ping) that also auto-pings your
  current position to the server every ~4s while online; a "Vehicle" panel
  to change your car icon at any time (pre-filled with your current choice,
  with a live preview as you pick); plus panels that
  poll for a pending trip offer (accept/reject, with pickup/dropoff pins
  drawn on the map) and, once matched, the active-trip controls (arrived /
  start / complete), plus a "Past Trips" history panel.

The moment a trip goes `IN_PROGRESS` (driver taps "Start trip"), both maps
draw the actual road route from pickup to dropoff — a real driving path, not
a straight line — sourced live from OSRM's public routing API, alongside an
"Estimated time to destination" callout (same OSRM `duration`, so rider and
driver see the same number). The route disappears again once the trip
leaves `IN_PROGRESS` (completed or cancelled), and a brand-new trip always
fetches its own fresh route rather than reusing a stale one.

While a trip is `DRIVER_ARRIVING`, the rider additionally sees a
"Driver arriving in ~X min" callout — a separate OSRM route from the
driver's current live position to the pickup point, refetched roughly every
10 seconds since (unlike the destination ETA) the driver is actually moving
during this phase. This one is rider-only; there's no equivalent on the
driver's own screen.

Map tiles come straight from `tile.openstreetmap.org`, route geometry from
`router.project-osrm.org`, and the Leaflet library from the `unpkg` CDN
(pinned to a specific version with a Subresource Integrity hash) — all three
require outbound internet access from the browser, unlike the rest of this
app which is fully local. OSRM's public server is a free, no-API-key
best-effort demo service, not guaranteed uptime or throughput — if it's
unreachable or rate-limited, the route line is silently skipped and the
pickup/dropoff pins alone still convey the trip.

This UI was built and its data flow verified via `curl` against every
endpoint it calls (including confirming a driver's `lat`/`lng` actually
updates in the rider's response after a location ping), but hasn't been
click-tested in an actual browser from this environment — worth exercising
once yourself before relying on it, especially the geolocation permission
prompts and marker dragging.

## Try it via curl

Register creates an account **and** logs you in — the response includes a
`token`, used as `Authorization: Bearer <token>` on every protected call.

```bash
# Register a driver
curl -X POST http://localhost:7071/v1/auth/register/driver -H "Content-Type: application/json" -d '{
  "name": "Dave", "email": "dave@example.com", "password": "correct-horse-1",
  "vehicle": {"plate": "ABC123", "productType": "STANDARD"}
}'
# -> {"token": "...", "accountId": "...", "accountType": "DRIVER", "name": "Dave"}

DRIVER_TOKEN=...   # the "token" field above

# Go online — this is what makes the driver searchable (§4.3)
curl -X POST http://localhost:7071/v1/drivers/online -H "Content-Type: application/json" \
  -H "Authorization: Bearer $DRIVER_TOKEN" -d '{"lat": 37.7749, "lng": -122.4194}'

# Register + log in a rider
curl -X POST http://localhost:7071/v1/auth/register/rider -H "Content-Type: application/json" -d '{
  "name": "Rachel", "email": "rachel@example.com", "password": "correct-horse-1"
}'
RIDER_TOKEN=...

# Request a trip near the driver — note there's no riderId field; it's derived from the token
curl -X POST http://localhost:7071/v1/trips -H "Content-Type: application/json" \
  -H "Authorization: Bearer $RIDER_TOKEN" \
  -d '{"pickupLat": 37.7750, "pickupLng": -122.4183, "dropoffLat": 37.7849, "dropoffLng": -122.4094}'
# -> {"tripId": "...", "status": "REQUESTED", "rider": {"id": "...", "name": "Rachel", "rating": null}, "driver": null, ...}
# -- returns immediately, matching happens async

TRIP_ID=...

# Poll — should reach MATCHING within ~1s, with offeredDriverId set
curl http://localhost:7071/v1/trips/$TRIP_ID -H "Authorization: Bearer $RIDER_TOKEN"

# Driver accepts — MATCHED, then auto-advances to DRIVER_ARRIVING (§4.4 has no separate trigger for that edge)
curl -X POST http://localhost:7071/v1/drivers/offers/$TRIP_ID/respond -H "Content-Type: application/json" \
  -H "Authorization: Bearer $DRIVER_TOKEN" -d '{"accept": true}'

# Walk the rest of the lifecycle
curl -X POST http://localhost:7071/v1/trips/$TRIP_ID/arrived -H "Authorization: Bearer $DRIVER_TOKEN"
curl -X POST http://localhost:7071/v1/trips/$TRIP_ID/start -H "Authorization: Bearer $DRIVER_TOKEN"
curl -X POST http://localhost:7071/v1/trips/$TRIP_ID/complete -H "Authorization: Bearer $DRIVER_TOKEN"

curl http://localhost:7071/v1/trips/$TRIP_ID -H "Authorization: Bearer $RIDER_TOKEN"
# -> status: COMPLETED, and "driver" is now populated: {"id": "...", "name": "Dave", "rating": null}

# Trip history — scoped to whichever role the caller's token actually is
curl http://localhost:7071/v1/trips -H "Authorization: Bearer $RIDER_TOKEN"   # a rider's past rides
curl http://localhost:7071/v1/trips -H "Authorization: Bearer $DRIVER_TOKEN"  # a driver's past trips
```

Every trip-returning response above (`create`, `get`, `list`,
`arrived`/`start`/`complete`/`cancel`, and the driver's
`GET /v1/drivers/me/active-trip`) is a `TripView`, not the raw trip row: the
rider's own `id`/`name`/`rating` are always present under `"rider"`, and
`"driver"` is `null` until a driver is actually assigned, then carries that
driver's `id`/`name`/`rating`/`vehicle` (`plate`/`make`/`model`/`productType`)
— this is what lets the rider see the driver's details and the driver see
the rider's, per DESIGN.md's counterpart-visibility expectation. While a
trip is `MATCHING` — an offer sent but not yet accepted — `"driver"` is
still `null`, but `"offeredDriver"` carries that same shape (name, rating,
cab) so the rider isn't staring at a bare `offeredDriverId` while waiting
for a response.

A rider/driver token only works for its own role's endpoints — a rider
token calling a driver-only endpoint (or vice versa) gets `403 FORBIDDEN`.
`GET /v1/trips/{id}` and `/cancel` also enforce that the caller is actually a
party to that specific trip (its rider or its assigned driver), and
`/arrived`, `/start`, `/complete` further require the caller to be the
*assigned* driver — a different driver's token gets `403 FORBIDDEN` on
someone else's trip. No token, or an invalid/expired one, gets
`401 UNAUTHENTICATED`.

## Verifying the double-dispatch fix

The single correctness property this design spends the most effort on
(§4.3) is that two riders can never both end up matched to the same driver.
With exactly one `AVAILABLE` driver, fire several concurrent trip requests
near them and check immediately (well inside the 10-second offer window):

```bash
for i in $(seq 1 15); do
  curl -s -X POST http://localhost:7071/v1/trips -H "Content-Type: application/json" \
    -H "Authorization: Bearer $RIDER_TOKEN" \
    -d '{"pickupLat":37.7750,"pickupLng":-122.4183,"dropoffLat":37.7849,"dropoffLng":-122.4094}' &
done
wait
```

Exactly one trip should reach `MATCHING` with `offeredDriverId` set; the
rest resolve to `NO_DRIVERS_FOUND` (there's nowhere else to dispatch with
only one driver). Confirm directly against Postgres — this is §7.3's
double-assignment canary, run by hand:

```bash
docker exec <postgres-container> psql -U cabreservation -d cabreservation -c "
SELECT driver_id, count(*) FROM trips
WHERE driver_id IS NOT NULL AND status NOT IN ('COMPLETED','CANCELLED_BY_RIDER','CANCELLED_BY_DRIVER','NO_DRIVERS_FOUND')
GROUP BY driver_id HAVING count(*) > 1;
"
# must return zero rows
```

## Inspect Postgres / Redis directly

```bash
docker exec <postgres-container> psql -U cabreservation -d cabreservation -c "SELECT trip_id, status, driver_id, offered_driver_id FROM trips ORDER BY created_at DESC;"
docker exec <redis-container> redis-cli KEYS "geo:*"
```

## Stop everything

```bash
docker compose stop cabreservation-postgres cabreservation-redis   # add `down -v` on the whole compose file to also drop volumes
```
