let selectedQueue = null;
let lastMessages = [];
let lastEventId = 0;
let queuesByName = {};

const queueTabsEl = document.getElementById("queueTabs");
const statAvailableEl = document.getElementById("statAvailable");
const statInFlightEl = document.getElementById("statInFlight");
const statQueueNameEl = document.getElementById("statQueueName");
const statQueueHintEl = document.getElementById("statQueueHint");
const messagesBodyEl = document.getElementById("messagesBody");
const activityLogEl = document.getElementById("activityLog");
const consumerStatusEl = document.getElementById("consumerStatus");
const consumerToggleBtn = document.getElementById("consumerToggleBtn");

function shortId(id) {
  return id.length > 8 ? id.slice(0, 8) + "…" : id;
}

function logLine(text, kind) {
  const line = document.createElement("div");
  line.className = "log-line" + (kind ? " " + kind : "");
  const time = new Date().toLocaleTimeString();
  line.textContent = `[${time}] ${text}`;
  activityLogEl.appendChild(line);
  while (activityLogEl.children.length > 100) {
    activityLogEl.removeChild(activityLogEl.firstChild);
  }
}

async function api(path, options) {
  const res = await fetch(path, options);
  if (!res.ok) {
    throw new Error(`${path} -> HTTP ${res.status}`);
  }
  return res.json();
}

async function refreshQueues() {
  let queues;
  try {
    queues = await api("/api/queues");
  } catch (e) {
    logLine("Failed to load queue list: " + e.message, "fail");
    return;
  }

  if (selectedQueue === null && queues.length > 0) {
    selectedQueue = queues[0].name;
  }

  queuesByName = Object.fromEntries(queues.map((q) => [q.name, q]));

  queueTabsEl.innerHTML = "";
  for (const q of queues) {
    const tab = document.createElement("div");
    tab.className = "queue-tab" + (q.name === selectedQueue ? " active" : "");
    tab.innerHTML = `<span class="queue-tab-name">${q.name}</span>` +
      `<span class="queue-tab-counts">avail ${q.available} · flight ${q.inFlight}</span>`;
    tab.addEventListener("click", () => {
      if (selectedQueue !== q.name) {
        selectedQueue = q.name;
        lastMessages = [];
        renderMessages();
        renderQueues(queues);
      }
    });
    queueTabsEl.appendChild(tab);
  }

  renderQueues(queues);
}

function renderQueues(queues) {
  const current = queues.find((q) => q.name === selectedQueue);
  if (!current) return;
  statAvailableEl.textContent = current.available;
  statInFlightEl.textContent = current.inFlight;
  statQueueNameEl.textContent = current.name;
  statQueueHintEl.textContent = current.name.endsWith("-dlq")
    ? "dead-letter queue"
    : "main queue";

  [...queueTabsEl.children].forEach((tab, i) => {
    tab.classList.toggle("active", queues[i].name === selectedQueue);
  });

  renderConsumerPanel(current);
}

function renderConsumerPanel(current) {
  if (!current.hasConsumer) {
    consumerStatusEl.textContent = "No consumer configured for this queue.";
    consumerToggleBtn.disabled = true;
    consumerToggleBtn.textContent = "Start consumer";
    return;
  }
  consumerToggleBtn.disabled = false;
  if (current.consumerRunning) {
    consumerStatusEl.textContent = "Running — polling every 400ms";
    consumerToggleBtn.textContent = "Stop consumer";
  } else {
    consumerStatusEl.textContent = "Stopped";
    consumerToggleBtn.textContent = "Start consumer";
  }
}

function renderMessages() {
  if (lastMessages.length === 0) {
    messagesBodyEl.innerHTML = `<tr><td colspan="5" class="empty-row">Poll to fetch messages</td></tr>`;
    return;
  }
  messagesBodyEl.innerHTML = "";
  for (const m of lastMessages) {
    const row = document.createElement("tr");
    row.innerHTML = `
      <td class="mono"><span class="id-chip" title="${m.messageId}">${shortId(m.messageId)}</span></td>
      <td>${escapeHtml(m.body)}</td>
      <td><span class="receive-badge">${m.receiveCount}</span></td>
      <td class="mono"><span class="id-chip" title="${m.receiptHandle}">${shortId(m.receiptHandle)}</span></td>
      <td>
        <button class="small delete-btn">Delete</button>
        <button class="small extend-btn">Extend +30s</button>
      </td>
    `;
    row.querySelector(".delete-btn").addEventListener("click", () => deleteMessage(m.receiptHandle));
    row.querySelector(".extend-btn").addEventListener("click", () => extendMessage(m.receiptHandle));
    messagesBodyEl.appendChild(row);
  }
}

