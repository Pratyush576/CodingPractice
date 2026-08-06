const DEMO_NETWORK_TRACE = [
  { throughputKbps: 6000, bufferHealthMs: 0 },
  { throughputKbps: 6000, bufferHealthMs: 3000 },
  { throughputKbps: 6000, bufferHealthMs: 9000 },
  { throughputKbps: 800, bufferHealthMs: 6000 },
  { throughputKbps: 800, bufferHealthMs: 2000 },
  { throughputKbps: 6000, bufferHealthMs: 9000 },
];

let lastEventId = 0;
// videoId -> { kind: "manifest" | "playback", content: string | array }
const expanded = {};

const videosBodyEl = document.getElementById("videosBody");
const activityLogEl = document.getElementById("activityLog");
const complexityInput = document.getElementById("uploadComplexity");
const complexityValueEl = document.getElementById("complexityValue");

complexityInput.addEventListener("input", () => {
  complexityValueEl.textContent = complexityInput.value;
});

function shortId(id) {
  return id.length > 8 ? id.slice(0, 8) + "…" : id;
}

function logLine(text) {
  const line = document.createElement("div");
  line.className = "log-line";
  const time = new Date().toLocaleTimeString();
  line.textContent = `[${time}] ${text}`;
  activityLogEl.appendChild(line);
  while (activityLogEl.children.length > 150) {
    activityLogEl.removeChild(activityLogEl.firstChild);
  }
}

async function api(path, options) {
  const res = await fetch(path, options);
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`${path} -> HTTP ${res.status} ${body}`);
  }
  return res.status === 204 ? null : res.json();
}

async function refreshQueueStats() {
  try {
    const stats = await api("/api/queue-stats");
    document.getElementById("statAvailable").textContent = stats.available;
    document.getElementById("statInFlight").textContent = stats.inFlight;
    document.getElementById("statDlq").textContent = stats.dlqAvailable;
  } catch (e) {
    // transient — next poll will retry
  }
}

async function pollWorkerEvents() {
  try {
    const events = await api(`/api/worker-events?since=${lastEventId}`);
    for (const ev of events) {
      lastEventId = Math.max(lastEventId, ev.id);
      logLine(ev.text);
    }
  } catch (e) {
    // transient — next poll will retry
  }
}

async function refreshVideos() {
  let videos;
  try {
    videos = await api("/api/videos");
  } catch (e) {
    logLine("Failed to load videos: " + e.message);
    return;
  }

  if (videos.length === 0) {
    videosBodyEl.innerHTML = `<tr><td colspan="5" class="empty-row">Upload a video to get started</td></tr>`;
    return;
  }

  videosBodyEl.innerHTML = "";
  for (const v of videos) {
    const row = document.createElement("tr");
    const renditionsLabel = v.status === "READY"
      ? `${v.readyRenditions.length}${v.failedResolutions.length ? " (missing " + v.failedResolutions.join(", ") + ")" : ""}`
      : "–";
    row.innerHTML = `
      <td>${escapeHtml(v.title)}</td>
      <td class="mono"><span class="id-chip" title="${v.id}">${shortId(v.id)}</span></td>
      <td><span class="status-badge status-${v.status}">${v.status}</span>${v.status === "FAILED" ? `<div class="result-line">${escapeHtml(v.failureReason || "")}</div>` : ""}</td>
      <td>${renditionsLabel}</td>
      <td>
        <button class="small play-btn" ${v.status !== "READY" ? "disabled" : ""}>Play</button>
        <button class="small manifest-btn" ${v.status !== "READY" ? "disabled" : ""}>Manifest</button>
        <button class="small playback-btn" ${v.status !== "READY" ? "disabled" : ""}>Simulate Playback</button>
        <button class="small json-btn">Metadata JSON</button>
      </td>
    `;
    row.querySelector(".play-btn").addEventListener("click", () => togglePlay(v));
    row.querySelector(".manifest-btn").addEventListener("click", () => toggleManifest(v));
    row.querySelector(".playback-btn").addEventListener("click", () => togglePlayback(v));
    row.querySelector(".json-btn").addEventListener("click", () => toggleJson(v));
    videosBodyEl.appendChild(row);

    if (expanded[v.id]) {
      videosBodyEl.appendChild(renderDetailRow(v, expanded[v.id]));
    }
  }
}

