const state = {
  token: localStorage.getItem('cab_token'),
  accountId: localStorage.getItem('cab_accountId'),
  accountType: localStorage.getItem('cab_accountType'),
  name: localStorage.getItem('cab_name'),
};

let pollTimer = null;

function api(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  return fetch(path, { ...options, headers }).then(async (res) => {
    const text = await res.text();
    const body = text ? JSON.parse(text) : null;
    if (!res.ok) {
      // A 401 while we believe we're logged in means the session (SessionManager's 8-hour TTL) expired
      // server-side — not a login attempt gone wrong, since that case has no token to begin with.
      if (res.status === 401 && state.token) {
        handleSessionExpired();
      }
      throw new Error(body && body.message ? body.message : `HTTP ${res.status}`);
    }
    return body;
  });
}

function handleSessionExpired() {
  clearSession();
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
  render();
  const resultEl = document.getElementById('loginResult');
  resultEl.textContent = 'Your session expired — please log in again.';
  resultEl.className = 'result error';
}

function saveSession(result) {
  state.token = result.token;
  state.accountId = result.accountId;
  state.accountType = result.accountType;
  state.name = result.name;
  localStorage.setItem('cab_token', result.token);
  localStorage.setItem('cab_accountId', result.accountId);
  localStorage.setItem('cab_accountType', result.accountType);
  localStorage.setItem('cab_name', result.name);
}

function clearSession() {
  state.token = state.accountId = state.accountType = state.name = null;
  localStorage.removeItem('cab_token');
  localStorage.removeItem('cab_accountId');
  localStorage.removeItem('cab_accountType');
  localStorage.removeItem('cab_name');
}

function render() {
  const authSection = document.getElementById('authSection');
  const riderSection = document.getElementById('riderSection');
  const driverSection = document.getElementById('driverSection');
  const accountBar = document.getElementById('accountBar');

  if (!state.token) {
    authSection.classList.remove('hidden');
    riderSection.classList.add('hidden');
    driverSection.classList.add('hidden');
    accountBar.classList.add('hidden');
    return;
  }
  authSection.classList.add('hidden');
  accountBar.classList.remove('hidden');
  document.getElementById('accountInfo').textContent = `${state.name} (${state.accountType})`;

  if (state.accountType === 'RIDER') {
    riderSection.classList.remove('hidden');
    driverSection.classList.add('hidden');
    initRiderMap();
    loadRiderHistory();
  } else {
    driverSection.classList.remove('hidden');
    riderSection.classList.add('hidden');
    initDriverMap();
    pollDriverActiveTrip();
    loadDriverHistory();
  }
}

// ---- Maps ----
// Leaflet + OpenStreetMap tiles — free, no API key. Real device location comes from the browser's
// Geolocation API; the lat/lng number inputs stay the single source of truth (markers/geolocation only
// ever write into them), so nothing here bypasses the existing form-submit/ping logic.

let riderMap = null;
let pickupMarker = null;
let dropoffMarker = null;
let riderDriverMarker = null;

let driverMap = null;
let driverSelfMarker = null;
let driverPickupMarker = null;
let driverDropoffMarker = null;
let driverIsOnline = false;
let driverAutoPingTimer = null;

function pinIcon(className) {
  return L.divIcon({ className: '', html: `<div class="marker-pin ${className}"></div>`, iconSize: [22, 22], iconAnchor: [11, 22] });
}

function addOsmTiles(map) {
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
  }).addTo(map);
}

function syncMarkerFromInputs(marker, latInput, lngInput) {
  const lat = Number(latInput.value);
  const lng = Number(lngInput.value);
  if (Number.isFinite(lat) && Number.isFinite(lng)) marker.setLatLng([lat, lng]);
}

