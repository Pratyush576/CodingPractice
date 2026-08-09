const tokenInput = document.getElementById('adminTokenInput');
const savedToken = localStorage.getItem('cab_admin_token');
if (savedToken) tokenInput.value = savedToken;

document.getElementById('saveTokenBtn').addEventListener('click', () => {
  localStorage.setItem('cab_admin_token', tokenInput.value);
  const resultEl = document.getElementById('tokenResult');
  resultEl.textContent = 'Saved.';
  resultEl.className = 'result success';
  loadAll();
});

let statsWindow = null;
document.querySelectorAll('.stats-window').forEach((btn, i) => {
  if (i === 0) {
    btn.classList.add('armed');
    statsWindow = btn.dataset.window;
  }
  btn.addEventListener('click', () => {
    document.querySelectorAll('.stats-window').forEach((b) => b.classList.remove('armed'));
    btn.classList.add('armed');
    statsWindow = btn.dataset.window;
    loadAll();
  });
});
document.getElementById('refreshStatsBtn').addEventListener('click', loadAll);

function loadAll() {
  loadStats();
  loadTrips();
}

async function adminFetch(path) {
  const token = localStorage.getItem('cab_admin_token') || '';
  const res = await fetch(path, { headers: { 'X-Admin-Token': token } });
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(body && body.message ? body.message : `HTTP ${res.status}`);
  return body;
}

async function loadStats() {
  const resultEl = document.getElementById('statsResult');
  try {
    const stats = await adminFetch(`/v1/admin/stats?window=${statsWindow}`);
    resultEl.textContent = '';
    renderOutcomes(stats);
    renderFinancials(stats);
  } catch (err) {
    resultEl.textContent = err.message;
    resultEl.className = 'result error';
  }
}

function renderCards(gridEl, cards) {
  gridEl.innerHTML = cards
    .map(([cls, value, label]) => `
      <div class="stat-card ${cls}"><div class="value">${value}</div><div class="label">${label}</div></div>
    `)
    .join('');
}

/** "—" rather than a misleading 0% or a divide-by-zero NaN when the denominator has no data yet in this window. */
function pct(numerator, denominator) {
  return denominator > 0 ? `${((numerator / denominator) * 100).toFixed(1)}%` : '—';
}

function perTrip(total, count) {
  return count > 0 ? `$${(total / count).toFixed(2)}` : '—';
}

function renderOutcomes(stats) {
  const cancelled = stats.cancelledByRider + stats.cancelledByDriver;
  renderCards(document.getElementById('outcomeGrid'), [
    ['accent', stats.requested, 'Requested'],
    ['accent', stats.matched, 'Matched'],
    ['good', stats.completed, 'Completed'],
    ['bad', cancelled, 'Cancelled'],
    ['bad', stats.noDriversFound, 'No drivers found'],
  ]);
}

function renderFinancials(stats) {
  const margin = stats.totalRevenue - stats.totalPayouts;
  renderCards(document.getElementById('financialGrid'), [
    ['good', `$${stats.totalRevenue.toFixed(2)}`, 'Revenue (collected)'],
    ['', `$${stats.totalFareValue.toFixed(2)}`, 'Fare value (gross)'],
    [stats.declinedPaymentsCount > 0 ? 'bad' : '', pct(stats.totalRevenue, stats.totalFareValue), 'Collection rate'],
    ['', perTrip(stats.totalFareValue, stats.completed), 'Avg fare / trip'],
    ['', `$${stats.totalPayouts.toFixed(2)}`, 'Driver payouts'],
    ['', perTrip(stats.totalPayouts, stats.payoutsCount), 'Avg payout / trip'],
    ['accent', `$${margin.toFixed(2)}`, 'Platform margin'],
    ['accent', pct(margin, stats.totalRevenue), 'Take rate'],
    [stats.declinedPaymentsCount > 0 ? 'bad' : '', `$${stats.declinedPaymentsAmount.toFixed(2)} (${stats.declinedPaymentsCount})`, 'Declined charges'],
    [stats.failedPayoutsCount > 0 ? 'bad' : '', `$${stats.failedPayoutsAmount.toFixed(2)} (${stats.failedPayoutsCount})`, 'Failed payouts'],
  ]);
}

