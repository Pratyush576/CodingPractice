// Plain JS, no build step, no framework — matches every other demo in this repo.
// Talks to the same-origin REST API documented in LLD.md §2 and §15.

// ---------- Session ----------

function getSession() {
  const raw = localStorage.getItem("session");
  return raw ? JSON.parse(raw) : null;
}

function setSession(session) {
  localStorage.setItem("session", JSON.stringify(session));
}

function clearSession() {
  localStorage.removeItem("session");
}

async function api(method, path, body) {
  const session = getSession();
  const headers = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (session) headers["Authorization"] = "Bearer " + session.token;

  const res = await fetch(path, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;

  if (res.status === 401) {
    // Session is gone or expired server-side — drop it locally too and bounce to login.
    clearSession();
    showAuthView();
  }

  if (!res.ok) {
    const message = data && data.violations ? "Validation failed: " + data.violations.join("; ")
        : data && data.message ? data.message
        : res.statusText;
    const err = new Error(message);
    err.status = res.status;
    err.data = data;
    throw err;
  }
  return data;
}

// ---------- View switching ----------

function showAuthView() {
  document.getElementById("authSection").classList.remove("hidden");
  document.getElementById("appSection").classList.add("hidden");
  document.getElementById("accountBar").classList.add("hidden");
}

function showAppView(session) {
  document.getElementById("authSection").classList.add("hidden");
  document.getElementById("appSection").classList.remove("hidden");
  document.getElementById("accountBar").classList.remove("hidden");
  document.getElementById("accountInfo").textContent =
    `${session.name} · ${session.role} · tenant ${session.tenantId}`;

  const isShipper = session.role === "SHIPPER";
  document.getElementById("bookingsSectionTitle").firstChild.textContent = isShipper ? "My Bookings " : "Bookings ";
  document.getElementById("bookingsSectionRole").textContent = isShipper ? "Shipper" : "Operator";
  // An Operator never sees DRAFT bookings (still a Shipper's private, unsubmitted work) —
  // the backend already enforces this, but offering a filter that always returns nothing
  // would just be a confusing dead end.
  const draftOption = document.getElementById("statusFilterDraft");
  draftOption.hidden = !isShipper;
  if (!isShipper && document.getElementById("statusFilter").value === "DRAFT") {
    document.getElementById("statusFilter").value = "";
  }

  // Every tabbable section is full-width now — at most one is ever visible within a role's tab bar.
  ["createSection", "manageSection", "supplySection"].forEach((id) =>
    document.getElementById(id).classList.add("full-width")
  );

  if (isShipper) {
    document.getElementById("operatorTabs").classList.add("hidden");
    document.getElementById("shipperTabs").classList.remove("hidden");
    document.getElementById("supplySection").classList.add("hidden"); // not this role's tab bar's concern
    selectSectionTabIn("shipperTabs", "createSection");
  } else {
    document.getElementById("shipperTabs").classList.add("hidden");
    document.getElementById("operatorTabs").classList.remove("hidden");
    document.getElementById("createSection").classList.add("hidden"); // not this role's tab bar's concern
    selectSectionTabIn("operatorTabs", "manageSection");
    loadOfferings();
  }

  loadBookings();
}

/** Only toggles sections targeted by tabs within the given bar — the other role's tab bar (and its sections) is untouched. */
function selectSectionTabIn(tabBarId, targetId) {
  const tabBar = document.getElementById(tabBarId);
  tabBar.querySelectorAll(".section-tab").forEach((tab) => {
    tab.classList.toggle("active", tab.dataset.target === targetId);
    document.getElementById(tab.dataset.target).classList.toggle("hidden", tab.dataset.target !== targetId);
  });
}

document.querySelectorAll(".section-tab").forEach((tab) => {
  tab.addEventListener("click", () => selectSectionTabIn(tab.closest(".section-tabs").id, tab.dataset.target));
});

// ---------- Auth tabs ----------

document.querySelectorAll(".auth-tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".auth-tab").forEach((t) => t.classList.remove("active"));
    tab.classList.add("active");
    const isLogin = tab.dataset.tab === "login";
    document.getElementById("loginForm").classList.toggle("hidden", !isLogin);
    document.getElementById("registerForm").classList.toggle("hidden", isLogin);
  });
});