function initRiderMap() {
  if (riderMap) return;
  const pickupLatInput = document.querySelector('input[name="pickupLat"]');
  const pickupLngInput = document.querySelector('input[name="pickupLng"]');
  const dropoffLatInput = document.querySelector('input[name="dropoffLat"]');
  const dropoffLngInput = document.querySelector('input[name="dropoffLng"]');

  const pickup = [Number(pickupLatInput.value), Number(pickupLngInput.value)];
  const dropoff = [Number(dropoffLatInput.value), Number(dropoffLngInput.value)];

  riderMap = L.map('riderMap').setView(pickup, 13);
  addOsmTiles(riderMap);

  pickupMarker = L.marker(pickup, { icon: pinIcon('pickup'), draggable: true }).addTo(riderMap).bindTooltip('Pickup');
  dropoffMarker = L.marker(dropoff, { icon: pinIcon('dropoff'), draggable: true }).addTo(riderMap).bindTooltip('Dropoff');

  pickupMarker.on('dragend', () => {
    const { lat, lng } = pickupMarker.getLatLng();
    pickupLatInput.value = lat.toFixed(6);
    pickupLngInput.value = lng.toFixed(6);
  });
  dropoffMarker.on('dragend', () => {
    const { lat, lng } = dropoffMarker.getLatLng();
    dropoffLatInput.value = lat.toFixed(6);
    dropoffLngInput.value = lng.toFixed(6);
  });

  [pickupLatInput, pickupLngInput].forEach((el) =>
    el.addEventListener('change', () => syncMarkerFromInputs(pickupMarker, pickupLatInput, pickupLngInput)));
  [dropoffLatInput, dropoffLngInput].forEach((el) =>
    el.addEventListener('change', () => syncMarkerFromInputs(dropoffMarker, dropoffLatInput, dropoffLngInput)));

  // "Choose on map": arm which pin the next map click should place, since a bare click is otherwise
  // ambiguous about whether it means pickup or dropoff. One click places the pin and disarms; dragging
  // the pins directly still works at any time regardless of this mode.
  const pickPickupBtn = document.getElementById('pickPickupBtn');
  const pickDropoffBtn = document.getElementById('pickDropoffBtn');
  let pickMode = null;

  function setPickMode(mode) {
    pickMode = pickMode === mode ? null : mode;
    pickPickupBtn.classList.toggle('armed', pickMode === 'pickup');
    pickDropoffBtn.classList.toggle('armed', pickMode === 'dropoff');
    riderMap.getContainer().style.cursor = pickMode ? 'crosshair' : '';
  }
  pickPickupBtn.addEventListener('click', () => setPickMode('pickup'));
  pickDropoffBtn.addEventListener('click', () => setPickMode('dropoff'));

  riderMap.on('click', (e) => {
    if (!pickMode) return;
    const { lat, lng } = e.latlng;
    if (pickMode === 'pickup') {
      pickupMarker.setLatLng([lat, lng]);
      pickupLatInput.value = lat.toFixed(6);
      pickupLngInput.value = lng.toFixed(6);
    } else {
      dropoffMarker.setLatLng([lat, lng]);
      dropoffLatInput.value = lat.toFixed(6);
      dropoffLngInput.value = lng.toFixed(6);
    }
    setPickMode(pickMode); // toggles back off
  });

  // Real device location if the rider allows it — falls back to the demo default already in the inputs.
  if (navigator.geolocation) {
    navigator.geolocation.getCurrentPosition((pos) => {
      const { latitude, longitude } = pos.coords;
      pickupLatInput.value = latitude.toFixed(6);
      pickupLngInput.value = longitude.toFixed(6);
      pickupMarker.setLatLng([latitude, longitude]);
      riderMap.setView([latitude, longitude], 14);
    }, () => { /* permission denied or unavailable — keep the default pickup point */ }, { timeout: 8000 });
  }

  // A map initialized while its container was hidden (display:none) measures zero size — this section
  // was just unhidden by render(), so force a re-measure once the layout has settled.
  setTimeout(() => riderMap.invalidateSize(), 200);
}

