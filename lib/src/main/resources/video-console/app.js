const CHUNK_SIZE_BYTES = 2 * 1024 * 1024; // 2 MB — small enough to show multiple parts even for modest files
const MAX_CHUNK_ATTEMPTS = 3;

// Hand-rolled CRC32 (standard IEEE 802.3 polynomial) — no library, matches
// this repo's preference for implementing the algorithm rather than
// importing it. Used to verify each chunk survived transit intact.
const CRC32_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    }
    table[n] = c;
  }
  return table;
})();

function crc32(bytes) {
  let crc = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) {
    crc = CRC32_TABLE[(crc ^ bytes[i]) & 0xff] ^ (crc >>> 8);
  }
  return ((crc ^ 0xffffffff) >>> 0).toString(16);
}

function fileFingerprint(file) {
  return `videoUpload:${file.name}:${file.size}:${file.lastModified}`;
}

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

let activeUpload = null; // { uploadId, cancelled }

function setUploadUiBusy(busy) {
  document.getElementById("uploadBtn").disabled = busy;
  document.getElementById("cancelUploadBtn").classList.toggle("hidden", !busy);
  document.getElementById("uploadProgressWrap").classList.toggle("hidden", !busy);
}

function updateUploadProgress(done, total) {
  const pct = total === 0 ? 0 : Math.round((done / total) * 100);
  document.getElementById("uploadProgressBar").value = pct;
  document.getElementById("uploadProgressText").textContent = `${done}/${total} parts (${pct}%)`;
}

async function uploadRealFileChunked(file, title, complexity, durationSeconds, simulateTotalFailure, simulateResolutionFailure, simulateCorruption) {
  const fingerprint = fileFingerprint(file);
  const savedUploadId = localStorage.getItem(fingerprint);
  let uploadId, totalChunks;

  if (savedUploadId) {
    try {
      const status = await api(`/api/uploads/${savedUploadId}/status`);
      uploadId = savedUploadId;
      totalChunks = status.totalChunks;
      logLine(`Resuming upload ${uploadId}: server already has ${status.receivedPartNumbers.length}/${totalChunks} part(s) from before — only sending what's missing`);
    } catch (e) {
      logLine(`Saved upload session ${savedUploadId} is gone (${e.message}) — starting a fresh upload`);
      localStorage.removeItem(fingerprint);
    }
  }

  if (!uploadId) {
    const init = await api("/api/uploads/init", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title, contentType: file.type || "application/octet-stream", totalSizeBytes: file.size, chunkSizeBytes: CHUNK_SIZE_BYTES }),
    });
    uploadId = init.uploadId;
    totalChunks = init.totalChunks;
    localStorage.setItem(fingerprint, uploadId);
    logLine(`Started chunked upload ${uploadId}: ${file.name} split into ${totalChunks} part(s) of up to ${CHUNK_SIZE_BYTES.toLocaleString()} bytes each`);
  }

  const status = await api(`/api/uploads/${uploadId}/status`);
  const alreadyHave = new Set(status.receivedPartNumbers);
  let doneCount = alreadyHave.size;

  activeUpload = { uploadId, cancelled: false };
  setUploadUiBusy(true);
  updateUploadProgress(doneCount, totalChunks);

  for (let part = 0; part < totalChunks; part++) {
    if (activeUpload.cancelled) {
      logLine(`Upload ${uploadId} cancelled — session stays on the server for ${activeUpload ? "later resumption" : "cleanup"} (or will be swept if abandoned)`);
      return null;
    }
    if (alreadyHave.has(part)) {
      continue; // already on the server from a previous attempt — this is resumability in action
    }

    const start = part * CHUNK_SIZE_BYTES;
    const end = Math.min(start + CHUNK_SIZE_BYTES, file.size);
    const bytes = new Uint8Array(await file.slice(start, end).arrayBuffer());
    const realChecksum = crc32(bytes);

    for (let attempt = 1; ; attempt++) {
      if (activeUpload.cancelled) {
        return null;
      }
      // Deliberately send a wrong checksum on the first attempt of ~1-in-5
      // parts, when the test checkbox is on, to exercise the server's real
      // rejection path and this loop's real retry path — not a fake log line.
      const sendCorrupt = simulateCorruption && attempt === 1 && Math.random() < 0.2;
      const claimedChecksum = sendCorrupt ? realChecksum.split("").reverse().join("") : realChecksum;
      try {
        await api(`/api/uploads/${uploadId}/parts/${part}`, {
          method: "PUT",
          headers: { "Content-Type": "application/octet-stream", "X-Chunk-Checksum": claimedChecksum },
          body: bytes,
        });
        break;
      } catch (e) {
        if (attempt >= MAX_CHUNK_ATTEMPTS) {
          setUploadUiBusy(false);
          activeUpload = null;
          throw new Error(`part ${part} failed after ${MAX_CHUNK_ATTEMPTS} attempts (${e.message}) — upload session ${uploadId} is kept server-side, reselect the same file to resume`);
        }
        logLine(`Part ${part} attempt ${attempt} failed (${e.message}) — retrying in ${300 * attempt}ms`);
        await new Promise((r) => setTimeout(r, 300 * attempt));
      }
    }
    doneCount++;
    updateUploadProgress(doneCount, totalChunks);
  }

  const result = await api(`/api/uploads/${uploadId}/complete`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ complexity, durationSeconds, simulateTotalFailure, simulateResolutionFailure }),
  });
  localStorage.removeItem(fingerprint);
  activeUpload = null;
  setUploadUiBusy(false);
  return result.videoId;
}

async function upload() {
  const fileInput = document.getElementById("uploadFile");
  const file = fileInput.files[0];
  const title = document.getElementById("uploadTitle").value.trim();
  const complexity = parseFloat(complexityInput.value);
  const durationSeconds = parseInt(document.getElementById("uploadDuration").value, 10) || 120;
  const simulateTotalFailure = document.getElementById("simTotalFailure").checked;
  const simulateResolutionFailure = document.getElementById("simResolution").value;
  const simulateCorruption = document.getElementById("simCorruption").checked;

  if (!file && !title) {
    logLine("Provide a title, a file, or both before uploading");
    return;
  }

  try {
    let videoId;
    if (file) {
      videoId = await uploadRealFileChunked(file, title || file.name, complexity, durationSeconds, simulateTotalFailure, simulateResolutionFailure, simulateCorruption);
      if (videoId === null) {
        return; // cancelled mid-upload
      }
    } else {
      const result = await api("/api/upload", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title, complexity, durationSeconds, simulateTotalFailure, simulateResolutionFailure }),
      });
      videoId = result.videoId;
    }
    document.getElementById("uploadResult").textContent = `Uploaded — videoId ${videoId} (202 Accepted, status=PROCESSING)`;
    logLine(`Uploaded "${title || (file && file.name)}" (${videoId})${file ? " — real file, playable once READY" : ""}`);
    fileInput.value = "";
    refreshVideos();
    refreshStorage();
  } catch (e) {
    setUploadUiBusy(false);
    logLine("Upload failed: " + e.message);
  }
}

async function cancelUpload() {
  if (!activeUpload) return;
  activeUpload.cancelled = true;
  try {
    await api(`/api/uploads/${activeUpload.uploadId}`, { method: "DELETE" });
  } catch (e) {
    // already gone — fine
  }
  setUploadUiBusy(false);
}

document.getElementById("uploadBtn").addEventListener("click", upload);
document.getElementById("cancelUploadBtn").addEventListener("click", cancelUpload);

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
