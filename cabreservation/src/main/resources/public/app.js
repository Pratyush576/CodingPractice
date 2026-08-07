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
    loadRiderHistory();
  } else {
    driverSection.classList.remove('hidden');
    riderSection.classList.add('hidden');
    pollDriverActiveTrip();
    loadDriverHistory();
  }
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
  } else {
    offerSection.classList.add('hidden');
    activeSection.classList.add('hidden');
  }
}

async function respondToOffer(tripId, accept) {
  await api(`/v1/drivers/offers/${tripId}/respond`, { method: 'POST', body: JSON.stringify({ accept }) });
}

async function tripAction(tripId, action) {
  await api(`/v1/trips/${tripId}/${action}`, { method: 'POST' });
}

render();