/** Shows the driver's live position once one exists — the assigned driver if matched, otherwise whoever the outstanding MATCHING offer went to. */
function updateRiderDriverMarker(trip) {
  if (!riderMap) return;
  const party = trip.driver || trip.offeredDriver;
  if (party && party.lat != null && party.lng != null) {
    const pos = [party.lat, party.lng];
    if (riderDriverMarker) {
      riderDriverMarker.setLatLng(pos);
    } else {
      riderDriverMarker = L.marker(pos, { icon: pinIcon('driver') }).addTo(riderMap).bindTooltip(driverLabel(party));
    }
  } else if (riderDriverMarker) {
    riderMap.removeLayer(riderDriverMarker);
    riderDriverMarker = null;
  }
}

function initDriverMap() {
  if (driverMap) return;
  const latInput = document.getElementById('driverLat');
  const lngInput = document.getElementById('driverLng');
  const start = [Number(latInput.value), Number(lngInput.value)];

  driverMap = L.map('driverMap').setView(start, 13);
  addOsmTiles(driverMap);

  driverSelfMarker = L.marker(start, { icon: pinIcon('self'), draggable: true }).addTo(driverMap).bindTooltip('You');
  driverSelfMarker.on('dragend', () => {
    const { lat, lng } = driverSelfMarker.getLatLng();
    latInput.value = lat.toFixed(6);
    lngInput.value = lng.toFixed(6);
  });
  [latInput, lngInput].forEach((el) =>
    el.addEventListener('change', () => syncMarkerFromInputs(driverSelfMarker, latInput, lngInput)));

  if (navigator.geolocation) {
    navigator.geolocation.getCurrentPosition((pos) => {
      const { latitude, longitude } = pos.coords;
      latInput.value = latitude.toFixed(6);
      lngInput.value = longitude.toFixed(6);
      driverSelfMarker.setLatLng([latitude, longitude]);
      driverMap.setView([latitude, longitude], 14);
    }, () => { /* permission denied or unavailable — keep the default */ }, { timeout: 8000 });

    // Continuously tracks real GPS movement for as long as this page stays open. Dragging the marker or
    // editing the fields by hand still works as a manual override — useful for testing several simulated
    // drivers from one machine, where real GPS would otherwise report the same position for all of them.
    navigator.geolocation.watchPosition((pos) => {
      const { latitude, longitude } = pos.coords;
      latInput.value = latitude.toFixed(6);
      lngInput.value = longitude.toFixed(6);
      driverSelfMarker.setLatLng([latitude, longitude]);
    }, () => { /* ignore watch errors — manual entry still works */ }, { enableHighAccuracy: true, maximumAge: 5000 });
  }

  startDriverAutoPing();
  setTimeout(() => driverMap.invalidateSize(), 200);
}

/** Pickup/dropoff pins on the driver's own map — shown for a pending offer or an active trip, cleared once neither applies. */
function updateDriverTripMarkers(trip) {
  if (!driverMap) return;
  if (trip) {
    const pickup = [trip.pickupLat, trip.pickupLng];
    const dropoff = [trip.dropoffLat, trip.dropoffLng];
    if (driverPickupMarker) driverPickupMarker.setLatLng(pickup);
    else driverPickupMarker = L.marker(pickup, { icon: pinIcon('pickup') }).addTo(driverMap).bindTooltip('Pickup');
    if (driverDropoffMarker) driverDropoffMarker.setLatLng(dropoff);
    else driverDropoffMarker = L.marker(dropoff, { icon: pinIcon('dropoff') }).addTo(driverMap).bindTooltip('Dropoff');
  } else {
    if (driverPickupMarker) { driverMap.removeLayer(driverPickupMarker); driverPickupMarker = null; }
    if (driverDropoffMarker) { driverMap.removeLayer(driverDropoffMarker); driverDropoffMarker = null; }
  }
}