function renderDetailRow(video, detail) {
  const row = document.createElement("tr");
  row.className = "detail-row";
  const cell = document.createElement("td");
  cell.colSpan = 5;
  if (detail.kind === "play") {
    cell.innerHTML = `<strong>Playing the actual uploaded file</strong>` +
      `<video controls style="max-width:100%;display:block;margin-top:0.5rem" src="/api/videos/${video.id}/play"></video>` +
      `<p class="result-line">This is the raw uploaded bytes served with Range support — not one of the "renditions" above, which are placeholder payloads only.</p>`;
  } else if (detail.kind === "json") {
    cell.innerHTML = `<strong>Raw VideoRecord as stored in VideoMetadataService</strong><pre>${escapeHtml(JSON.stringify(video, null, 2))}</pre>`;
  } else if (detail.kind === "manifest") {
    cell.innerHTML = `<strong>master.m3u8</strong><pre>${escapeHtml(detail.content)}</pre>`;
  } else {
    const rows = detail.content.map((d) =>
      `<tr><td>${d.throughputKbps} kbps</td><td>${d.bufferHealthMs} ms</td><td>${d.resolution} (${d.bitrateKbps} kbps)</td></tr>`
    ).join("");
    cell.innerHTML = `
      <strong>ABR decisions for a fixed demo network trace</strong>
      <table>
        <thead><tr><th>Throughput</th><th>Buffer health</th><th>Chosen rendition</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    `;
  }
  row.appendChild(cell);
  return row;
}

function toggleJson(video) {
  if (expanded[video.id]?.kind === "json") {
    delete expanded[video.id];
  } else {
    expanded[video.id] = { kind: "json" };
  }
  refreshVideos();
}

function togglePlay(video) {
  if (expanded[video.id]?.kind === "play") {
    delete expanded[video.id];
  } else {
    expanded[video.id] = { kind: "play" };
  }
  refreshVideos();
}

async function toggleManifest(video) {
  if (expanded[video.id]?.kind === "manifest") {
    delete expanded[video.id];
    refreshVideos();
    return;
  }
  try {
    const result = await api(`/api/videos/${video.id}/manifest`);
    expanded[video.id] = { kind: "manifest", content: result.manifest };
    refreshVideos();
  } catch (e) {
    logLine("Failed to load manifest: " + e.message);
  }
}

async function togglePlayback(video) {
  if (expanded[video.id]?.kind === "playback") {
    delete expanded[video.id];
    refreshVideos();
    return;
  }
  try {
    const decisions = await api(`/api/videos/${video.id}/simulate-playback`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(DEMO_NETWORK_TRACE),
    });
    expanded[video.id] = { kind: "playback", content: decisions };
    refreshVideos();
    logLine(`Simulated ABR playback for "${video.title}": ` + decisions.map((d) => d.resolution).join(" -> "));
  } catch (e) {
    logLine("Failed to simulate playback: " + e.message);
  }
}

async function refreshStorage() {
  try {
    const snapshot = await api("/api/storage");
    document.getElementById("rawCount").textContent = `(${snapshot.raw.length})`;
    document.getElementById("processedCount").textContent = `(${snapshot.processed.length})`;
    renderStorageTable("rawObjectsBody", "raw", snapshot.raw, "Nothing uploaded yet");
    renderStorageTable("processedObjectsBody", "processed", snapshot.processed, "Nothing encoded yet");
  } catch (e) {
    logLine("Failed to load storage snapshot: " + e.message);
  }
}