document.getElementById("loginForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.target;
  const resultBox = document.getElementById("loginResult");
  resultBox.className = "form-result";
  resultBox.textContent = "";
  try {
    const session = await api("POST", "/v1/auth/login", {
      email: form.email.value,
      password: form.password.value,
    });
    setSession(session);
    form.reset();
    showAppView(session);
  } catch (err) {
    resultBox.className = "form-result error";
    resultBox.textContent = err.message;
  }
});

// ---------- Tenant picker ----------

const NEW_TENANT_VALUE = "__new__";

async function loadTenants() {
  const select = document.getElementById("tenantSelect");
  let tenants = [];
  try {
    tenants = await api("GET", "/v1/tenants");
  } catch (err) {
    // Fall through with an empty list — the "create new" option still works standalone.
  }
  select.innerHTML =
    tenants.map((t) => `<option value="${t}">${t}</option>`).join("") +
    `<option value="${NEW_TENANT_VALUE}">+ Create a new tenant…</option>`;

  const newTenantLabel = document.getElementById("newTenantLabel");
  if (tenants.length > 0) {
    select.value = tenants[0];
    newTenantLabel.classList.add("hidden");
  } else {
    // Nothing to join yet — go straight to "create new" so first-time setup isn't a dead end.
    select.value = NEW_TENANT_VALUE;
    newTenantLabel.classList.remove("hidden");
  }
}

document.getElementById("tenantSelect").addEventListener("change", (event) => {
  document.getElementById("newTenantLabel").classList.toggle("hidden", event.target.value !== NEW_TENANT_VALUE);
});

document.getElementById("registerForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const resultBox = document.getElementById("registerResult");
  resultBox.className = "form-result";
  resultBox.textContent = "";
  await attemptRegister(event.target, resultBox);
});

async function attemptRegister(form, resultBox) {
  const tenantSelect = document.getElementById("tenantSelect");
  const isNewTenant = tenantSelect.value === NEW_TENANT_VALUE;
  const tenantId = isNewTenant ? document.getElementById("newTenantInput").value.trim() : tenantSelect.value;

  if (isNewTenant && !tenantId) {
    resultBox.className = "form-result error";
    resultBox.textContent = "Enter a tenant ID for the new company, or pick an existing one above.";
    return;
  }

  try {
    const session = await api("POST", "/v1/auth/register", {
      tenantId,
      role: form.role.value,
      name: form.name.value,
      email: form.email.value,
      password: form.password.value,
      // Picking "+ Create a new tenant…" from a list that already shows every existing
      // tenant *is* the deliberate confirmation — no need to ask a second time via popup.
      confirmNewTenant: isNewTenant,
    });
    setSession(session);
    form.reset();
    document.getElementById("newTenantLabel").classList.add("hidden");
    showAppView(session);
  } catch (err) {
    // Still handled as a fallback (e.g. someone else registered the same brand-new tenant
    // between this page loading the list and this submit), even though the UI above makes
    // it rare in practice.
    if (err.data && err.data.error === "NEW_TENANT_CONFIRMATION_REQUIRED") {
      const proceed = confirm(err.data.message);
      if (proceed) {
        await attemptRegisterConfirmed(form, resultBox, tenantId);
        return;
      }
      resultBox.className = "form-result error";
      resultBox.textContent = "Registration cancelled — double-check the tenant you selected.";
      return;
    }
    resultBox.className = "form-result error";
    resultBox.textContent = err.message;
  }
}

/** Rare fallback retry path — see the NEW_TENANT_CONFIRMATION_REQUIRED branch above. */
async function attemptRegisterConfirmed(form, resultBox, tenantId) {
  try {
    const session = await api("POST", "/v1/auth/register", {
      tenantId,
      role: form.role.value,
      name: form.name.value,
      email: form.email.value,
      password: form.password.value,
      confirmNewTenant: true,
    });
    setSession(session);
    form.reset();
    document.getElementById("newTenantLabel").classList.add("hidden");
    showAppView(session);
  } catch (err) {
    resultBox.className = "form-result error";
    resultBox.textContent = err.message;
  }
}