// ---- Route (shown on both maps while a trip is IN_PROGRESS) ----
// OSRM's public demo server — free, no API key, but a best-effort service not meant for production
// traffic. If it's unreachable or rate-limited, we just skip the route line rather than block the UI;
// the pickup/dropoff pins alone still convey the trip.

let riderRouteLine = null;
let riderRouteTripId = null;
let driverRouteLine = null;
let driverRouteTripId = null;

async function fetchRouteLatLngs(pickupLat, pickupLng, dropoffLat, dropoffLng) {
  try {
    const url = `https://router.project-osrm.org/route/v1/driving/${pickupLng},${pickupLat};${dropoffLng},${dropoffLat}?overview=full&geometries=geojson`;
    const res = await fetch(url);
    if (!res.ok) return null;
    const data = await res.json();
    if (data.code !== 'Ok' || !data.routes || !data.routes.length) return null;
    // GeoJSON coordinates are [lng, lat] — Leaflet wants [lat, lng].
    return data.routes[0].geometry.coordinates.map(([lng, lat]) => [lat, lng]);
  } catch (err) {
    return null;
  }
}

async function showRiderRoute(trip) {
  if (!riderMap || riderRouteTripId === trip.tripId) return;
  const latlngs = await fetchRouteLatLngs(trip.pickupLat, trip.pickupLng, trip.dropoffLat, trip.dropoffLng);
  if (!latlngs || riderRouteTripId === trip.tripId) return; // trip may have moved on while the request was in flight
  clearRiderRoute();
  riderRouteLine = L.polyline(latlngs, { color: '#4a90d9', weight: 4, opacity: 0.85 }).addTo(riderMap);
  riderRouteTripId = trip.tripId;
  riderMap.fitBounds(riderRouteLine.getBounds(), { padding: [24, 24] });
}

function clearRiderRoute() {
  if (riderRouteLine && riderMap) riderMap.removeLayer(riderRouteLine);
  riderRouteLine = null;
  riderRouteTripId = null;
}

async function showDriverRoute(trip) {
  if (!driverMap || driverRouteTripId === trip.tripId) return;
  const latlngs = await fetchRouteLatLngs(trip.pickupLat, trip.pickupLng, trip.dropoffLat, trip.dropoffLng);
  if (!latlngs || driverRouteTripId === trip.tripId) return;
  clearDriverRoute();
  driverRouteLine = L.polyline(latlngs, { color: '#4a90d9', weight: 4, opacity: 0.85 }).addTo(driverMap);
  driverRouteTripId = trip.tripId;
  driverMap.fitBounds(driverRouteLine.getBounds(), { padding: [24, 24] });
}

function clearDriverRoute() {
  if (driverRouteLine && driverMap) driverMap.removeLayer(driverRouteLine);
  driverRouteLine = null;
  driverRouteTripId = null;
}

/** Runs continuously in the background; only actually pings while the driver is AVAILABLE-or-better (goOnline succeeded, no goOffline yet). */
function startDriverAutoPing() {
  if (driverAutoPingTimer) return;
  driverAutoPingTimer = setInterval(() => {
    if (!driverIsOnline) return;
    const lat = Number(document.getElementById('driverLat').value);
    const lng = Number(document.getElementById('driverLng').value);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;
    api('/v1/drivers/location', { method: 'POST', body: JSON.stringify({ lat, lng }) }).catch(() => { /* next tick retries */ });
  }, 4000);
}

// ---- Auth ----

document.querySelectorAll('.tab').forEach((tab) => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach((t) => t.classList.remove('active'));
    tab.classList.add('active');
    const isLogin = tab.dataset.tab === 'login';
    document.getElementById('loginForm').classList.toggle('hidden', !isLogin);
    document.getElementById('registerForm').classList.toggle('hidden', isLogin);
  });
});

document.querySelector('select[name="accountType"]').addEventListener('change', (e) => {
  document.getElementById('vehicleFields').classList.toggle('hidden', e.target.value !== 'driver');
});