function escapeHtml(s) {
  const div = document.createElement("div");
  div.textContent = s;
  return div.innerHTML;
}

async function sendMessage() {
  const body = document.getElementById("sendBody").value.trim();
  if (!body) return;
  try {
    const result = await api(`/api/queues/${selectedQueue}/send`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ body }),
    });
    document.getElementById("sendResult").textContent = `Sent — messageId ${result.messageId}`;
    document.getElementById("sendBody").value = "";
    logLine(`Sent to ${selectedQueue}: "${body}"`, "ok");
    refreshQueues();
  } catch (e) {
    logLine("Send failed: " + e.message, "fail");
  }
}

async function pollMessages() {
  const maxMessages = parseInt(document.getElementById("maxMessages").value, 10) || 5;
  const visibilityTimeoutSeconds = parseInt(document.getElementById("visibilityTimeout").value, 10) || 30;
  try {
    const messages = await api(`/api/queues/${selectedQueue}/receive`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ maxMessages, visibilityTimeoutSeconds }),
    });
    lastMessages = messages;
    renderMessages();
    document.getElementById("pollResult").textContent =
      `Received ${messages.length} message(s), invisible for ${visibilityTimeoutSeconds}s`;
    logLine(`Polled ${selectedQueue}: got ${messages.length} message(s)`, "ok");
    refreshQueues();
  } catch (e) {
    logLine("Poll failed: " + e.message, "fail");
  }
}

async function deleteMessage(receiptHandle) {
  try {
    const result = await api(`/api/queues/${selectedQueue}/delete`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ receiptHandle }),
    });
    logLine(
      `Delete ${shortId(receiptHandle)}: ${result.deleted}`,
      result.deleted ? "ok" : "fail"
    );
    if (result.deleted) {
      lastMessages = lastMessages.filter((m) => m.receiptHandle !== receiptHandle);
      renderMessages();
    }
    refreshQueues();
  } catch (e) {
    logLine("Delete failed: " + e.message, "fail");
  }
}

async function extendMessage(receiptHandle) {
  try {
    const result = await api(`/api/queues/${selectedQueue}/change-visibility`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ receiptHandle, timeoutSeconds: 30 }),
    });
    logLine(
      `Extend ${shortId(receiptHandle)} +30s: ${result.changed}`,
      result.changed ? "ok" : "fail"
    );
  } catch (e) {
    logLine("Extend failed: " + e.message, "fail");
  }
}

async function toggleConsumer() {
  const current = queuesByName[selectedQueue];
  if (!current || !current.hasConsumer) return;
  const action = current.consumerRunning ? "stop" : "start";
  try {
    const result = await api(`/api/queues/${selectedQueue}/consumer/${action}`, { method: "POST" });
    logLine(`Consumer ${action} -> running=${result.running}`, "ok");
    refreshQueues();
  } catch (e) {
    logLine(`Consumer ${action} failed: ` + e.message, "fail");
  }
}

async function pollConsumerEvents() {
  try {
    const events = await api(`/api/consumer-events?since=${lastEventId}`);
    for (const ev of events) {
      lastEventId = Math.max(lastEventId, ev.id);
      logLine(ev.text, "consumer");
    }
  } catch (e) {
    // consumer-events polling failure isn't worth spamming the log over
  }
}

document.getElementById("sendBtn").addEventListener("click", sendMessage);
document.getElementById("pollBtn").addEventListener("click", pollMessages);
consumerToggleBtn.addEventListener("click", toggleConsumer);

refreshQueues();
setInterval(refreshQueues, 1500);
setInterval(pollConsumerEvents, 1000);
logLine("Console loaded — polling queue stats every 1.5s");
