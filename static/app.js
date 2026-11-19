"use strict";

const app = document.querySelector("#app");
const modal = document.querySelector("#modal");
const toast = document.querySelector("#toast");
const currency = new Intl.NumberFormat("en-IN", {style: "currency", currency: "INR"});
const dateFormat = new Intl.DateTimeFormat("en-IN", {day: "numeric", month: "short", year: "numeric"});
const filters = {};
let state;
let config = {demo: false};
let auth = {view: "login"};
let selection = new Set();
let toastTimer;
let access = [];

const esc = (value = "") => String(value).replace(/[&<>"']/g, c => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
}[c]));
const money = amount => currency.format(amount / 100);
const decimal = amount => (amount / 100).toFixed(2);
const dayText = value => value ? dateFormat.format(new Date(value + "T00:00:00")) : "—";
const sum = (rows, key) => rows.reduce((total, row) => total + row[key], 0);
const attr = (name, value) => value ? ` ${name}` : "";
const brand = `<button class="brand" type="button" data-action="home"><span class="brand-mark" aria-hidden="true">P</span>PayPilot</button>`;
const button = (text, action, cls = "", id = "") =>
  `<button type="button" class="button ${cls}" data-action="${action}" data-id="${id}">${text}</button>`;
const option = (value, selected, label = value) =>
  `<option value="${esc(value)}"${attr("selected", value === selected)}>${esc(label)}</option>`;
const options = (values, value) => values.map(item => option(item, value)).join("");
const field = (label, name, type = "text", value = "", config = "") =>
  `<label>${label}<input type="${type}" name="${name}" value="${esc(value)}" ${config}></label>`;
const dropdown = (label, name, values, value) =>
  `<label>${label}<select name="${name}">${options(values, value)}</select></label>`;
const rowFilter = (row, f, key = "due", nameKey = "name") =>
  (!f.category || row.category === f.category) &&
  (!f.q || row[nameKey].toLowerCase().includes(f.q.trim().toLowerCase())) &&
  (!f.from || row[key] >= f.from) && (!f.to || row[key] <= f.to);

function tell(message) {
  toast.textContent = message;
  toast.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { toast.hidden = true; }, 4300);
}

async function api(path, data) {
  const options = data === undefined ? {} : {
    method: "POST",
    headers: {"Content-Type": "application/json", "X-PayPilot": "1"},
    body: JSON.stringify(data)
  };
  const response = await fetch("/api/" + path, options);
  const body = await response.json();
  if (!response.ok) {
    const error = new Error(body.error || "The request could not be completed.");
    error.status = response.status;
    throw error;
  }
  return body;
}

async function load(refresh = false) {
  if (refresh) await api("refresh", {});
  state = await api("state");
  await loadAdminView();
}

async function loadAdminView() {
  const [page, tab] = route();
  if (page === "access" && state?.user.role === "admin") {
    access = await api(tab === "audit" && config.audit_available ? "audit" : "access");
  }
}