document.getElementById('loginForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const form = new FormData(e.target);
  const resultEl = document.getElementById('loginResult');
  try {
    const result = await api('/v1/auth/login', { method: 'POST', body: JSON.stringify({ email: form.get('email'), password: form.get('password') }) });
    saveSession(result);
    resultEl.textContent = '';
    render();
  } catch (err) {
    resultEl.textContent = err.message;
    resultEl.className = 'result error';
  }
});

document.getElementById('registerForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const form = new FormData(e.target);
  const resultEl = document.getElementById('registerResult');
  const isDriver = form.get('accountType') === 'driver';
  const path = isDriver ? '/v1/auth/register/driver' : '/v1/auth/register/rider';
  const payload = { name: form.get('name'), email: form.get('email'), password: form.get('password') };
  if (isDriver) {
    payload.vehicle = { plate: form.get('plate'), make: form.get('make'), model: form.get('model'), productType: form.get('productType') };
  }
  try {
    const result = await api(path, { method: 'POST', body: JSON.stringify(payload) });
    saveSession(result);
    resultEl.textContent = '';
    render();
  } catch (err) {
    resultEl.textContent = err.message;
    resultEl.className = 'result error';
  }
});

document.getElementById('logoutBtn').addEventListener('click', async () => {
  await api('/v1/auth/logout', { method: 'POST' }).catch(() => {});
  clearSession();
  if (pollTimer) clearInterval(pollTimer);
  render();
  document.getElementById('loginResult').textContent = '';
});

// ---- Rider ----

let currentTripId = null;
const RIDER_CANCELLABLE_STATUSES = ['REQUESTED', 'MATCHING', 'MATCHED', 'DRIVER_ARRIVING'];

document.getElementById('tripForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const form = new FormData(e.target);
  const resultEl = document.getElementById('tripResult');
  try {
    const trip = await api('/v1/trips', {
      method: 'POST',
      body: JSON.stringify({
        pickupLat: Number(form.get('pickupLat')), pickupLng: Number(form.get('pickupLng')),
        dropoffLat: Number(form.get('dropoffLat')), dropoffLng: Number(form.get('dropoffLng')),
      }),
    });
    resultEl.textContent = '';
    currentTripId = trip.tripId;
    clearRiderRoute();
    document.getElementById('cancelTripResult').textContent = '';
    document.getElementById('tripStatusSection').classList.remove('hidden');
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = setInterval(() => pollTripStatus(trip.tripId), 1000);
    pollTripStatus(trip.tripId);
  } catch (err) {
    resultEl.textContent = err.message;
    resultEl.className = 'result error';
  }
});

document.getElementById('cancelTripBtn').addEventListener('click', async () => {
  const resultEl = document.getElementById('cancelTripResult');
  if (!currentTripId) return;
  try {
    await api(`/v1/trips/${currentTripId}/cancel`, { method: 'POST' });
    resultEl.textContent = '';
    pollTripStatus(currentTripId);
  } catch (err) {
    resultEl.textContent = err.message;
    resultEl.className = 'result error';
  }
});

async function pollTripStatus(tripId) {
  try {
    const trip = await api(`/v1/trips/${tripId}`);
    document.getElementById('tripStatusBadge').textContent = trip.status;
    document.getElementById('tripStatusList').innerHTML = `
      <li><span>Trip ID</span><span>${trip.tripId}</span></li>
      ${tripPartyRows(trip)}
    `;
    document.getElementById('cancelTripBtn').classList.toggle('hidden', !RIDER_CANCELLABLE_STATUSES.includes(trip.status));
    updateRiderDriverMarker(trip);
    if (trip.status === 'IN_PROGRESS') {
      showRiderRoute(trip);
    } else {
      clearRiderRoute();
    }
    if (['COMPLETED', 'CANCELLED_BY_RIDER', 'CANCELLED_BY_DRIVER', 'NO_DRIVERS_FOUND'].includes(trip.status) && pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
      loadRiderHistory();
    }
  } catch (err) {
    // transient — next tick retries
  }
}