document.getElementById("logoutBtn").addEventListener("click", async () => {
  try {
    await api("POST", "/v1/auth/logout");
  } catch (err) {
    // Ignore — we're clearing the local session either way.
  }
  clearSession();
  showAuthView();
});

// ---------- Create form: dynamic cargo lines / container requirements ----------

function addCargoLineRow() {
  const row = document.createElement("div");
  row.className = "dynamic-row";
  row.innerHTML = `
    <input placeholder="Description*" class="cli-description" required>
    <input placeholder="Qty*" type="number" step="any" min="0.000001" class="cli-quantity" required>
    <input placeholder="UoM*" class="cli-unitOfMeasure" required>
    <input placeholder="HS code" class="cli-hsCode">
    <input placeholder="Country of origin" class="cli-countryOfOrigin">
    <input placeholder="Weight kg" type="number" step="any" class="cli-lineWeightKg">
    <input placeholder="Value" type="number" step="any" class="cli-lineValueAmount">
    <input placeholder="Currency" class="cli-lineValueCurrency">
    <button type="button" class="remove-row" title="Remove">✕</button>
  `;
  row.querySelector(".remove-row").addEventListener("click", () => row.remove());
  document.getElementById("cargoLines").appendChild(row);
}

// DESIGN.md §4.1's container-type reference table — kept in sync with that, not invented here.
const CONTAINER_TYPES = [
  { code: "20GP", label: "20' Standard (20GP)" },
  { code: "40GP", label: "40' Standard (40GP)" },
  { code: "40HC", label: "40' High Cube (40HC)" },
  { code: "45HC", label: "45' High Cube (45HC)" },
  { code: "20RF", label: "20' Reefer (20RF)" },
  { code: "40RH", label: "40' Reefer (40RH)" },
  { code: "20OT", label: "20' Open Top (20OT)" },
  { code: "40OT", label: "40' Open Top (40OT)" },
  { code: "20FR", label: "20' Flat Rack (20FR)" },
  { code: "40FR", label: "40' Flat Rack (40FR)" },
];

function containerTypeOptions() {
  return CONTAINER_TYPES.map((t) => `<option value="${t.code}">${t.label}</option>`).join("");
}

function addContainerReqRow() {
  const row = document.createElement("div");
  row.className = "dynamic-row";
  row.innerHTML = `
    <select class="cr-containerType">${containerTypeOptions()}</select>
    <input placeholder="Quantity" type="number" min="1" class="cr-quantity">
    <button type="button" class="remove-row" title="Remove">✕</button>
  `;
  row.querySelector(".remove-row").addEventListener("click", () => row.remove());
  document.getElementById("containerReqs").appendChild(row);
}

document.getElementById("addCargoLine").addEventListener("click", addCargoLineRow);
document.getElementById("addContainerReq").addEventListener("click", addContainerReqRow);

function resetDynamicRows() {
  document.getElementById("cargoLines").innerHTML = "";
  document.getElementById("containerReqs").innerHTML = "";
  addCargoLineRow();
}

// ---------- Create booking ----------

function toIso(datetimeLocalValue) {
  if (!datetimeLocalValue) return null;
  return new Date(datetimeLocalValue).toISOString();
}

function numOrNull(value) {
  return value === "" || value === null || value === undefined ? null : Number(value);
}

