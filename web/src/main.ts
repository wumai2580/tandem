import "./style.css";

const $ = <T extends HTMLElement>(sel: string) => document.querySelector(sel) as T;

type Item = {
  id: string;
  kind: "file" | "text" | "link";
  name?: string;
  text?: string;
  size?: number;
  ts: number;
};

// ---------- auth ----------
let token = localStorage.getItem("tandem.token") || "";
const q = new URLSearchParams(location.search);
if (q.get("token")) {
  token = q.get("token")!;
  localStorage.setItem("tandem.token", token);
}
history.replaceState(null, "", location.pathname + (q.get("shared") ? "?shared=1" : ""));

const api = (path: string, init: RequestInit = {}) =>
  fetch(path, {
    ...init,
    headers: { ...(init.headers || {}), Authorization: `Bearer ${token}` },
  });

const authedUrl = (path: string) => `${path}?token=${encodeURIComponent(token)}`;

// ---------- ui helpers ----------
function toast(msg: string) {
  const t = $("#toast");
  t.textContent = msg;
  t.classList.remove("hidden");
  setTimeout(() => t.classList.add("hidden"), 2600);
}

function setStatus(on: boolean) {
  $("#dot").className = `dot ${on ? "on" : "off"}`;
  $("#status-text").textContent = on ? "connected" : "offline";
}

function fmtSize(n = 0) {
  if (n < 1024) return `${n} B`;
  if (n < 1 << 20) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1 << 30) return `${(n / (1 << 20)).toFixed(1)} MB`;
  return `${(n / (1 << 30)).toFixed(2)} GB`;
}

function fmtTime(ts: number) {
  return new Date(ts * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const ta = document.createElement("textarea");
    ta.value = text;
    document.body.appendChild(ta);
    ta.select();
    document.execCommand("copy");
    ta.remove();
  }
  toast("Copied");
}

// ---------- inbox ----------
async function refreshInbox() {
  const res = await api("/api/items");
  if (!res.ok) return;
  const items: Item[] = await res.json();
  const ul = $("#inbox");
  ul.innerHTML = "";
  $("#inbox-empty").classList.toggle("hidden", items.length > 0);
  for (const it of items) {
    const li = document.createElement("li");
    const kind = it.kind === "file" ? "📄" : it.kind === "link" ? "🔗" : "📝";
    const label = it.kind === "file" ? it.name! : it.kind === "link" ? it.text ?? "link" : it.text!;
    const sub =
      it.kind === "file" ? `${fmtSize(it.size)} · ${fmtTime(it.ts)}` : fmtTime(it.ts);
    li.innerHTML = `<span class="kind">${kind}</span><span class="meta">${escapeHtml(
      label
    )}<small>${sub}</small></span>`;
    const actions = document.createElement("span");
    if (it.kind === "file") {
      const a = document.createElement("a");
      a.textContent = "Save";
      a.href = authedUrl(`/api/files/${it.id}`);
      a.download = it.name!;
      actions.appendChild(a);
    } else {
      const b = document.createElement("button");
      b.className = "mini";
      b.textContent = "Copy";
      b.onclick = () => copyText(it.text ?? "");
      actions.appendChild(b);
    }
    const del = document.createElement("button");
    del.className = "mini";
    del.textContent = "✕";
    del.onclick = async () => {
      await api(`/api/items/${it.id}`, { method: "DELETE" });
      refreshInbox();
    };
    actions.appendChild(del);
    li.appendChild(actions);
    ul.appendChild(li);
  }
}

function escapeHtml(s: string) {
  return s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

// ---------- websocket ----------
function connectWs() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  const ws = new WebSocket(`${proto}://${location.host}/ws?token=${encodeURIComponent(token)}`);
  ws.onopen = () => setStatus(true);
  ws.onclose = () => {
    setStatus(false);
    setTimeout(connectWs, 2000);
  };
  ws.onmessage = (e) => {
    const ev = JSON.parse(e.data);
    if (ev.type === "clipboard") {
      $("#hub-clip").textContent = ev.text;
      $("#hub-clip").classList.remove("muted");
      toast("Clipboard updated");
    } else if (ev.type === "file" || ev.type === "text") {
      refreshInbox();
      toast(ev.type === "file" ? `Received ${ev.item.name}` : "Received text");
    } else if (ev.type === "link") {
      toast("Link opened on PC");
    }
  };
}