/** During MATCHING there's no confirmed driver yet, only an outstanding offer — show that driver's name/cab instead of a bare offeredDriverId. */
function tripPartyRows(trip) {
  if (trip.driver) {
    return `
      <li><span>Driver</span><span>${driverLabel(trip.driver)}</span></li>
      <li><span>Cab</span><span>${vehicleLabel(trip.driver.vehicle)}</span></li>
    `;
  }
  if (trip.offeredDriver) {
    return `
      <li><span>Offer sent to</span><span>${driverLabel(trip.offeredDriver)} (awaiting response)</span></li>
      <li><span>Cab</span><span>${vehicleLabel(trip.offeredDriver.vehicle)}</span></li>
    `;
  }
  return `<li><span>Driver</span><span>Searching for a nearby driver…</span></li>`;
}

function vehicleLabel(vehicle) {
  if (!vehicle) return '—';
  const makeModel = [vehicle.make, vehicle.model].filter(Boolean).join(' ');
  return `${vehicle.plate}${makeModel ? ' — ' + makeModel : ''} (${vehicle.productType})`;
}

function driverLabel(party) {
  return party ? `${party.name}${party.rating != null ? ' (' + party.rating.toFixed(1) + '★)' : ''}` : '—';
}

function riderLabel(party) {
  return party ? `${party.name}${party.rating != null ? ' (' + party.rating.toFixed(1) + '★)' : ''}` : '—';
}

function formatWhen(iso) {
  return iso ? new Date(iso).toLocaleString() : '—';
}

document.getElementById('refreshRiderHistoryBtn').addEventListener('click', loadRiderHistory);
document.getElementById('refreshDriverHistoryBtn').addEventListener('click', loadDriverHistory);

async function loadRiderHistory() {
  const listEl = document.getElementById('riderHistoryList');
  try {
    const trips = await api('/v1/trips');
    renderHistory(listEl, trips, (trip) => `Driver: ${driverLabel(trip.driver)}${trip.driver && trip.driver.vehicle ? ' — ' + vehicleLabel(trip.driver.vehicle) : ''}`);
  } catch (err) {
    listEl.innerHTML = `<li><span>Error</span><span>${err.message}</span></li>`;
  }
}

async function loadDriverHistory() {
  const listEl = document.getElementById('driverHistoryList');
  try {
    const trips = await api('/v1/trips');
    renderHistory(listEl, trips, (trip) => `Rider: ${riderLabel(trip.rider)}`);
  } catch (err) {
    listEl.innerHTML = `<li><span>Error</span><span>${err.message}</span></li>`;
  }
}

function renderHistory(listEl, trips, counterpartLine) {
  if (!trips.length) {
    listEl.innerHTML = '<li><span>No trips yet</span><span></span></li>';
    return;
  }
  listEl.innerHTML = trips
    .slice()
    .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
    .map((trip) => `
      <li><span>${formatWhen(trip.createdAt)} — ${trip.status}</span><span>${counterpartLine(trip)}</span></li>
    `)
    .join('');
}

// ---- Driver ----

document.getElementById('goOnlineBtn').addEventListener('click', async () => {
  const lat = Number(document.getElementById('driverLat').value);
  const lng = Number(document.getElementById('driverLng').value);
  const resultEl = document.getElementById('driverResult');
  try {
    await api('/v1/drivers/online', { method: 'POST', body: JSON.stringify({ lat, lng }) });
    driverIsOnline = true;
    resultEl.textContent = 'Online — searchable for nearby trip requests.';
    resultEl.className = 'result success';
  } catch (err) {
    resultEl.textContent = err.message;
    resultEl.className = 'result error';
  }
});