function route() {
  const [page, tab] = location.hash.replace(/^#\/?/, "").split("/");
  return [["overview", "bills", "schedule", "track", "reports", "access"].includes(page) ? page : "overview", tab];
}

function go(page) {
  const hash = "#/" + page;
  if (location.hash === hash) render();
  else location.hash = hash;
}

function authFields() {
  const identity = field("User ID", "username", "text", auth.username, 'required autocomplete="username" maxlength="40"');
  const email = field("Registered email", "email", "email", "", 'required autocomplete="email"');
  const current = field("Password", "password", "password", "", 'required autocomplete="current-password" maxlength="128"');
  const password = [
    field("New password", "password", "password", "", 'required autocomplete="new-password" minlength="10" maxlength="128"'),
    field("Confirm password", "confirm", "password", "", 'required autocomplete="new-password" minlength="10" maxlength="128"')
  ].join("");
  const code = field("Email verification code", "code", "text", "", 'required autocomplete="one-time-code" inputmode="numeric" pattern="[0-9]{6}"');
  switch (auth.view) {
    case "register":
      return ["Request access", "An administrator reviews every request. Use sample banking details for this local build.",
        identity + email + field("PAN", "pan", "text", "", 'required maxlength="10" placeholder="ABCDE1234F"') +
        field("Bank account number", "account", "text", "", 'required inputmode="numeric" maxlength="18"') +
        field("IFSC code", "ifsc", "text", "", 'required maxlength="11" placeholder="DEMO0123456"') +
        field("Banking partner", "bank", "text", "", 'required maxlength="120" placeholder="Demo Bank"') + password,
        "Submit request"];
    case "reset":
      return ["Reset password", "Enter your user ID and the email you registered with.", identity + email, "Send code"];
    case "verify-reset":
      return ["Set a new password", auth.message,
        `<div class="full">${code}</div>` + password, "Update password"];
    case "verify-login":
      return ["Verify your sign-in", "Enter the six-digit code sent to your registered email.",
        `<div class="full">${code}</div>` + (auth.pin ? field("Access PIN", "pin", "password", "",
          'required inputmode="numeric" pattern="[0-9]{6,8}" autocomplete="off"') : ""), "Sign in"];
    default: {
      const c = auth.challenge;
      const screen = c ? `<fieldset class="challenge full"><legend>Screen verification · Select the ${esc(c.prompt)}</legend>
        <div class="shapes">${c.shapes.map(shape => `<label><input type="radio" name="answer" value="${esc(shape)}"
          aria-label="${esc(c.names[shape])}" required><span class="shape" aria-hidden="true">${esc(shape)}</span></label>`).join("")}</div></fieldset>`
        : `<div class="full muted">Loading screen verification… ${button("Reload", "auth-login", "quiet small")}</div>`;
      return ["Welcome back", "Sign in to manage your bills and payments.", identity + current + screen, "Continue"];
    }
  }
}

function renderAuth() {
  const [title, intro, fields, submit] = authFields();
  const links = auth.view === "login" ?
    button("Forgot password?", "auth-reset", "quiet") + button("Request access", "auth-register", "quiet") :
    button("Back to sign in", "auth-login", "quiet");
  app.innerHTML = `<main class="auth">
    <section class="auth-branding" aria-label="PayPilot">
      <div class="auth-orbit" aria-hidden="true"></div>${brand}
      <div><h1>All your bills in one place.</h1><p>See what’s due, plan your payments, and keep a record of what you’ve paid.</p></div>
      <div></div>
    </section>
    <div class="auth-form"><section class="auth-card"><h2>${title}</h2><p>${esc(intro)}</p>
      ${config.maintenance_notice ? `<p class="callout" role="status"><strong>Scheduled maintenance</strong> ${esc(config.maintenance_notice)}</p>` : ""}
      ${config.demo && auth.view.startsWith("verify-") ? '<p class="callout">In demo mode, check the server terminal when SMTP is unconfigured.</p>' : ""}
      <form data-form="auth"><div class="form-grid">${fields}</div>
      <p class="form-error" role="alert">${esc(auth.error)}</p>
      <button class="button primary" type="submit"${attr("disabled", auth.view === "login" && !auth.challenge)}>${submit}</button>
      </form><div class="auth-links">${links}
      ${config.demo && auth.view === "login" ? button("Open sample workspace", "demo") : ""}
      </div></section></div></main>`;
}

async function authView(view) {
  auth = {view};
  renderAuth();
  if (view === "login") {
    try { auth.challenge = await api("challenge", {}); }
    catch (error) { auth.error = error.message; }
    renderAuth();
  }
}

function status(bill) {
  if (!bill.balance) return ["Paid", "green"];
  if (bill.paid) return ["Partially paid", "blue"];
  if (bill.overdue_days) return ["Overdue", "red"];
  if (bill.due === state.today) return ["Due today", "red"];
  return ["Upcoming", ""];
}

const pill = (text, color = "") => `<span class="pill ${color}">${esc(text)}</span>`;
const billPill = bill => pill(...status(bill));
const pageHead = (title, text, actions = "") => `<header class="page-head"><div><h1>${title}</h1><p>${text}</p></div>
  ${actions ? '<div class="actions">' + actions + '</div>' : ""}</header>`;
const panel = (title, body, extra = "", description = "") => `<section class="panel">
  <div class="panel-title"><div><h2>${title}</h2>${description ? '<p>' + description + '</p>' : ""}</div>${extra}</div>${body}</section>`;
const empty = message => `<p class="empty">${message}</p>`;
const table = (headings, rows, fallback = "No matching records.") => rows.length ?
  `<div class="table-wrap"><table><thead><tr>${headings.map(label => `<th scope="col">${label}</th>`).join("")}
  </tr></thead><tbody>${rows.map(cells => `<tr>${cells.map(cell => `<td>${cell}</td>`).join("")}</tr>`).join("")}</tbody></table></div>` :
  empty(fallback);
const stat = (title, icon, amount, description, main = false) =>
  `<section class="stat ${main ? "main" : ""}"><div class="stat-label"><span class="stat-icon" aria-hidden="true">${icon}</span>${title}</div>
  <div class="stat-value">${money(amount)}</div><p>${description}</p></section>`;
const nameCell = b => `<strong>${esc(b.name)}</strong><small>${esc(b.category)}</small>`;
const amountCell = amount => `<span class="amount">${money(amount)}</span>`;
const tabs = (page, current, items) => `<nav class="tabs" aria-label="Views">${items.map(([key, text]) =>
  `<a class="button" href="#/${page}/${key}"${current === key ? ' aria-current="page"' : ""}>${text}</a>`).join("")}</nav>`;

function chart(title, groups, desc) {
  const max = Math.max(1, ...groups.map(([, amount]) => amount));
  const body = groups.length && groups.some(([, amount]) => amount) ?
    `<div class="chart">${groups.map(([label, amount]) => `<div class="chart-row">
      <div class="chart-key"><span>${esc(label)}</span>${amountCell(amount)}</div>
      <progress max="${max}" value="${amount}" aria-label="${esc(label)}" aria-valuetext="${money(amount)}"></progress>
    </div>`).join("")}</div>` : empty("Recorded payments will appear here.");
  return panel(title, `<div class="panel-body">${body}</div>`, "", desc);
}

function byCategory(rows) {
  const totals = new Map();
  rows.forEach(p => totals.set(p.category, (totals.get(p.category) || 0) + p.amount));
  return [...totals].sort((a, b) => b[1] - a[1]);
}

function overview() {
  const unpaid = state.bills.filter(b => b.balance);
  const scheduled = state.schedules.filter(s => s.status === "Scheduled");
  const month = state.payments.filter(p => p.day.slice(0, 7) === state.today.slice(0, 7));
  const overdue = unpaid.filter(b => b.overdue_days);
  const upcoming = unpaid.slice(0, 5);
  return `${pageHead("Your bill overview", dayText(state.today), button("+ Add bill", "add-bill", "primary"))}
    <div class="inset">${stat("Outstanding", "₹", sum(unpaid, "balance"), overdue.length + " overdue", true)}
    ${stat("Scheduled", "◷", sum(scheduled, "amount"), scheduled.length + " active payment plans")}
    ${stat("Paid this month", "✓", sum(month, "amount"), month.length + " recorded payments")}</div>
    <div class="dashboard-grid">${panel("Bills to watch", table(["Bill", "Due date", "Balance", "Status"], upcoming.map(b => [
      nameCell(b), dayText(b.due), amountCell(b.balance), billPill(b)
    ]), "You’re caught up. Add a bill to start tracking due dates."),
    '<a class="button small" href="#/bills">All bills</a>', "Unpaid bills ordered by due date.")}
    <div>${chart("Spending by category", byCategory(month), "Payments recorded this month")}
    ${panel("Payment plans", `<div class="panel-body">${scheduled.length ? `<ul class="pane-list">${scheduled.slice(0, 3).map(s =>
      `<li><span><strong>${esc(s.bill_name)}</strong><br><span class="muted">${dayText(s.day)}</span></span>${amountCell(s.amount)}</li>`).join("")}</ul>` :
      empty("You have no scheduled payments.")}</div>`, '<a class="button small" href="#/schedule">Open</a>')}</div></div>`;
}

function categories() {
  return [...new Set([...state.categories, ...state.bills.map(b => b.category), ...state.payments.map(p => p.category),
    ...state.schedules.map(s => s.category)])].sort((a, b) => a.localeCompare(b));
}

function filterBar(page, statusList = [], sort = false) {
  const f = filters[page] || {};
  return `<form class="filters" data-form="filters" data-page="${page}">
    <label class="wide">Search<input type="search" name="q" value="${esc(f.q)}" maxlength="120" placeholder="Bill name"></label>
    <label>Category<select name="category">${option("", f.category || "", "All categories")}${options(categories(), f.category)}</select></label>
    ${field("From", "from", "date", f.from)}${field("To", "to", "date", f.to)}
    ${statusList.length ? `<label>Status<select name="status">${option("", f.status || "", "All statuses")}${options(statusList, f.status)}</select></label>` : ""}
    ${sort ? dropdown("Sort", "sort", ["Due date", "Most overdue", "Priority"], f.sort) : ""}
    <button type="submit" class="button primary">Apply</button>${button("Clear", "clear-filters", "quiet", page)}
    <p class="form-error full" role="alert"></p></form>`;
}

function billsList(view) {
  const f = filters.bills || {};
  let rows = state.bills.filter(b => rowFilter(b, f) &&
    (!f.status || f.status === status(b)[0] || (f.status === "Pending" && b.balance)) &&
    (view !== "upcoming" || (b.balance && !b.overdue_days)) && (view !== "overdue" || b.overdue_days) &&
    (view !== "timeline" || b.balance));
  if (f.sort === "Most overdue") rows.sort((a, b) => b.overdue_days - a.overdue_days);
  if (f.sort === "Priority") rows.sort((a, b) => a.priority.localeCompare(b.priority) || a.due.localeCompare(b.due));
  const counts = categories().map(category => [category, state.bills.filter(b => b.category === category).length])
    .filter(([, count]) => count);
  const columns = ["Select", "Bill", "Due date", "Total", "Balance", "Status", "Actions"];
  return `${pageHead("Manage bills", "Find, organize and update your bills.", button("+ Add bill", "add-bill", "primary"))}
    ${tabs("bills", view, [["all", "Bills overview"], ["upcoming", "Upcoming"], ["overdue", "Overdue"], ["timeline", "Due-date timeline"]])}
    ${view === "all" && counts.length ? `<div class="chips" aria-label="Category shortcuts">${counts.map(([category, count]) =>
      `<button type="button" class="button small ${f.category === category ? "active" : ""}" data-action="category" data-id="${esc(category)}">${esc(category)} · ${count}</button>`).join("")}</div>` : ""}
    ${filterBar("bills", ["Upcoming", "Due today", "Pending", "Overdue", "Partially paid", "Paid"], true)}
    <div class="toolbar"><span class="muted" id="selection-count">${selection.size} selected</span>
      ${button("Pay now · Simulate", "batch-pay", "small")}${button("Mark paid", "batch-mark", "small")}${button("Snooze", "batch-snooze", "small")}</div>
    ${panel(view === "timeline" ? "Due-date timeline" : "Bills", table(columns, rows.map(b => [
      `<input type="checkbox" data-select="${b.id}" aria-label="Select ${esc(b.name)}"${attr("checked", selection.has(b.id))}${attr("disabled", !b.balance)}>`,
      nameCell(b), dayText(b.due) + (b.overdue_days ? `<small>${b.overdue_days} days overdue</small>` : ""),
      amountCell(b.amount), amountCell(b.balance), billPill(b) +
        (b.snoozed > state.today ? `<small>Snoozed to ${dayText(b.snoozed)}</small>` : "") +
        (b.priority === "High" ? "<small>High priority</small>" : ""),
      `<div class="actions">${button("Edit", "edit-bill", "small", b.id)}${b.balance ? button("Record", "record", "small", b.id) : ""}
      ${b.attachment ? `<a class="button small" href="/api/attachment/${b.id}">PDF</a>` : ""}</div>`
    ]), "No bills match this view."))}`;
}

function scheduleList() {
  const f = filters.schedule || {};
  const rows = state.schedules.filter(s => rowFilter(s, f, "day", "bill_name") && (!f.status || f.status === s.status));
  const active = state.schedules.filter(s => s.status === "Scheduled");
  return `${pageHead("Schedule payments", "Plan simulated payments and adjust active schedules.", button("+ Schedule payment", "add-schedule", "primary"))}
    <div class="inset">${stat("Total scheduled", "◷", sum(active, "amount"), active.length + " active schedules", true)}
    ${stat("Total outstanding", "₹", sum(state.bills, "balance"), "Remaining across all active bills")}</div>
    ${filterBar("schedule", ["Scheduled", "Completed", "Cancelled"])}
    ${panel("Payment schedules", table(["Bill", "Payee", "Payment date", "Amount", "Status", "Actions"], rows.map(s => [
      `<strong>${esc(s.bill_name)}</strong><small>${esc(s.category)}</small>`, `${esc(s.payee)}<small>${esc(s.method)}</small>`,
      dayText(s.day), amountCell(s.amount), pill(s.status, s.status === "Completed" ? "green" : s.status === "Cancelled" ? "gray" : ""),
      s.status === "Scheduled" ? `<div class="actions">${button("Reschedule", "reschedule", "small", s.id)}
        ${button("Cancel", "cancel-schedule", "small danger", s.id)}</div>` : "—"
    ]), "No schedules match this view."))}`;
}

function track(view) {
  const f = filters.track || {};
  let rows = state.bills.filter(b => rowFilter(b, f));
  if (view === "priorities") rows = rows.filter(b => b.balance && b.priority === "High");
  if (view === "due") rows = rows.filter(b => b.balance);
  const history = state.payments.filter(p => rowFilter(p, f, "day", "bill_name"));
  const attempts = (state.payment_attempts || []).filter(p => rowFilter(p, f, "day", "bill_name"));
  const headings = ["Bill", "Due date", "Original amount", "Outstanding", view === "progress" ? "Progress" : "Payment status"];
  const output = view === "attempts" ? attemptTable(attempts) :
    view === "history" ? table(["Bill", "Due date", "Paid on", "Method", "Amount"], history.map(p => [
    `<strong>${esc(p.bill_name)}</strong><small>${esc(p.category)}</small>`, dayText(p.due), dayText(p.day),
    `${esc(p.method)}<small>${esc(p.source)}</small>`, amountCell(p.amount)
  ]), "No recorded payments match these filters.") : table(headings, rows.map(b => [
    nameCell(b), dayText(b.due) + (b.overdue_days ? `<small>${b.overdue_days} days overdue</small>` : ""),
    amountCell(b.amount), amountCell(b.balance), view === "progress" ? `<div class="progress-cell"><progress class="${!b.balance ? "green" : b.paid ? "blue" : "red"}"
      max="${b.amount}" value="${b.paid}" aria-label="Paid toward ${esc(b.name)}"></progress><span>
      ${money(b.paid)} paid · ${!b.balance ? "Paid completely" : b.paid ? "Paid partially" : "Pending"}</span></div>` : billPill(b)
  ]));
  const views = [["progress", "Payment progress"], ["overview", "Payment overview"], ["history", "Payment history"],
    ["priorities", "Payment priorities"], ["due", "Upcoming and overdue"], ["attempts", "Test transactions"]];
  return `${pageHead("Track payments", "Review payment progress, balances and activity.")}
    ${tabs("track", view, views)}${filterBar("track")}${panel(views.find(([key]) => key === view)[1], output)}`;
}

function attemptTable(attempts) {
  return table(["Bill and reference", "Method", "Amount", "Result"], attempts.map(attempt => [
    `<strong>${esc(attempt.bill_name)}</strong><small class="receipt-reference">${esc(attempt.reference)}</small>
    ${attempt.day ? `<small>${dayText(attempt.day)}</small>` : ""}`,
    `${esc(attempt.method)}<small>${esc(attempt.institution)}</small>`, amountCell(attempt.amount),
    pill(attempt.status, attempt.status === "Settled" ? "green" : "red")
  ]), "Test payment receipts will appear here.");
}

function reports() {
  const f = filters.reports || {};
  const rows = state.payments.filter(p => rowFilter(p, f, "day", "bill_name"));
  const months = new Map();
  for (const row of rows) {
    const key = row.day.slice(0, 7);
    months.set(key, (months.get(key) || 0) + row.amount);
  }
  const labels = new Intl.DateTimeFormat("en-IN", {month: "short", year: "numeric"});
  const trend = [...months].sort(([a], [b]) => a.localeCompare(b)).map(([key, amount]) =>
    [labels.format(new Date(key + "-01T00:00:00")), amount]);
  return `${pageHead("Spending reports", "Explore recorded payments by category and month.")}
    ${filterBar("reports")}<div class="inset">${stat("Total recorded", "₹", sum(rows, "amount"), rows.length + " payments in this view", true)}</div>
    <div class="dashboard-grid">${chart("Monthly payments", trend, "Recorded payment amounts")}
    ${chart("By category", byCategory(rows), "Largest categories first")}</div>`;
}

function accessView(tab) {
  const showAudit = config.audit_available && tab === "audit";
  const nav = config.audit_available ?
    tabs("access", showAudit ? "audit" : "requests", [["requests", "Access requests"], ["audit", "Audit trail"]]) : "";
  if (showAudit) {
    return `${pageHead("Audit trail", "Review the latest account and transaction activity.")}${nav}
    ${panel("Recent events", table(["Time", "Actor", "Action", "Record"], access.map(entry => [
      entry.at ? esc(new Date(entry.at).toLocaleString()) : "—", esc(entry.actor), esc(entry.action), esc(entry.target)
    ]), "No activity has been recorded."))}`;
  }
  return `${pageHead("Access requests", "Approve or decline pending requests.")}
    ${nav}${panel("Account requests", table(["User ID", "Email", "Status", "Actions"], access.map(row => [
      esc(row.username), esc(row.email), pill(row.status === "pending" ? "Pending" : "Declined", "gray"),
      `<div class="actions">${button("Approve", "approve", "primary small", row.id)}
      ${row.status === "pending" ? button("Decline", "decline", "small danger", row.id) : ""}</div>`
    ]), "No pending or declined requests."))}`;
}

function render() {
  if (!state) { renderAuth(); return; }
  const navigation = [["overview", "◈", "Overview"], ["bills", "▤", "Manage bills"],
    ["schedule", "◷", "Schedule payments"], ["track", "◉", "Track payments"], ["reports", "▥", "Reports"]];
  if (state.user.role === "admin") navigation.push(["access", "▣", "Access requests"]);
  const [page, tab] = route();
  const displayName = state.user.username.startsWith("demo-") ? "Demo account" : state.user.username;
  const active = page === "access" && state.user.role !== "admin" ? "overview" : page;
  const unread = state.notifications.filter(n => !n.read).length;
  const notificationIcon = `<span aria-hidden="true">♢</span>${unread ? `<span class="count" aria-hidden="true">${unread}</span>` : ""}`;
  const views = {overview, bills: () => billsList(["all", "upcoming", "overdue", "timeline"].includes(tab) ? tab : "all"),
    schedule: scheduleList, track: () => track(["progress", "overview", "history", "priorities", "due", "attempts"].includes(tab) ? tab : "progress"),
    reports, access: () => accessView(tab)};
  app.innerHTML = `<div class="shell"><aside class="sidebar">${brand}<nav aria-label="Main navigation">
    ${navigation.map(([key, icon, label]) => `<a href="#/${key}" class="nav-button"${active === key ? ' aria-current="page"' : ""}
       ><span aria-hidden="true">${icon}</span><span>${label}</span></a>`).join("")}</nav>
    <div class="side-bottom"><div class="mode">Payment simulation is active.</div>${button("Help", "help", "small")}</div></aside>
    <div class="content"><header class="topbar"><strong>Bill workspace</strong><div class="top-controls">
      <button class="button icon" type="button" data-action="notifications" aria-label="Notifications (${unread} unread)">${notificationIcon}</button>
      <button type="button" class="button profile-button" data-action="profile" aria-label="Profile"><span class="avatar" aria-hidden="true">${esc(displayName[0])}</span><span class="avatar-name">${esc(displayName)}</span></button>
    </div></header><main class="workspace" id="workspace">
      ${config.maintenance_notice ? `<p class="callout" role="status"><strong>Scheduled maintenance</strong> ${esc(config.maintenance_notice)}</p>` : ""}
      ${views[active]()}</main></div></div>`;
}

function dialog(title, content) {
  modal.innerHTML = `<header class="dialog-top"><h2 id="modal-title">${title}</h2>${button("Close", "close", "small")}</header>
    <div class="dialog-body">${content}</div>`;
  if (!modal.open) modal.showModal();
}

function formDialog(title, endpoint, content, submit, details = "", id = "") {
  dialog(title, `<form data-form="change" data-endpoint="${endpoint}" data-id="${id}">
    ${details}<div class="form-grid">${content}</div><p class="form-error" role="alert"></p>
    <div class="form-actions">${button("Back", "close")}<button class="button primary" type="submit">${submit}</button></div></form>`);
}

function billDialog(b = {}) {
  const required = "required";
  const recurring = Boolean(b.series);
  formDialog(b.id ? "Edit bill" : "Add a bill", "bill",
    field("Bill name", "name", "text", b.name, required + ' maxlength="120"') +
    field("Billing company", "company", "text", b.company, required + ' maxlength="120"') +
    field("Category", "category", "text", b.category, 'required list="bill-categories" maxlength="60"') +
    `<datalist id="bill-categories">${options(categories())}</datalist>` +
    field("Due date", "due", "date", b.due || state.today, required) +
    field("Amount (₹)", "amount", "text", b.amount ? decimal(b.amount) : "", 'required inputmode="decimal" maxlength="12"') +
    `<label class="check"><input type="checkbox" name="recurring"${attr("checked", recurring)}${attr("disabled", recurring)}>
      Recurring bill</label>` +
    dropdown("Payment frequency", "payment_frequency", ["Weekly", "Bi-weekly", "Monthly", "Yearly"],
      recurring ? b.payment_frequency : "Monthly") +
    dropdown("Priority", "priority", ["Normal", "High"], b.priority) +
    `<div></div><h3 class="full">Reminder settings</h3>` +
    dropdown("Reminder frequency", "reminder_frequency", state.frequencies, b.reminder_frequency) +
    field("Reminder start", "reminder_start", "date", b.reminder_start) +
    `<label>Notification preference<select name="channel">
      ${option("In app", b.channel)}${option("Email", b.channel)}${option("Contact", b.channel, "Contact · SMS preview")}
    </select></label>` +
    `<label class="check"><input type="checkbox" name="remind_recurring"${attr("checked", b.remind_recurring ?? true)}> Remind for future occurrences</label>
    <label class="full">Reminder message<textarea name="reminder_message" maxlength="250">${esc(b.reminder_message)}</textarea></label>
    <label class="full">Notes<textarea name="notes" maxlength="1000">${esc(b.notes)}</textarea></label>
    <label class="full">PDF attachment (up to 3 MB)<input type="file" name="pdf" accept=".pdf,application/pdf"></label>
    ${b.attachment ? '<label class="check full"><input type="checkbox" name="remove_attachment"> Remove existing attachment</label>' : ""}
    ${b.id ? button("Delete bill", "delete-bill", "danger small", b.id) : ""}`, "Save bill",
    b.series ? '<p class="callout">Edits apply to this occurrence. Its due date and recurrence are fixed.</p>' : "", b.id);
  modal.querySelector('[name="payment_frequency"]').disabled = !recurring;
  if (recurring) {
    modal.querySelector('[name="due"]').readOnly = true;
    const freq = modal.querySelector('[name="payment_frequency"]');
    freq.innerHTML = option(b.payment_frequency, b.payment_frequency);
  }
}

function scheduleDialog() {
  const available = state.bills.filter(b => b.balance > b.scheduled);
  if (!available.length) { dialog("Schedule payment", empty("Add an unpaid bill with an unreserved balance first.")); return; }
  const [first] = available;
  formDialog("Schedule payment", "schedule", `<div class="form-grid full">
    <label>Bill category<select name="pick_category">${option("", "", "All categories")}${options(categories())}</select></label>
    <div></div>${field("Due from", "pick_from", "date")}${field("Due to", "pick_to", "date")}</div>
    <label class="full">Bill<select name="bill_id" required>
    ${available.map(b => option(String(b.id), String(first.id), b.name + " · " + dayText(b.due))).join("")}</select></label>` +
    field("Purpose", "purpose", "text", "", 'required maxlength="120"') +
    field("Bill date", "bill_date", "date", first.due, "required") +
    field("Payee", "payee", "search", Object.keys(state.payees)[0], 'required list="payee-list"') +
    `<datalist id="payee-list">${options(Object.keys(state.payees))}</datalist><p id="payee-details" class="hint"></p>` +
    dropdown("Payment method", "method", state.methods) + dropdown("Funding source", "funding", state.funding) +
    field("Payment date", "day", "date", state.today, `required min="${state.today}"`) +
    field("Amount (₹)", "amount", "text", decimal(first.balance - first.scheduled), 'required inputmode="decimal" maxlength="12"') +
    dropdown("Payment frequency", "frequency", ["Once"], "Once"),
    "Confirm simulated schedule", '<p class="callout">This schedule creates a simulated payment record. No money moves.</p>');
  updateScheduledBill(first);
  updatePayee();
}

function filterScheduleBills(form) {
  const f = {category: form.elements.pick_category.value, from: form.elements.pick_from.value, to: form.elements.pick_to.value};
  const bills = state.bills.filter(b => b.balance > b.scheduled && rowFilter(b, f));
  const select = form.elements.bill_id;
  const previouslySelected = select.value;
  const selected = bills.find(b => String(b.id) === previouslySelected) || bills[0];
  select.innerHTML = bills.length ? bills.map(b => option(String(b.id), String(selected.id), b.name + " · " + dayText(b.due))).join("")
    : option("", "", "No unpaid bills match");
  form.querySelector('button[type="submit"]').disabled = !selected;
  if (selected) updateScheduledBill(selected);
}

function updateScheduledBill(b) {
  const form = modal.querySelector("form");
  form.elements.bill_date.value = b.due;
  form.elements.amount.value = decimal(b.balance - b.scheduled);
  form.elements.frequency.innerHTML = options(b.series ? ["Once", b.payment_frequency] : ["Once"], "Once");
  if (Object.prototype.hasOwnProperty.call(state.payees, b.company)) form.elements.payee.value = b.company;
  updatePayee();
}

function updatePayee() {
  const key = modal.querySelector('[name="payee"]').value;
  const details = document.querySelector("#payee-details");
  details.textContent = Object.prototype.hasOwnProperty.call(state.payees, key) ? state.payees[key].join(" · ") : "Select a payee from the list.";
}

function notificationsDialog() {
  const rows = state.notifications.map(n => [
    `${esc(n.message)}<small>${dayText(n.day)} · ${esc(n.delivery)}</small>
      ${n.delivery.startsWith("SMS preview") ? button("View SMS", "preview-sms", "small", n.id) : ""}`,
    n.read ? pill("Read", "gray") : button("Mark read", "read-note", "small", n.id)
  ]);
  dialog("Notifications", table(["Reminder", "Status"], rows, "No bill reminders yet."));
}

function profileDialog() {
  dialog("Profile", `<p><strong>${esc(state.user.username.startsWith("demo-") ? "Demo account" : state.user.username)}</strong></p>
    <p class="tall">${esc(state.user.email)}</p><div class="form-actions">
    ${button("Help", "help")}${button("Change password", "password")}
    ${button(state.user.pin ? "Manage access PIN" : "Set access PIN", "pin")}
    ${button("Sign out", "logout", "danger")}</div>`);
}

function plainDialog(title, endpoint, submit, message, id = "") {
  formDialog(title, endpoint, "", submit, `<p class="callout">${esc(message)}</p>`, id);
}

function batchDialog(action) {
  if (!selection.size) { tell("Select at least one unpaid bill."); return; }
  const rows = state.bills.filter(b => selection.has(b.id));
  const detail = `<p class="callout">${rows.length} bill(s) · ${money(sum(rows, "balance"))} outstanding</p>`;
  if (action === "batch-mark") {
    formDialog("Mark bills paid", "mark", "", "Confirm marked paid", detail + "<p>This records the selected balances as paid manually.</p>");
  } else if (action === "batch-snooze") {
    formDialog("Snooze reminders", "snooze", field("Resume on", "day", "date", "", "required"), "Snooze", detail);
  } else {
    formDialog("Pay now · Test payment", "pay",
      dropdown("Payment method", "method", state.methods) +
      dropdown("Demo institution", "institution", state.test_institutions || ["Demo Bank", "Demo Wallet"]) +
      dropdown("Test result", "outcome", state.test_outcomes || ["Approve", "Decline"]),
      "Run test payment", detail + "<p>This test uses sample institutions. No money moves.</p>");
  }
}

async function action(name, id) {
  const recordId = Number(id);
  if (name.startsWith("auth-")) return authView(name.slice(5));
  if (!state) {
    if (name === "demo") {
      await api("demo", {});
      await load(true);
      go("overview");
    } else if (name === "home") await authView("login");
    return;
  }
  const bill = state.bills.find(item => item.id === recordId);
  const schedule = state.schedules.find(item => item.id === recordId);
  switch (name) {
    case "home": go("overview"); break;
    case "close": modal.close(); break;
    case "add-bill": billDialog(); break;
    case "edit-bill": billDialog(bill); break;
    case "delete-bill":
      plainDialog("Delete bill", "archive", "Delete bill", "The bill will leave active views and its active schedules will be cancelled. Payment history is retained.", recordId);
      break;
    case "record":
      formDialog("Record an external payment", "record",
        field("Amount paid (₹)", "amount", "text", decimal(bill.balance), 'required inputmode="decimal" maxlength="12"') +
        field("Paid on", "day", "date", state.today, `required max="${state.today}"`) +
        dropdown("Payment method", "method", state.methods), "Record payment",
        `<p class="callout">${esc(bill.name)} · ${money(bill.balance)} outstanding</p>`, recordId);
      break;
    case "batch-mark": case "batch-snooze": case "batch-pay": batchDialog(name); break;
    case "add-schedule": scheduleDialog(); break;
    case "reschedule":
      formDialog("Change scheduled date", "schedule", field("New payment date", "day", "date", schedule.day,
        `required min="${state.today}"`), "Save date", "", recordId);
      break;
    case "cancel-schedule":
      plainDialog("Cancel schedule", "cancel", "Cancel schedule", "The scheduled payment will not be recorded.", recordId);
      break;
    case "clear-filters":
      delete filters[id]; selection.clear(); render(); break;
    case "category":
      filters.bills = {...filters.bills, category: filters.bills?.category === id ? "" : id};
      selection.clear(); render(); break;
    case "notifications": await load(true); render(); notificationsDialog(); break;
    case "preview-sms": {
      const note = state.notifications.find(n => n.id === recordId);
      if (!note || !note.delivery.startsWith("SMS preview")) return;
      dialog("SMS preview", `<p class="callout">Demo handset · ${dayText(note.day)}</p>
        <p class="sms-message">PayPilot: ${esc(note.message)}</p>
        <div class="form-actions">${button("Back to notifications", "notifications")}</div>`);
      break;
    }
    case "read-note": await api("notification", {id: recordId}); await load(); render(); notificationsDialog(); break;
    case "profile": profileDialog(); break;
    case "help":
      dialog("PayPilot help", `<p class="tall">Use Manage bills to add due dates, attachments and reminders. Use Schedule payments to set up a simulation. Track payments shows balances and history. Contact your PayPilot administrator for account assistance.</p>`);
      break;
    case "password":
      formDialog("Change password", "password",
        field("Current password", "current", "password", "", 'required autocomplete="current-password"') +
        field("New password", "password", "password", "", 'required minlength="10" maxlength="128" autocomplete="new-password"') +
        field("Confirm new password", "confirm", "password", "", 'required minlength="10" maxlength="128" autocomplete="new-password"'),
        "Update password");
      break;
    case "pin":
      formDialog("Access PIN", "pin", field("Current password", "current", "password", "", 'required autocomplete="current-password"') +
        field("PIN (6–8 digits)", "pin", "password", "", 'pattern="[0-9]{6,8}" inputmode="numeric" autocomplete="off"'),
      "Save PIN", '<p class="callout">An access PIN adds a step after the email verification code. Leave the PIN field blank to remove it.</p>');
      break;
    case "approve": case "decline":
      plainDialog(name === "approve" ? "Approve request" : "Decline request",
        name, name === "approve" ? "Approve" : "Decline", "Confirm your decision for this access request.", recordId);
      break;
    case "logout":
      await api("logout", {});
      modal.close(); state = null; selection.clear();
      for (const page of Object.keys(filters)) delete filters[page];
      await authView("login");
      break;
  }
}

async function authSubmit(data) {
  const view = auth.view;
  if (view === "register") {
    const result = await api("register", data);
    await authView("login");
    tell(result.message);
  } else if (view === "login") {
    try {
      const result = await api("login", {...data, challenge: auth.challenge.token});
      auth = {view: "verify-login", ...result};
      renderAuth();
    } catch (error) {
      auth = {view, username: data.username, error: error.message};
      try { auth.challenge = await api("challenge", {}); } catch { /* Preserve the original error. */ }
      renderAuth();
    }
  } else if (view === "reset") {
    const result = await api("reset", data);
    auth = {view: "verify-reset", ...result};
    renderAuth();
  } else if (view === "verify-reset") {
    const result = await api(view, {...data, token: auth.token});
    await authView("login");
    tell(result.message);
  } else {
    await api(view, {...data, token: auth.token});
    await load(true);
    go("overview");
  }
}

const fileAsBase64 = file => new Promise((resolve, reject) => {
  const reader = new FileReader();
  reader.onerror = () => reject(new Error("Could not read the attachment."));
  reader.onload = () => resolve(reader.result.split(",")[1]);
  reader.readAsDataURL(file);
});

async function saveChange(form, data) {
  let endpoint = form.dataset.endpoint;
  const id = Number(form.dataset.id);
  if (id) data.id = id;
  if (endpoint === "bill") {
    if (!form.elements.recurring.checked) data.payment_frequency = "Once";
    delete data.recurring;
    data.remind_recurring = form.elements.remind_recurring.checked;
    data.remove_attachment = data.remove_attachment === "on";
    if (data.pdf?.size) {
      if (data.pdf.size > 3 * 1024 * 1024) throw new Error("Choose a PDF up to 3 MB.");
      data.attachment = await fileAsBase64(data.pdf);
      data.filename = data.pdf.name;
    }
    delete data.pdf;
  }
  if (endpoint === "schedule" && !id) {
    data.bill_id = Number(data.bill_id);
    delete data.pick_category;
    delete data.pick_from;
    delete data.pick_to;
  }
  if (["mark", "snooze", "pay", "record", "archive"].includes(endpoint)) data.ids = id ? [id] : [...selection];
  if (endpoint === "approve" || endpoint === "decline") {
    data.status = endpoint === "approve" ? "approved" : "denied";
    endpoint = "access";
  }
  form.dataset.requestId ??= crypto.randomUUID();
  const result = await api(endpoint, {...data, request_id: form.dataset.requestId});
  selection.clear();
  modal.close();
  await load(true);
  render();
  if (endpoint === "pay" && result.attempts?.length) {
    const declined = result.attempts[0].status === "Declined";
    dialog(declined ? "Test payment declined" : "Test payment settled",
      `<p class="callout">${declined ? "The bill balance is unchanged. Select the bill again to retry." : "The simulated payment was recorded in your payment history."}</p>
      ${attemptTable(result.attempts)}<div class="form-actions">
      <a class="button" href="#/track/attempts" data-action="close">View test transactions</a></div>`);
    return;
  }
  const messages = {bill: "Bill saved.", mark: "Bills marked paid.", snooze: "Reminders snoozed.",
    pay: "Simulated payment recorded.", record: "External payment recorded.", archive: "Bill deleted from active views.",
    schedule: id ? "Scheduled date updated." : "Simulated schedule confirmed.", cancel: "Schedule cancelled.",
    access: "Access request updated.", password: "Password updated.", pin: "Access PIN updated."};
  tell(messages[endpoint]);
}

document.addEventListener("click", async event => {
  const control = event.target.closest("[data-action]");
  if (!control || control.disabled) return;
  control.disabled = true;
  try { await action(control.dataset.action, control.dataset.id); }
  catch (error) { tell(error.message); }
  finally { control.disabled = false; }
});

document.addEventListener("change", event => {
  const input = event.target;
  if (input.dataset.select) {
    const id = Number(input.dataset.select);
    if (input.checked) selection.add(id);
    else selection.delete(id);
    document.querySelector("#selection-count").textContent = selection.size + " selected";
  }
  if (input.name === "bill_id" && modal.contains(input)) {
    const bill = state.bills.find(b => b.id === Number(input.value));
    if (bill) updateScheduledBill(bill);
  }
  if (input.name?.startsWith("pick_") && modal.contains(input)) {
    filterScheduleBills(input.form);
  }
  if (input.name === "recurring" && modal.contains(input)) {
    input.form.elements.payment_frequency.disabled = !input.checked;
  }
});

document.addEventListener("input", event => {
  if (event.target.name === "payee" && modal.contains(event.target)) updatePayee();
});

document.addEventListener("submit", async event => {
  const form = event.target;
  if (!form.dataset.form) return;
  event.preventDefault();
  const submit = event.submitter || form.querySelector('button[type="submit"]');
  const errorBox = form.querySelector(".form-error");
  const data = Object.fromEntries(new FormData(form));
  errorBox.textContent = "";
  submit.disabled = true;
  try {
    if (form.dataset.form === "auth") await authSubmit(data);
    else if (form.dataset.form === "change") await saveChange(form, data);
    else {
      if (data.from && data.to && data.from > data.to) throw new Error("The From date must be before or equal to the To date.");
      filters[form.dataset.page] = data;
      selection.clear();
      render();
    }
  } catch (error) {
    if (errorBox.isConnected) errorBox.textContent = error.message;
    else tell(error.message);
  } finally {
    submit.disabled = false;
  }
});

window.addEventListener("hashchange", async () => {
  selection.clear();
  try { await loadAdminView(); } catch (error) { tell(error.message); }
  if (state) render();
});

(async () => {
  try {
    config = await api("config");
    try {
      await load();
      await load(true);
      render();
    } catch (error) {
      if (error.status !== 401) throw error;
      state = null;
      await authView("login");
    }
  } catch (error) {
    app.textContent = "PayPilot could not start. " + error.message;
  } finally {
    app.removeAttribute("aria-busy");
  }
})();