document.getElementById("createForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.target;
  const resultBox = document.getElementById("createResult");
  resultBox.className = "";
  resultBox.textContent = "";

  const cargoLineItems = [...document.querySelectorAll("#cargoLines .dynamic-row")].map((row) => ({
    description: row.querySelector(".cli-description").value || null,
    quantity: numOrNull(row.querySelector(".cli-quantity").value),
    unitOfMeasure: row.querySelector(".cli-unitOfMeasure").value || null,
    hsCode: row.querySelector(".cli-hsCode").value || null,
    countryOfOrigin: row.querySelector(".cli-countryOfOrigin").value || null,
    lineWeightKg: numOrNull(row.querySelector(".cli-lineWeightKg").value),
    lineValueAmount: numOrNull(row.querySelector(".cli-lineValueAmount").value),
    lineValueCurrency: row.querySelector(".cli-lineValueCurrency").value || null,
  }));

  const containerRequirements = [...document.querySelectorAll("#containerReqs .dynamic-row")]
    .map((row) => ({
      containerType: row.querySelector(".cr-containerType").value || null,
      quantity: numOrNull(row.querySelector(".cr-quantity").value),
    }))
    .filter((c) => c.containerType);

  const body = {
    modePreference: form.modePreference.value,
    incoterm: form.incoterm.value,
    loadType: form.loadType.value,
    originNodeId: form.originNodeId.value,
    destinationNodeId: form.destinationNodeId.value,
    consigneeId: form.consigneeId.value,
    notifyPartyId: form.notifyPartyId.value || null,
    contractId: form.contractId.value || null,
    requiredPickupBy: toIso(form.requiredPickupBy.value),
    requiredDeliveryBy: toIso(form.requiredDeliveryBy.value),
    totalWeightKg: numOrNull(form.totalWeightKg.value),
    totalVolumeCbm: numOrNull(form.totalVolumeCbm.value),
    containerRequirements,
    cargoLineItems,
  };

  try {
    const booking = await api("POST", "/v1/bookings", body);
    resultBox.className = "success";
    resultBox.textContent = `Created ${booking.bookingId} (status ${booking.status})`;
    form.reset();
    resetDynamicRows();
  } catch (err) {
    if (err.status === 401) return; // already bounced to the login screen
    resultBox.className = "error";
    resultBox.textContent = err.message;
  }
});

// ---------- Bookings table (operator management) ----------

function rowIdAndVersion(target) {
  const row = target.closest("tr");
  return { id: row.dataset.id, version: Number(row.dataset.version) };
}

async function loadBookings() {
  const tbody = document.getElementById("bookingsBody");
  const status = document.getElementById("statusFilter").value;
  try {
    const bookings = await api("GET", "/v1/bookings" + (status ? `?status=${status}` : ""));
    if (bookings.length === 0) {
      tbody.innerHTML = `<tr class="muted-row"><td colspan="8">No bookings yet.</td></tr>`;
      return;
    }
    tbody.innerHTML = bookings.map(renderRow).join("");
    tbody.querySelectorAll(".view-btn").forEach((b) => b.addEventListener("click", onView));
    tbody.querySelectorAll(".submit-btn").forEach((b) => b.addEventListener("click", onSubmit));
    tbody.querySelectorAll(".amend-btn").forEach((b) => b.addEventListener("click", onAmend));
    tbody.querySelectorAll(".cancel-btn").forEach((b) => b.addEventListener("click", onCancel));
    tbody.querySelectorAll(".matches-btn").forEach((b) => b.addEventListener("click", onFindMatches));
  } catch (err) {
    if (err.status === 401) return;
    tbody.innerHTML = `<tr><td colspan="8" class="error">${err.message}</td></tr>`;
  }
}

function renderRow(b) {
  const canSubmit = b.status === "DRAFT";
  const canModify = b.status !== "CANCELLED" && b.status !== "CONFIRMED";
  const canMatch = b.status === "SUBMITTED";
  return `
    <tr data-id="${b.bookingId}" data-version="${b.version}">
      <td title="${b.bookingId}">${b.bookingId.slice(0, 8)}…</td>
      <td>${formatShipper(b, 8)}</td>
      <td><span class="status status-${b.status}">${b.status}</span></td>
      <td>${b.modePreference}</td>
      <td>${b.incoterm}</td>
      <td>${b.originNodeId} → ${b.destinationNodeId}</td>
      <td>${b.version}</td>
      <td class="actions">
        <button class="view-btn" type="button">View</button>
        ${canSubmit ? '<button class="submit-btn" type="button">Submit</button>' : ""}
        ${canMatch ? '<button class="matches-btn" type="button">Find Matches</button>' : ""}
        ${canModify ? '<button class="amend-btn" type="button">Amend</button><button class="cancel-btn" type="button">Cancel</button>' : ""}
      </td>
    </tr>
  `;
}