// ---- All Requests (per-trip financial detail, with filters) ----

let allTrips = [];

document.getElementById('tripStatusFilter').addEventListener('change', renderTrips);
document.getElementById('tripSearchInput').addEventListener('input', renderTrips);

async function loadTrips() {
  const resultEl = document.getElementById('tripsResult');
  try {
    allTrips = await adminFetch(`/v1/admin/trips?window=${statsWindow}`);
    resultEl.textContent = '';
    renderTrips();
  } catch (err) {
    allTrips = [];
    resultEl.textContent = err.message;
    resultEl.className = 'result error';
    renderTrips();
  }
}

function tripBadgeClass(status) {
  if (status === 'COMPLETED') return 'status-good';
  if (status === 'IN_PROGRESS') return 'status-live';
  if (['CANCELLED_BY_RIDER', 'CANCELLED_BY_DRIVER', 'NO_DRIVERS_FOUND'].includes(status)) return 'status-bad';
  return '';
}

function formatWhen(iso) {
  return iso ? new Date(iso).toLocaleString() : '—';
}

function settlementCell(status, amount) {
  if (!status) return '—';
  const cls = status === 'CHARGED' || status === 'PAID' ? 'good' : 'bad';
  return `<span class="stat-card-inline ${cls}">${status} ($${amount.toFixed(2)})</span>`;
}

function renderTrips() {
  const statusFilter = document.getElementById('tripStatusFilter').value;
  const search = document.getElementById('tripSearchInput').value.trim().toLowerCase();
  const countEl = document.getElementById('tripsCount');

  const filtered = allTrips.filter((t) => {
    if (statusFilter && t.status !== statusFilter) return false;
    if (search) {
      const haystack = `${t.riderName || ''} ${t.driverName || ''} ${t.tripId}`.toLowerCase();
      if (!haystack.includes(search)) return false;
    }
    return true;
  });

  countEl.textContent = `Showing ${filtered.length} of ${allTrips.length} requests in this window.`;

  const tbody = document.getElementById('tripsTableBody');
  if (!filtered.length) {
    tbody.innerHTML = '<tr><td colspan="9" class="empty-row">No requests match these filters</td></tr>';
    return;
  }
  tbody.innerHTML = filtered
    .map((t) => `
      <tr>
        <td>${formatWhen(t.createdAt)}</td>
        <td><span class="badge ${tripBadgeClass(t.status)}">${t.status}</span></td>
        <td>${t.riderName || '—'}</td>
        <td>${t.driverName || '—'}</td>
        <td class="amount">${t.fareEstimate != null ? '$' + t.fareEstimate.toFixed(2) : '—'}</td>
        <td class="amount">${t.fareFinal != null ? '$' + t.fareFinal.toFixed(2) : '—'}</td>
        <td>${settlementCell(t.paymentStatus, t.paymentAmount)}</td>
        <td>${settlementCell(t.payoutStatus, t.payoutAmount)}</td>
        <td class="amount">${marginCell(t)}</td>
      </tr>
    `)
    .join('');
}

/**
 * Only meaningful when both sides actually settled (CHARGED + PAID) — same rule the aggregate "Platform
 * margin" card follows, so a row with a declined charge or a not-yet-attempted payout shows "—" instead
 * of a number that would silently misstate what the platform actually kept on that trip.
 */
function marginCell(t) {
  if (t.paymentStatus !== 'CHARGED' || t.payoutStatus !== 'PAID') return '—';
  const margin = t.paymentAmount - t.payoutAmount;
  return `$${margin.toFixed(2)}`;
}

loadAll();
