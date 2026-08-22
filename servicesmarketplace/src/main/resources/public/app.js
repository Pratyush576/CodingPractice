const state = {
  token: localStorage.getItem('sm_token'),
  accountId: localStorage.getItem('sm_accountId'),
  accountType: localStorage.getItem('sm_accountType'),
  name: localStorage.getItem('sm_name'),
};

function api(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  return fetch(path, { ...options, headers }).then(async (res) => {
    const text = await res.text();
    const body = text ? JSON.parse(text) : null;
    if (!res.ok) {
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
  localStorage.setItem('sm_token', result.token);
  localStorage.setItem('sm_accountId', result.accountId);
  localStorage.setItem('sm_accountType', result.accountType);
  localStorage.setItem('sm_name', result.name);
}

function clearSession() {
  state.token = state.accountId = state.accountType = state.name = null;
  localStorage.removeItem('sm_token');
  localStorage.removeItem('sm_accountId');
  localStorage.removeItem('sm_accountType');
  localStorage.removeItem('sm_name');
}

function render() {
  const authSection = document.getElementById('authSection');
  const customerSection = document.getElementById('customerSection');
  const proSection = document.getElementById('proSection');
  const accountBar = document.getElementById('accountBar');

  if (!state.token) {
    authSection.classList.remove('hidden');
    customerSection.classList.add('hidden');
    proSection.classList.add('hidden');
    accountBar.classList.add('hidden');
    return;
  }
  authSection.classList.add('hidden');
  accountBar.classList.remove('hidden');
  document.getElementById('accountInfo').textContent = `${state.name} (${state.accountType})`;

  if (state.accountType === 'CUSTOMER') {
    customerSection.classList.remove('hidden');
    proSection.classList.add('hidden');
    loadRequests();
  } else {
    proSection.classList.remove('hidden');
    customerSection.classList.add('hidden');
    loadBalance();
    loadLeads();
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

document.querySelector('#registerForm select[name="accountType"]').addEventListener('change', (e) => {
  const isPro = e.target.value === 'pro';
  document.getElementById('profileFields').classList.toggle('hidden', !isPro);
  document.getElementById('nameLabel').firstChild.textContent = isPro ? 'Business name ' : 'Full name ';
});

document.getElementById('loginForm').addEventListener('submit', (e) => {
  e.preventDefault();
  const form = new FormData(e.target);
  const resultEl = document.getElementById('loginResult');
  api('/v1/auth/login', { method: 'POST', body: JSON.stringify({ email: form.get('email'), password: form.get('password') }) })
    .then((result) => { saveSession(result); render(); })
    .catch((err) => { resultEl.textContent = err.message; resultEl.className = 'result error'; });
});

document.getElementById('registerForm').addEventListener('submit', (e) => {
  e.preventDefault();
  const form = new FormData(e.target);
  const resultEl = document.getElementById('registerResult');
  const accountType = form.get('accountType');
  const path = accountType === 'pro' ? '/v1/auth/register/pro' : '/v1/auth/register/customer';
  const body = accountType === 'pro'
    ? {
        businessName: form.get('name'),
        email: form.get('email'),
        password: form.get('password'),
        profile: {
          categoryId: form.get('categoryId'),
          lat: parseFloat(form.get('lat')),
          lng: parseFloat(form.get('lng')),
          radiusKm: parseFloat(form.get('radiusKm')),
          startingPrice: parseFloat(form.get('startingPrice')),
        },
      }
    : { name: form.get('name'), email: form.get('email'), password: form.get('password') };
  api(path, { method: 'POST', body: JSON.stringify(body) })
    .then((result) => { saveSession(result); render(); })
    .catch((err) => { resultEl.textContent = err.message; resultEl.className = 'result error'; });
});

document.getElementById('logoutBtn').addEventListener('click', () => {
  api('/v1/auth/logout', { method: 'POST' }).catch(() => {}).finally(() => { clearSession(); render(); });
});

// ---- Customer ----

document.getElementById('requestForm').addEventListener('submit', (e) => {
  e.preventDefault();
  const form = new FormData(e.target);
  const resultEl = document.getElementById('requestResult');
  const body = {
    categoryId: form.get('categoryId'),
    answers: { details: form.get('details') || '' },
    lat: parseFloat(form.get('lat')),
    lng: parseFloat(form.get('lng')),
    desiredTiming: form.get('desiredTiming') || null,
  };
  api('/v1/requests', { method: 'POST', body: JSON.stringify(body) })
    .then(() => { resultEl.textContent = 'Request posted — matching Pros now.'; resultEl.className = 'result success'; e.target.reset(); loadRequests(); })
    .catch((err) => { resultEl.textContent = err.message; resultEl.className = 'result error'; });
});

document.getElementById('refreshRequestsBtn').addEventListener('click', loadRequests);

function loadRequests() {
  const listEl = document.getElementById('requestsList');
  api('/v1/requests/mine')
    .then((requests) => Promise.all(requests.map((r) => api(`/v1/requests/${r.requestId}/quotes`).then((quotes) => ({ request: r, quotes })))))
    .then((rows) => {
      listEl.innerHTML = '';
      if (rows.length === 0) {
        listEl.innerHTML = '<li class="empty">No requests yet</li>';
        return;
      }
      rows.forEach(({ request, quotes }) => {
        const li = document.createElement('li');
        const line = document.createElement('div');
        line.className = 'line';
        line.innerHTML = `<span>${request.categoryId}</span><span class="badge">${request.status}</span>`;
        li.appendChild(line);
        if (quotes.length === 0) {
          const none = document.createElement('div');
          none.className = 'hint';
          none.style.margin = '0';
          none.textContent = 'No quotes yet';
          li.appendChild(none);
        } else {
          quotes.forEach((q) => {
            const qLine = document.createElement('div');
            qLine.className = 'line';
            qLine.innerHTML = `<span>Quote — $${q.price.toFixed(2)} (${q.status})</span>`;
            if (q.status === 'PENDING' && request.status === 'OPEN') {
              const btn = document.createElement('button');
              btn.textContent = 'Hire';
              btn.addEventListener('click', () => hire(request.requestId, q.quoteId));
              const actions = document.createElement('span');
              actions.appendChild(btn);
              qLine.appendChild(actions);
            }
            li.appendChild(qLine);
          });
        }
        listEl.appendChild(li);
      });
    })
    .catch((err) => { listEl.innerHTML = `<li class="empty">${err.message}</li>`; });
}

function hire(requestId, quoteId) {
  api(`/v1/requests/${requestId}/hire`, { method: 'POST', body: JSON.stringify({ quoteId }) })
    .then(loadRequests)
    .catch((err) => alert(err.message));
}

// ---- Pro ----

function loadBalance() {
  api('/v1/credits/balance')
    .then((res) => { document.querySelector('#creditBalance .amount').textContent = res.balance.toFixed(2); })
    .catch(() => {});
}

document.getElementById('purchaseCreditsBtn').addEventListener('click', () => {
  const amount = parseFloat(document.getElementById('purchaseAmount').value);
  const resultEl = document.getElementById('creditResult');
  api('/v1/credits/purchase', { method: 'POST', body: JSON.stringify({ amount }) })
    .then((res) => { document.querySelector('#creditBalance .amount').textContent = res.balance.toFixed(2); resultEl.textContent = 'Credits added.'; resultEl.className = 'result success'; })
    .catch((err) => { resultEl.textContent = err.message; resultEl.className = 'result error'; });
});

document.getElementById('refreshLeadsBtn').addEventListener('click', loadLeads);

function loadLeads() {
  const listEl = document.getElementById('leadsList');
  api('/v1/leads/mine')
    .then((leads) => {
      listEl.innerHTML = '';
      if (leads.length === 0) {
        listEl.innerHTML = '<li class="empty">No leads yet</li>';
        return;
      }
      leads.forEach((lead) => {
        const li = document.createElement('li');
        const line = document.createElement('div');
        line.className = 'line';
        line.innerHTML = `<span>Lead — ${lead.creditCost} credits</span><span class="badge">${lead.status}</span>`;
        li.appendChild(line);

        if (lead.status === 'DELIVERED') {
          const actions = document.createElement('div');
          actions.className = 'actions';
          const btn = document.createElement('button');
          btn.textContent = 'Unlock';
          btn.addEventListener('click', () => unlockLead(lead.leadId));
          actions.appendChild(btn);
          li.appendChild(actions);
        } else if (lead.status === 'UNLOCKED') {
          const form = document.createElement('form');
          form.className = 'row';
          form.style.marginTop = '0.3rem';
          form.innerHTML = `
            <input type="number" step="any" placeholder="price" required style="max-width:100px">
            <input type="text" placeholder="message (optional)">
            <button type="submit">Send Quote</button>
          `;
          form.addEventListener('submit', (e) => {
            e.preventDefault();
            const inputs = form.querySelectorAll('input');
            sendQuote(lead.leadId, parseFloat(inputs[0].value), inputs[1].value);
          });
          li.appendChild(form);
        }
        listEl.appendChild(li);
      });
    })
    .catch((err) => { listEl.innerHTML = `<li class="empty">${err.message}</li>`; });
}

function unlockLead(leadId) {
  api(`/v1/leads/${leadId}/unlock`, { method: 'POST' })
    .then(() => { loadLeads(); loadBalance(); })
    .catch((err) => alert(err.message));
}

function sendQuote(leadId, price, message) {
  api(`/v1/leads/${leadId}/quote`, { method: 'POST', body: JSON.stringify({ price, message }) })
    .then(loadLeads)
    .catch((err) => alert(err.message));
}

render();
