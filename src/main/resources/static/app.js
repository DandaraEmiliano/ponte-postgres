const element = (id) => document.getElementById(id);
const text = (id, value) => { element(id).textContent = value; };
const dialog = element("editor");
const form = element("client-form");
const pageSize = 6;
const clock = new Intl.DateTimeFormat("pt-BR", { timeStyle: "medium" });
let model;
let busy = false;
let refreshVersion = 0;
let latestActivity = "";
let latestAudit;
let customers = [];
let customersLoaded = false;
let page = 0;
let editing;
let saving = false;

async function request(path, method = "GET", body) {
  const response = await fetch("/api" + path, {
    method,
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
    cache: "no-store",
    signal: AbortSignal.timeout(14000),
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new Error(problem.detail || "Não foi possível concluir a operação.");
  }
  if (response.status !== 204) return response.json();
}

function message(id, value = "", error = false) {
  text(id, value);
  element(id).hidden = !value;
  element(id).classList.toggle("error", error);
}

function gateMessage({ estado, log, podeVirar, leituraEfetiva, workerComErro }) {
  if (workerComErro) return "O processo encontrou um erro e tentará retomar a partir do checkpoint.";
  if (estado.destinoAtivo) return leituraEfetiva === "DESTINO"
    ? "A aplicação está lendo do destino. As gravações seguem na origem."
    : "O destino está atrasado. A aplicação voltou temporariamente a ler da origem.";
  if (estado.fase === "IDLE") return "Inicie a migração para preparar o destino.";
  if (estado.fase === "COPY") return "Troca bloqueada enquanto a cópia estiver em andamento.";
  if (estado.pausado) return "Troca bloqueada enquanto a replicação estiver pausada.";
  if (log.pendentes) return "Troca bloqueada enquanto houver mudanças pendentes.";
  if (podeVirar) return "Dados conferidos na posição atual. O destino pode receber leituras.";
  return "Fila vazia. Confira os dados na posição atual para liberar a troca.";
}

function renderPanel() {
  if (!model) return;
  const { estado, log, clientesOrigem, clientesDestino, leituraEfetiva, podeVirar, atividade } = model;
  const idle = estado.fase === "IDLE";
  const copying = estado.fase === "COPY";
  text("source-count", clientesOrigem);
  text("target-count", clientesDestino);
  text("phase", idle ? "Aguardando início" : copying ? "Copiando registros"
    : estado.pausado ? "Replicação pausada" : "Replicação ativa");
  text("pending", idle ? "Fila disponível após iniciar" : log.pendentes + " mudança(s) pendente(s)");
  element("copy-progress").value = idle ? 0
    : copying ? Math.min(100, 100 * estado.copiados / Math.max(1, estado.total)) : 100;
  text("copy-label", idle ? "Cópia ainda não iniciada"
    : estado.copiados + " registro(s) copiado(s)" + (copying ? "" : " · cópia concluída"));
  text("source-position", log.atual);
  text("target-position", idle ? "—" : estado.aplicado);
  text("delay", idle ? "—" : Math.max(0, log.atrasoSegundos) + " s");
  text("route", "Leitura na " + (leituraEfetiva === "DESTINO" ? "base de destino" : "origem"));
  text("gate", gateMessage(model));
  text("pause", estado.pausado ? "Retomar replicação" : "Pausar replicação");
  text("switch", estado.destinoAtivo ? "Voltar para origem" : "Ler do destino");
  element("start").disabled = busy || !idle;
  element("pause").disabled = busy || idle;
  element("verify").disabled = busy || idle;
  element("switch").disabled = busy || (!podeVirar && !estado.destinoAtivo);
  element("reset").disabled = busy || idle;
  renderActivity(atividade);
  renderAudit();
}

function renderActivity(items) {
  const signature = JSON.stringify(items);
  if (signature === latestActivity) return;
  latestActivity = signature;
  element("activity-empty").hidden = items.length > 0;
  element("activity").replaceChildren(...items.map((event) => {
    const row = document.createElement("li");
    const time = document.createElement("time");
    time.dateTime = event.quando;
    time.textContent = clock.format(new Date(event.quando));
    const message = document.createElement("span");
    message.textContent = event.mensagem;
    row.append(time, message);
    return row;
  }));
}

function renderAudit() {
  element("audit").hidden = !latestAudit;
  if (!latestAudit) return;
  const summary = latestAudit.completa ? "Comparação na mesma posição."
    : "Comparação provisória enquanto a cópia ou a replicação avança.";
  const old = model && model.log.atual !== latestAudit.posicaoOrigem;
  text("audit-summary", summary + " " + latestAudit.totalDivergencias + " diferença(s)."
    + (old ? " Há mudanças na origem desde esta conferência." : ""));
}

function showAudit(result) {
  latestAudit = result;
  element("audit-diff").replaceChildren(...result.divergencias.map((diff) => {
    const line = document.createElement("li");
    const describe = (client) => !client ? "ausente" : client.nome + " · " + client.email + " · v" + client.versao;
    line.textContent = "#" + diff.id + " — Origem: " + describe(diff.origem) + " · Destino: " + describe(diff.destino);
    return line;
  }));
  renderAudit();
}

async function refresh() {
  const version = ++refreshVersion;
  const result = await request("/migracao");
  if (version !== refreshVersion) return;
  model = result;
  message("connection");
  renderPanel();
}

async function action(operation, successMessage) {
  if (busy) return;
  busy = true;
  renderPanel();
  message("notice");
  try {
    await operation();
    if (successMessage) message("notice", successMessage);
  } catch (error) {
    message("notice", error.message, true);
  } finally {
    busy = false;
    await refresh().catch((error) => message("notice", error.message, true));
    renderPanel();
  }
}

element("start").addEventListener("click", () => action(() => request("/migracao/iniciar", "POST")));
element("pause").addEventListener("click", () => action(() =>
  request("/migracao/" + (model.estado.pausado ? "retomar" : "pausar"), "POST")));
element("verify").addEventListener("click", () => action(async () =>
  showAudit(await request("/migracao/conferir", "POST"))));
element("switch").addEventListener("click", () => action(() =>
  request("/migracao/" + (model.estado.destinoAtivo ? "voltar-origem" : "ativar-destino"), "POST")));
element("reset").addEventListener("click", () => action(async () => {
  await request("/migracao/reiniciar", "POST");
  latestAudit = null;
  renderAudit();
  message("read-result");
}, "Migração reiniciada. Os clientes na origem foram preservados."));
element("read").addEventListener("click", async () => {
  element("read").disabled = true;
  try {
    const result = await request("/clientes");
    const first = result.clientes[0];
    const bank = result.banco === "DESTINO" ? "destino" : "origem";
    const sample = first ? " Primeiro registro: " + first.nome + " (v" + first.versao + ")." : "";
    message("read-result", result.clientes.length + " registro(s) lidos da base de " + bank + "." + sample);
  } catch (error) {
    message("read-result", error.message, true);
  } finally {
    element("read").disabled = false;
  }
});

function renderCustomers() {
  const maxPage = Math.max(0, Math.ceil(customers.length / pageSize) - 1);
  page = Math.min(page, maxPage);
  const start = page * pageSize;
  const visible = customers.slice(start, start + pageSize);
  element("clients").replaceChildren(...visible.map((client) => {
    const row = document.createElement("tr");
    for (const value of [client.id, client.nome, client.email, client.versao]) {
      const cell = document.createElement("td");
      cell.textContent = value;
      row.append(cell);
    }
    const controls = document.createElement("td");
    const edit = document.createElement("button");
    edit.className = "button text-button";
    edit.type = "button";
    edit.textContent = "Editar";
    edit.setAttribute("aria-label", "Editar " + client.nome);
    edit.addEventListener("click", () => openEditor(client));
    controls.append(edit);
    row.append(controls);
    return row;
  }));
  text("page-range", customers.length ? (start + 1) + "–" + (start + visible.length)
    + " de " + customers.length : "Nenhum cliente");
  element("prev").disabled = page === 0;
  element("next").disabled = page >= maxPage;
}

async function refreshCustomers() {
  customers = await request("/origem/clientes");
  customersLoaded = true;
  renderCustomers();
}

function openEditor(client) {
  editing = client;
  form.reset();
  text("editor-title", editing ? "Editar cliente" : "Novo cliente");
  text("remove", "Excluir");
  element("remove").hidden = !editing;
  form.elements.nome.value = editing?.nome ?? "";
  form.elements.email.value = editing?.email ?? "";
  message("editor-error");
  dialog.showModal();
}

async function editOperation(operation) {
  if (saving) return;
  saving = true;
  element("remove").disabled = true;
  element("save").disabled = true;
  element("cancel").disabled = true;
  message("editor-error");
  try {
    await operation();
    dialog.close();
    await Promise.all([refreshCustomers(), refresh()]).catch((error) => message("notice", error.message, true));
  } catch (error) {
    message("editor-error", error.message, true);
  } finally {
    saving = false;
    element("remove").disabled = false;
    element("save").disabled = false;
    element("cancel").disabled = false;
  }
}

form.addEventListener("submit", (event) => {
  event.preventDefault();
  const body = Object.fromEntries(new FormData(form));
  const path = "/origem/clientes" + (editing ? "/" + editing.id : "");
  editOperation(() => request(path, editing ? "PUT" : "POST", body));
});

element("remove").addEventListener("click", () => {
  if (element("remove").textContent === "Excluir") {
    text("remove", "Confirmar exclusão");
    message("editor-error", "Isso remove o cadastro da origem e registra a exclusão para o destino.");
    return;
  }
  editOperation(() => request("/origem/clientes/" + editing.id, "DELETE"));
});

element("add").addEventListener("click", () => openEditor());
element("cancel").addEventListener("click", () => dialog.close());
dialog.addEventListener("cancel", (event) => { if (saving) event.preventDefault(); });
element("prev").addEventListener("click", () => { page--; renderCustomers(); });
element("next").addEventListener("click", () => { page++; renderCustomers(); });

async function poll() {
  if (!busy) {
    try {
      await refresh();
      if (!customersLoaded) await refreshCustomers();
    } catch {
      message("connection", "Não foi possível carregar os dados. Tentando reconectar…", true);
    }
  }
  setTimeout(poll, 1000);
}
poll();