// ---------- send ----------
async function sendFiles(files: FileList | File[]) {
  for (const f of Array.from(files)) {
    const fd = new FormData();
    fd.append("file", f);
    fd.append("origin", "web");
    const res = await api("/api/files", { method: "POST", body: fd });
    if (res.ok) toast(`Sent ${f.name}`);
    else toast(`Failed: ${f.name}`);
  }
}

async function sendText(text: string) {
  const fd = new FormData();
  fd.append("text", text);
  fd.append("origin", "web");
  const res = await api("/api/text", { method: "POST", body: fd });
  if (res.ok) {
    const j = await res.json();
    toast(j.kind === "link" ? "Opened on PC" : "Sent");
  }
}

// ---------- share target handoff ----------
async function drainShare() {
  if (!q.get("shared")) return;
  if (!("serviceWorker" in navigator)) return;
  const reg = await navigator.serviceWorker.ready;
  const sw = reg.active;
  if (!sw) return;
  navigator.serviceWorker.addEventListener("message", async (e) => {
    if (e.data?.type !== "share" || !e.data.share) return;
    const { text, url, files, title } = e.data.share;
    for (const f of files as File[]) await sendFiles([f]);
    const combined = url || text || title;
    if (combined && !(files as File[]).length) await sendText(combined);
    refreshInbox();
  });
  sw.postMessage("get-share");
}

// ---------- init ----------
function initApp() {
  $("#app").classList.remove("hidden");
  connectWs();
  refreshInbox();

  api("/api/clipboard")
    .then((r) => (r.ok ? r.json() : null))
    .then((j) => {
      if (j?.text) {
        $("#hub-clip").textContent = j.text;
        $("#hub-clip").classList.remove("muted");
      }
    });

  const fi = $("#file-input") as HTMLInputElement;
  $("#pick-btn").onclick = () => fi.click();
  fi.onchange = () => fi.files && sendFiles(fi.files);

  const dz = $("#dropzone");
  dz.ondragover = (e) => {
    e.preventDefault();
    dz.classList.add("drag");
  };
  dz.ondragleave = () => dz.classList.remove("drag");
  dz.ondrop = (e) => {
    e.preventDefault();
    dz.classList.remove("drag");
    if (e.dataTransfer?.files.length) sendFiles(e.dataTransfer.files);
  };

  const ti = $("#text-input") as HTMLInputElement;
  const doSend = () => {
    if (ti.value.trim()) {
      sendText(ti.value.trim());
      ti.value = "";
    }
  };
  $("#send-text").onclick = doSend;
  ti.onkeydown = (e) => e.key === "Enter" && doSend();

  $("#copy-clip").onclick = () => copyText($("#hub-clip").textContent || "");
  $("#push-clip").onclick = async () => {
    let text = "";
    try {
      text = await navigator.clipboard.readText();
    } catch {
      text = prompt("Paste your clipboard here:") || "";
    }
    if (text) {
      await api("/api/clipboard", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ text }),
      });
      toast("Clipboard pushed");
    }
  };

  drainShare();
}

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("./sw.js").catch(() => {});
}

if (token) {
  initApp();
} else {
  $("#pair-view").classList.remove("hidden");
  $("#pair-btn").onclick = () => {
    const v = ($("#pair-input") as HTMLInputElement).value.trim();
    const m = v.match(/token=([^&\s]+)/);
    if (m) {
      localStorage.setItem("tandem.token", decodeURIComponent(m[1]));
      location.href = location.pathname;
    } else toast("Paste the full pair URL");
  };
}