// ---------- Matching: find candidates for a booking, reserve one ----------

async function onFindMatches(event) {
  const { id } = rowIdAndVersion(event.target);
  const panel = document.getElementById("candidatesPanel");
  const body = document.getElementById("candidatesBody");
  document.getElementById("candidatesBookingId").textContent = id.slice(0, 8) + "…";
  panel.classList.remove("hidden");
  body.innerHTML = `<tr class="muted-row"><td colspan="5">Searching…</td></tr>`;
  try {
    const candidates = await api("GET", `/v1/bookings/${id}/candidates`);
    if (candidates.length === 0) {
      body.innerHTML = `<tr class="muted-row"><td colspan="5">No matching capacity found for this lane/mode.</td></tr>`;
      return;
    }
    body.innerHTML = candidates.map((c) => renderCandidateRow(c, id)).join("");
    body.querySelectorAll(".reserve-btn").forEach((btn) => btn.addEventListener("click", onReserve));
  } catch (err) {
    if (err.status === 401) return;
    body.innerHTML = `<tr><td colspan="5" class="error">${err.message}</td></tr>`;
  }
}

function renderCandidateRow(offering, bookingId) {
  return `
    <tr data-offering-id="${offering.offeringId}" data-booking-id="${bookingId}">
      <td title="${offering.offeringId}">${offering.offeringId.slice(0, 8)}…</td>
      <td>${offering.originNodeId} → ${offering.destinationNodeId}</td>
      <td>${formatCapacity(offering)}</td>
      <td>${offering.rateAmount} ${offering.rateCurrency}</td>
      <td><button class="reserve-btn" type="button">Reserve</button></td>
    </tr>
  `;
}

function formatCapacity(offering) {
  if (offering.containerCapacities && offering.containerCapacities.length > 0) {
    return offering.containerCapacities.map((c) => `${c.containerType}: ${c.availableQuantity}/${c.totalQuantity}`).join(", ");
  }
  const parts = [];
  if (offering.availableWeightKg != null) parts.push(`${offering.availableWeightKg}kg`);
  if (offering.availableVolumeCbm != null) parts.push(`${offering.availableVolumeCbm}cbm`);
  return parts.join(" / ") || "—";
}

async function onReserve(event) {
  const row = event.target.closest("tr");
  const offeringId = row.dataset.offeringId;
  const bookingId = row.dataset.bookingId;
  try {
    await api("POST", `/v1/bookings/${bookingId}/reserve`, { offeringId });
    document.getElementById("candidatesPanel").classList.add("hidden");
    loadBookings();
  } catch (err) {
    if (err.status !== 401) alert("Reserve failed: " + err.message);
  }
}

async function onView(event) {
  const button = event.target;
  const row = button.closest("tr");
  const existingDetailRow = row.nextElementSibling;

  // Clicking an already-expanded row's button collapses it.
  if (existingDetailRow && existingDetailRow.classList.contains("detail-row")) {
    existingDetailRow.remove();
    button.textContent = "View";
    return;
  }

  // Only one row expanded at a time — collapse whichever one currently is.
  document.querySelectorAll("#bookingsBody .detail-row").forEach((r) => r.remove());
  document.querySelectorAll("#bookingsBody .view-btn").forEach((b) => (b.textContent = "View"));

  const { id } = rowIdAndVersion(button);
  try {
    const booking = await api("GET", `/v1/bookings/${id}`);
    const detailRow = document.createElement("tr");
    detailRow.className = "detail-row";
    const columnCount = row.children.length;
    detailRow.innerHTML = `<td colspan="${columnCount}"><div class="detail-panel">${renderBookingDetail(booking)}</div></td>`;
    row.after(detailRow);
    button.textContent = "Hide";
  } catch (err) {
    if (err.status !== 401) alert("View failed: " + err.message);
  }
}