function renderStorageTable(bodyId, storeName, objects, emptyMessage) {
  const body = document.getElementById(bodyId);
  if (objects.length === 0) {
    body.innerHTML = `<tr><td colspan="3" class="empty-row">${emptyMessage}</td></tr>`;
    return;
  }
  body.innerHTML = "";
  for (const obj of objects) {
    const row = document.createElement("tr");
    row.innerHTML = `
      <td class="mono">${escapeHtml(obj.key)}</td>
      <td class="mono">${obj.sizeBytes.toLocaleString()} B</td>
      <td><button class="small preview-btn">Preview bytes</button></td>
    `;
    row.querySelector(".preview-btn").addEventListener("click", () => previewObject(storeName, obj.key));
    body.appendChild(row);
  }
}

async function previewObject(storeName, key) {
  const previewEl = document.getElementById("storagePreview");
  try {
    const preview = await api(`/api/storage/preview?store=${storeName}&key=${encodeURIComponent(key)}`);
    previewEl.textContent = `${storeName}:${preview.key}  (${preview.sizeBytes} bytes total)\n\n`
      + preview.previewHex + (preview.truncated ? " ..." : "");
    previewEl.classList.remove("hidden-when-empty");
  } catch (e) {
    logLine("Failed to preview object: " + e.message);
  }
}

document.getElementById("refreshStorageBtn").addEventListener("click", refreshStorage);

function escapeHtml(s) {
  const div = document.createElement("div");
  div.textContent = s;
  return div.innerHTML;
}

async function upload() {
  const fileInput = document.getElementById("uploadFile");
  const file = fileInput.files[0];
  const title = document.getElementById("uploadTitle").value.trim();
  const complexity = parseFloat(complexityInput.value);
  const durationSeconds = parseInt(document.getElementById("uploadDuration").value, 10) || 120;
  const simulateTotalFailure = document.getElementById("simTotalFailure").checked;
  const simulateResolutionFailure = document.getElementById("simResolution").value;

  if (!file && !title) {
    logLine("Provide a title, a file, or both before uploading");
    return;
  }

  try {
    let result;
    if (file) {
      const formData = new FormData();
      formData.append("file", file);
      formData.append("title", title);
      formData.append("complexity", String(complexity));
      formData.append("durationSeconds", String(durationSeconds));
      formData.append("simulateTotalFailure", String(simulateTotalFailure));
      formData.append("simulateResolutionFailure", simulateResolutionFailure);
      result = await api("/api/upload-file", { method: "POST", body: formData });
    } else {
      result = await api("/api/upload", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title, complexity, durationSeconds, simulateTotalFailure, simulateResolutionFailure }),
      });
    }
    document.getElementById("uploadResult").textContent = `Uploaded — videoId ${result.videoId} (202 Accepted, status=PROCESSING)`;
    logLine(`Uploaded "${title || (file && file.name)}" (${result.videoId})${file ? " — real file, playable once READY" : ""}`);
    fileInput.value = "";
    refreshVideos();
    refreshStorage();
  } catch (e) {
    logLine("Upload failed: " + e.message);
  }
}

document.getElementById("uploadBtn").addEventListener("click", upload);

function anyVideoPlaying() {
  return Object.values(expanded).some((d) => d.kind === "play");
}

refreshVideos();
refreshQueueStats();
refreshStorage();
// Skip the automatic table rebuild while a <video> is expanded — rebuilding
// the row would tear down and recreate the element, restarting playback
// every 1.5s. Explicit user actions (upload, toggling a different panel)
// still refresh immediately via their own direct calls below.
setInterval(() => {
  if (!anyVideoPlaying()) {
    refreshVideos();
  }
}, 1500);
setInterval(refreshQueueStats, 1500);
setInterval(pollWorkerEvents, 1000);
logLine("Console loaded — polling every 1-1.5s");