document.getElementById('goOfflineBtn').addEventListener('click', async () => {
  const resultEl = document.getElementById('driverResult');
  try {
    await api('/v1/drivers/offline', { method: 'POST' });
    driverIsOnline = false;
    resultEl.textContent = 'Offline.';
    resultEl.className = 'result success';
  } catch (err) {
    resultEl.textContent = err.message;
    resultEl.className = 'result error';
  }
});

document.getElementById('pingBtn').addEventListener('click', async () => {
  const lat = Number(document.getElementById('driverLat').value);
  const lng = Number(document.getElementById('driverLng').value);
  const resultEl = document.getElementById('driverResult');
  try {
    await api('/v1/drivers/location', { method: 'POST', body: JSON.stringify({ lat, lng }) });
    resultEl.textContent = 'Location updated.';
    resultEl.className = 'result success';
  } catch (err) {
    resultEl.textContent = err.message;
    resultEl.className = 'result error';
  }
});

let wasActive = false;

async function pollDriverActiveTrip() {
  if (pollTimer) return;
  pollTimer = setInterval(async () => {
    try {
      const trip = await api('/v1/drivers/me/active-trip');
      wasActive = true;
      renderDriverTrip(trip);
    } catch (err) {
      document.getElementById('offerSection').classList.add('hidden');
      document.getElementById('activeTripSection').classList.add('hidden');
      updateDriverTripMarkers(null);
      clearDriverRoute();
      if (wasActive) {
        wasActive = false;
        loadDriverHistory();
      }
    }
  }, 1000);
}

function renderDriverTrip(trip) {
  const offerSection = document.getElementById('offerSection');
  const activeSection = document.getElementById('activeTripSection');
  if (trip.status === 'MATCHING' && trip.offeredDriverId === state.accountId) {
    offerSection.classList.remove('hidden');
    activeSection.classList.add('hidden');
    document.getElementById('offerStatusList').innerHTML = `
      <li><span>Trip ID</span><span>${trip.tripId}</span></li>
      <li><span>Rider</span><span>${riderLabel(trip.rider)}</span></li>
      <li><span>Pickup</span><span>${trip.pickupLat.toFixed(4)}, ${trip.pickupLng.toFixed(4)}</span></li>
      <li><span>Dropoff</span><span>${trip.dropoffLat.toFixed(4)}, ${trip.dropoffLng.toFixed(4)}</span></li>
    `;
    document.getElementById('acceptBtn').onclick = () => respondToOffer(trip.tripId, true);
    document.getElementById('rejectBtn').onclick = () => respondToOffer(trip.tripId, false);
    updateDriverTripMarkers(trip);
  } else if (['MATCHED', 'DRIVER_ARRIVING', 'ARRIVED', 'IN_PROGRESS'].includes(trip.status)) {
    offerSection.classList.add('hidden');
    activeSection.classList.remove('hidden');
    document.getElementById('activeTripList').innerHTML = `
      <li><span>Trip ID</span><span>${trip.tripId}</span></li>
      <li><span>Rider</span><span>${riderLabel(trip.rider)}</span></li>
      <li><span>Status</span><span>${trip.status}</span></li>
    `;
    document.getElementById('arrivedBtn').onclick = () => tripAction(trip.tripId, 'arrived');
    document.getElementById('startBtn').onclick = () => tripAction(trip.tripId, 'start');
    document.getElementById('completeBtn').onclick = () => tripAction(trip.tripId, 'complete');
    updateDriverTripMarkers(trip);
    if (trip.status === 'IN_PROGRESS') {
      showDriverRoute(trip);
    } else {
      clearDriverRoute();
    }
  } else {
    offerSection.classList.add('hidden');
    activeSection.classList.add('hidden');
    updateDriverTripMarkers(null);
    clearDriverRoute();
  }
}

async function respondToOffer(tripId, accept) {
  await api(`/v1/drivers/offers/${tripId}/respond`, { method: 'POST', body: JSON.stringify({ accept }) });
}

async function tripAction(tripId, action) {
  await api(`/v1/trips/${tripId}/${action}`, { method: 'POST' });
}

render();