function formatDateTime(iso) {
  if (!iso) return "—";
  return new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

/**
 * Always shows the ID — name resolution can fail (e.g. a legacy shipperId that
 * predates real Party accounts), and the ID is the actual foreign key, worth
 * keeping visible even once a friendly name is available.
 */
function formatShipper(b, truncateIdAt) {
  const id = truncateIdAt ? b.shipperId.slice(0, truncateIdAt) + "…" : b.shipperId;
  return b.shipperName ? `${b.shipperName} <span class="id-inline">${id}</span>` : id;
}

function detailField(label, value) {
  return `
    <div class="detail-field">
      <span class="detail-label">${label}</span>
      <span class="detail-value">${value === null || value === undefined || value === "" ? "—" : value}</span>
    </div>
  `;
}

function renderBookingDetail(b) {
  const containerRows = (b.containerRequirements || [])
    .map((c) => `<tr><td>${c.containerType}</td><td>${c.quantity}</td></tr>`)
    .join("");

  const cargoRows = (b.cargoLineItems || [])
    .map((c) => `
      <tr>
        <td>${c.description}</td>
        <td>${c.quantity} ${c.unitOfMeasure}</td>
        <td>${c.hsCode || "—"}</td>
        <td>${c.countryOfOrigin || "—"}</td>
        <td>${c.lineWeightKg != null ? c.lineWeightKg + " kg" : "—"}</td>
        <td>${c.lineValueAmount != null ? c.lineValueAmount + " " + (c.lineValueCurrency || "") : "—"}</td>
      </tr>
    `)
    .join("");

  return `
    <div class="detail-header">
      <span class="booking-id">${b.bookingId}</span>
      <span class="status status-${b.status}">${b.status}</span>
    </div>

    <div class="detail-grid">
      ${detailField("Mode", b.modePreference)}
      ${detailField("Incoterm", b.incoterm)}
      ${detailField("Load type", b.loadType)}
      ${detailField("Route", `${b.originNodeId} → ${b.destinationNodeId}`)}
      ${detailField("Shipper", formatShipper(b))}
      ${detailField("Consignee", b.consigneeId)}
      ${detailField("Notify party", b.notifyPartyId)}
      ${detailField("Contract", b.contractId)}
      ${detailField("Required pickup by", formatDateTime(b.requiredPickupBy))}
      ${detailField("Required delivery by", formatDateTime(b.requiredDeliveryBy))}
      ${detailField("Total weight", b.totalWeightKg != null ? b.totalWeightKg + " kg" : null)}
      ${detailField("Total volume", b.totalVolumeCbm != null ? b.totalVolumeCbm + " cbm" : null)}
      ${detailField("Capacity offering", b.capacityOfferingId)}
      ${detailField("Version", b.version)}
      ${detailField("Created", formatDateTime(b.createdAt))}
      ${detailField("Updated", formatDateTime(b.updatedAt))}
    </div>

    <div class="detail-section">
      <h4>Container requirements</h4>
      ${containerRows
        ? `<table><thead><tr><th>Type</th><th>Qty</th></tr></thead><tbody>${containerRows}</tbody></table>`
        : `<p class="detail-empty">None — this is an LCL/Breakbulk booking.</p>`}
    </div>

    <div class="detail-section">
      <h4>Cargo line items</h4>
      <table>
        <thead><tr><th>Description</th><th>Qty</th><th>HS code</th><th>Origin</th><th>Weight</th><th>Value</th></tr></thead>
        <tbody>${cargoRows}</tbody>
      </table>
    </div>
  `;
}

async function onSubmit(event) {
  const { id } = rowIdAndVersion(event.target);
  try {
    await api("PUT", `/v1/bookings/${id}/submit`);
    loadBookings();
  } catch (err) {
    if (err.status !== 401) alert("Submit failed: " + err.message);
  }
}

async function onCancel(event) {
  const { id } = rowIdAndVersion(event.target);
  const reason = prompt("Cancellation reason?", "customer-request");
  if (reason === null) return;
  try {
    await api("DELETE", `/v1/bookings/${id}?reason=${encodeURIComponent(reason)}`);
    loadBookings();
  } catch (err) {
    if (err.status !== 401) alert("Cancel failed: " + err.message);
  }
}

async function onAmend(event) {
  const { id, version } = rowIdAndVersion(event.target);
  const notifyPartyId = prompt("New notify party ID (blank leaves it unchanged):", "");
  if (notifyPartyId === null) return;
  try {
    await api("PUT", `/v1/bookings/${id}/amend`, {
      expectedVersion: version,
      notifyPartyId: notifyPartyId || null,
    });
    loadBookings();
  } catch (err) {
    if (err.status !== 401) alert("Amend failed: " + err.message);
  }
}

document.getElementById("refreshBtn").addEventListener("click", loadBookings);
document.getElementById("statusFilter").addEventListener("change", loadBookings);

// ---------- Supply (Operator): create + list capacity offerings ----------

function addOfferingContainerRow() {
  const row = document.createElement("div");
  row.className = "dynamic-row";
  row.innerHTML = `
    <select class="oc-containerType">${containerTypeOptions()}</select>
    <input placeholder="Total quantity" type="number" min="1" class="oc-totalQuantity">
    <button type="button" class="remove-row" title="Remove">✕</button>
  `;
  row.querySelector(".remove-row").addEventListener("click", () => row.remove());
  document.getElementById("offeringContainers").appendChild(row);
}

document.getElementById("addOfferingContainer").addEventListener("click", addOfferingContainerRow);

document.getElementById("offeringForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.target;
  const resultBox = document.getElementById("offeringResult");
  resultBox.className = "";
  resultBox.textContent = "";

  const containerCapacities = [...document.querySelectorAll("#offeringContainers .dynamic-row")]
    .map((row) => ({
      containerType: row.querySelector(".oc-containerType").value || null,
      totalQuantity: numOrNull(row.querySelector(".oc-totalQuantity").value),
    }))
    .filter((c) => c.containerType);

  const body = {
    mode: form.mode.value,
    originNodeId: form.originNodeId.value,
    destinationNodeId: form.destinationNodeId.value,
    containerCapacities,
    totalWeightKg: numOrNull(form.totalWeightKg.value),
    totalVolumeCbm: numOrNull(form.totalVolumeCbm.value),
    rateAmount: numOrNull(form.rateAmount.value),
    rateCurrency: form.rateCurrency.value,
    validFrom: toIso(form.validFrom.value),
    validUntil: toIso(form.validUntil.value),
  };

  try {
    const offering = await api("POST", "/v1/capacity-offerings", body);
    resultBox.className = "success";
    resultBox.textContent = `Created offering ${offering.offeringId}`;
    form.reset();
    document.getElementById("offeringContainers").innerHTML = "";
    loadOfferings();
  } catch (err) {
    if (err.status === 401) return;
    resultBox.className = "error";
    resultBox.textContent = err.message;
  }
});

async function loadOfferings() {
  const tbody = document.getElementById("offeringsBody");
  try {
    const offerings = await api("GET", "/v1/capacity-offerings");
    if (offerings.length === 0) {
      tbody.innerHTML = `<tr class="muted-row"><td colspan="6">No offerings yet — create one above.</td></tr>`;
      return;
    }
    tbody.innerHTML = offerings.map(renderOfferingRow).join("");
  } catch (err) {
    if (err.status === 401) return;
    tbody.innerHTML = `<tr><td colspan="6" class="error">${err.message}</td></tr>`;
  }
}

function renderOfferingRow(o) {
  return `
    <tr>
      <td title="${o.offeringId}">${o.offeringId.slice(0, 8)}…</td>
      <td>${o.mode}</td>
      <td>${o.originNodeId} → ${o.destinationNodeId}</td>
      <td>${formatCapacity(o)}</td>
      <td>${o.rateAmount} ${o.rateCurrency}</td>
      <td><span class="status status-${o.status === "ACTIVE" ? "CONFIRMED" : "CANCELLED"}">${o.status}</span></td>
    </tr>
  `;
}

// ---------- Init ----------

resetDynamicRows();
addContainerReqRow();
loadTenants();

const existingSession = getSession();
if (existingSession) {
  showAppView(existingSession);
} else {
  showAuthView();
}
