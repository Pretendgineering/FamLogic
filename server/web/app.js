// Thereabouts web dashboard. Plain JS, no build step.
"use strict";

const $ = (sel) => document.querySelector(sel);
const REFRESH_MS = 10000;
const STALE_MS = 30 * 60 * 1000; // a location older than this is shown faded

let map, refreshTimer, fittedOnce = false;
let circles = [];       // all circles on the server
let lastMembers = [];   // everyone, from the last refresh
let circleFilter = "";  // "" = everyone, else a circle id
try { circleFilter = localStorage.getItem("circleFilter") || ""; } catch (_) {}

const circleName = (id) => (circles.find((c) => c.id === id) || {}).name || "?";
const markers = new Map(); // member id -> { marker, circle }

async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
    credentials: "same-origin",
  });
  if (res.status === 401 && !path.endsWith("/login")) {
    showLogin();
    throw new Error("unauthorized");
  }
  if (!res.ok) {
    let msg = res.statusText;
    try { msg = (await res.json()).error || msg; } catch (_) {}
    throw new Error(msg);
  }
  return res.status === 204 ? null : res.json();
}

function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") e.className = v;
    else if (k === "style") e.style.cssText = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v);
  }
  for (const c of children) if (c != null) e.append(c);
  return e;
}

function initial(name) {
  return (name || "?").trim().charAt(0).toUpperCase();
}

function ago(ms) {
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 60) return "just now";
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} h ago`;
  return `${Math.round(h / 24)} d ago`;
}

// "@taylor · signed in 2 h ago", or a hint when they haven't set a password yet.
function account(m) {
  if (!m.hasPassword) return "No password yet (they can set one in the app)";
  return `@${m.username}` + (m.lastSignIn ? ` · signed in ${ago(m.lastSignIn)}` : "");
}

// A red banner across the top while anyone has an SOS active. Click to zoom to them.
function renderSOS(list) {
  const banner = $("#sos-banner");
  banner.hidden = list.length === 0;
  banner.replaceChildren(...list.map((m) =>
    el("button", { class: "sos-item", onclick: () => m.loc && map.setView([m.loc.lat, m.loc.lon], 17) },
      `🆘 SOS from ${m.name} since ${new Date(m.sosSince).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}` +
      (m.loc ? ` · last seen ${ago(m.loc.time)}, click to show` : " · no location yet"))));
}

function describe(m) {
  if (m.sosSince) return `SOS since ${new Date(m.sosSince).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}` + (m.loc ? ` · ${ago(m.loc.time)}` : "");
  if (m.paused) return "Sharing paused";
  if (!m.loc) return "No location yet";
  const parts = [ago(m.loc.time)];
  if (m.loc.battery >= 0) parts.push(`${m.loc.battery}%${m.loc.charging ? " ⚡" : ""}`);
  return parts.join(" · ");
}

// ---- login ----

function showLogin() {
  clearInterval(refreshTimer);
  $("#app").hidden = true;
  $("#login").hidden = false;
  $("#password").focus();
}

$("#login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  $("#login-error").textContent = "";
  try {
    await api("POST", "/api/admin/login", { password: $("#password").value });
    $("#password").value = "";
    start();
  } catch (err) {
    $("#login-error").textContent = err.message;
  }
});

$("#logout-btn").addEventListener("click", async () => {
  await api("POST", "/api/admin/logout").catch(() => {});
  showLogin();
});

// ---- map ----

function ensureMap(server) {
  if (map) return;
  map = L.map("map", { zoomControl: true }).setView([20, 0], 2);
  // Tile servers (OpenStreetMap's in particular) require a Referer; send only our origin.
  L.tileLayer(server.tileUrl, {
    maxZoom: 19,
    attribution: server.tileAttribution,
    referrerPolicy: "strict-origin-when-cross-origin",
  }).addTo(map);
}

function renderMembers(members) {
  const list = $("#members");
  list.replaceChildren();
  $("#no-members").hidden = members.length > 0;
  const seen = new Set();
  const bounds = [];

  for (const m of members) {
    const stale = !m.loc || Date.now() - m.loc.time > STALE_MS;
    const avatar = el("div", { class: "avatar" + (stale ? " stale" : "") + (m.sosSince ? " sos" : ""), style: `background:${m.color}` }, initial(m.name));
    const li = el("li", { class: m.loc ? "clickable" : "" },
      avatar,
      el("div", { class: "grow" },
        el("div", { class: "title" }, m.name),
        el("div", { class: "sub" }, describe(m)),
        el("div", { class: "sub" }, account(m)),
        circles.length > 1 ? el("div", { class: "chips" }, ...(m.circles || []).map((id) =>
          el("span", {
            class: "chip" + ((m.hiddenFrom || []).includes(id) ? " hidden-from" : ""),
            title: (m.hiddenFrom || []).includes(id) ? `Not sharing their location with ${circleName(id)}` : "",
          }, circleName(id)))) : null,
        el("div", { class: "row-actions" },
          el("button", { class: "link", title: "Choose which circles they're in", onclick: (e) => { e.stopPropagation(); openCircles(m); } }, "Circles…"),
          el("button", { class: "link", title: "Rename", onclick: (e) => { e.stopPropagation(); rename(m); } }, "Rename"),
          el("button", { class: "link", title: "Let them choose a new password and sign in on a phone", onclick: (e) => { e.stopPropagation(); resetSignIn(m); } }, "Reset sign-in"),
          el("button", { class: "link danger", title: "Remove", onclick: (e) => { e.stopPropagation(); removeMember(m); } }, "Remove"))),
    );
    if (m.loc) li.addEventListener("click", () => map.setView([m.loc.lat, m.loc.lon], Math.max(map.getZoom(), 15)));
    list.append(li);

    if (!m.loc) continue;
    seen.add(m.id);
    const pos = [m.loc.lat, m.loc.lon];
    bounds.push(pos);
    const icon = L.divIcon({
      className: "pin",
      html: `<div class="avatar${stale ? " stale" : ""}${m.sosSince ? " sos" : ""}" style="background:${m.color}"></div>`,
      iconSize: [38, 38],
      iconAnchor: [19, 19],
    });
    let entry = markers.get(m.id);
    if (!entry) {
      entry = {
        marker: L.marker(pos, { icon }).addTo(map),
        circle: L.circle(pos, { radius: m.loc.acc || 0, color: m.color, weight: 1, fillOpacity: 0.12 }).addTo(map),
      };
      markers.set(m.id, entry);
    }
    entry.marker.setLatLng(pos).setIcon(icon);
    // Set the letter with textContent so names can never inject HTML.
    entry.marker.getElement()?.querySelector(".avatar")?.replaceChildren(initial(m.name));
    entry.marker.bindTooltip(`${m.name} · ${describe(m)}`);
    entry.circle.setLatLng(pos).setRadius(m.loc.acc || 0).setStyle({ color: m.color });
  }

  for (const [id, entry] of markers) {
    if (!seen.has(id)) {
      entry.marker.remove();
      entry.circle.remove();
      markers.delete(id);
    }
  }

  if (!fittedOnce && bounds.length) {
    map.fitBounds(bounds, { padding: [50, 50], maxZoom: 15 });
    fittedOnce = true;
  }
}

function renderInvites(invites) {
  const list = $("#invites");
  list.replaceChildren();
  $("#no-invites").hidden = invites.length > 0;
  for (const inv of invites) {
    list.append(el("li", {},
      el("div", { class: "grow" },
        el("div", { class: "title" }, inv.memberId ? `Reset sign-in for ${inv.name}` : (inv.name || "Anyone")),
        el("div", { class: "sub" }, `${inv.code} · ` + (!inv.memberId && circles.length > 1 ? `joins ${circleName(inv.circleId)} · ` : "") +
          `expires ${new Date(inv.expiresAt).toLocaleDateString()}`)),
      el("button", { class: "link", onclick: () => copy(inv.link) }, "Copy link"),
      el("button", { class: "link danger", onclick: () => cancelInvite(inv) }, "Cancel"),
    ));
  }
}

async function refresh() {
  try {
    const [data, invites] = await Promise.all([api("GET", "/api/admin/members"), api("GET", "/api/admin/invites")]);
    $("#family-name").textContent = data.server.name;
    const sos = data.members.filter((m) => m.sosSince);
    document.title = (sos.length ? "🆘 SOS · " : "") + `${data.server.name} · Thereabouts`;
    renderSOS(sos);
    ensureMap(data.server);
    circles = data.circles || [];
    lastMembers = data.members;
    renderCircles();
    renderMembers(visibleMembers());
    renderInvites(invites);
  } catch (err) {
    if (err.message !== "unauthorized") console.error(err);
  }
}

// ---- circles ----

function visibleMembers() {
  if (!circles.some((c) => c.id === circleFilter)) circleFilter = "";
  return circleFilter ? lastMembers.filter((m) => (m.circles || []).includes(circleFilter)) : lastMembers;
}

function renderCircles() {
  // The "Show" filter above the people list (only useful with 2+ circles).
  const sel = $("#circle-filter");
  sel.replaceChildren(el("option", { value: "" }, "Everyone"), ...circles.map((c) => el("option", { value: c.id }, c.name)));
  sel.value = circles.some((c) => c.id === circleFilter) ? circleFilter : "";
  $("#filter-label").hidden = circles.length < 2;

  const list = $("#circles");
  list.replaceChildren(...circles.map((c) => {
    const count = lastMembers.filter((m) => (m.circles || []).includes(c.id)).length;
    return el("li", {},
      el("div", { class: "grow" },
        el("div", { class: "title" }, c.name),
        el("div", { class: "sub" }, count === 1 ? "1 person" : `${count} people`)),
      el("button", { class: "link", onclick: () => renameCircle(c) }, "Rename"),
      circles.length > 1 ? el("button", { class: "link danger", onclick: () => deleteCircle(c) }, "Delete") : null);
  }));
}

$("#circle-filter").addEventListener("change", (e) => {
  circleFilter = e.target.value;
  try { localStorage.setItem("circleFilter", circleFilter); } catch (_) {}
  fittedOnce = false; // zoom to the people in this circle
  renderMembers(visibleMembers());
});

$("#new-circle-btn").addEventListener("click", async () => {
  const name = prompt("Name of the new circle (e.g. Grandparents, Friends):");
  if (!name) return;
  try { await api("POST", "/api/admin/circles", { name }); refresh(); }
  catch (err) { alert(err.message); }
});

async function renameCircle(c) {
  const name = prompt("New name for this circle:", c.name);
  if (!name || name === c.name) return;
  try { await api("PATCH", `/api/admin/circles/${encodeURIComponent(c.id)}`, { name }); refresh(); }
  catch (err) { alert(err.message); }
}

async function deleteCircle(c) {
  if (!confirm(`Delete the circle "${c.name}"? Its members stay on the server (and in their other circles), but no longer see each other through this circle. Its invites stop working.`)) return;
  try { await api("DELETE", `/api/admin/circles/${encodeURIComponent(c.id)}`); refresh(); }
  catch (err) { alert(err.message); }
}

// The "Circles…" dialog: tick the circles a person is in.
const circlesDialog = $("#circles-dialog");
circlesDialog.querySelectorAll("[data-close]").forEach((b) => b.addEventListener("click", () => circlesDialog.close()));
function openCircles(m) {
  $("#circles-dialog-title").textContent = `Circles for ${m.name}`;
  $("#circles-checks").replaceChildren(...circles.map((c) => {
    const box = el("input", { type: "checkbox" });
    box.checked = (m.circles || []).includes(c.id);
    box.addEventListener("change", async () => {
      try {
        await api(box.checked ? "PUT" : "DELETE", `/api/admin/circles/${encodeURIComponent(c.id)}/members/${encodeURIComponent(m.id)}`);
        refresh();
      } catch (err) {
        box.checked = !box.checked;
        alert(err.message);
      }
    });
    return el("label", {}, box, c.name);
  }));
  circlesDialog.showModal();
}


// ---- actions ----

async function rename(m) {
  const name = prompt(`New name for ${m.name}:`, m.name);
  if (!name || name === m.name) return;
  try { await api("PATCH", `/api/admin/members/${encodeURIComponent(m.id)}`, { name }); refresh(); }
  catch (err) { alert(err.message); }
}

async function resetSignIn(m) {
  if (!confirm(`Make a one-time link that lets ${m.name} choose a new password and sign in on a phone? Any older reset link for them stops working.`)) return;
  try {
    const inv = await api("POST", `/api/admin/members/${encodeURIComponent(m.id)}/reset`);
    showLink(inv, `Sign-in reset for ${m.name}`,
      `Send this link to ${m.name}. It lets them choose a new password and sign in on their phone, keeping their place in the family. It works once and expires in a few days.`);
    refresh();
  } catch (err) { alert(err.message); }
}

async function removeMember(m) {
  if (!confirm(`Remove ${m.name}? Their phone will stop sharing and need a new invite to rejoin.`)) return;
  try { await api("DELETE", `/api/admin/members/${encodeURIComponent(m.id)}`); refresh(); }
  catch (err) { alert(err.message); }
}

async function cancelInvite(inv) {
  try { await api("DELETE", `/api/admin/invites/${encodeURIComponent(inv.code)}`); refresh(); }
  catch (err) { alert(err.message); }
}

async function copy(text) {
  try { await navigator.clipboard.writeText(text); }
  catch (_) { prompt("Copy this link:", text); }
}

const dialog = $("#invite-dialog");
$("#invite-btn").addEventListener("click", () => {
  $("#invite-form").hidden = false;
  $("#invite-result").hidden = true;
  $("#invite-name").value = "";
  const sel = $("#invite-circle");
  sel.replaceChildren(...circles.map((c) => el("option", { value: c.id }, c.name)));
  if (circleFilter) sel.value = circleFilter;
  $("#invite-circle-label").hidden = circles.length < 2;
  dialog.showModal();
});
dialog.querySelectorAll("[data-close]").forEach((b) => b.addEventListener("click", () => dialog.close()));
$("#invite-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  try {
    const inv = await api("POST", "/api/admin/invites", { name: $("#invite-name").value, circleId: $("#invite-circle").value });
    showLink(inv, "Invite ready", "Send this link to their phone. It works once and expires in a few days.");
    refresh();
  } catch (err) {
    alert(err.message);
  }
});
// Shows a freshly made invite or reset link in the dialog.
function showLink(inv, title, text) {
  $("#result-title").textContent = title;
  $("#result-text").textContent = text;
  $("#invite-link").value = inv.link;
  $("#invite-code").textContent = inv.code;
  $("#invite-server").textContent = new URL(inv.link).origin;
  // QR code of the link, so a phone can scan it from the screen.
  try {
    const qr = qrcode(0, "M");
    qr.addData(inv.link);
    qr.make();
    $("#invite-qr").src = qr.createDataURL(6, 2);
    $("#invite-qr").parentElement.hidden = false;
  } catch (_) {
    $("#invite-qr").parentElement.hidden = true;
  }
  $("#invite-form").hidden = true;
  $("#invite-result").hidden = false;
  if (!dialog.open) dialog.showModal();
}

$("#copy-link").addEventListener("click", async () => {
  await copy($("#invite-link").value);
  $("#copy-link").textContent = "Copied";
  setTimeout(() => ($("#copy-link").textContent = "Copy"), 1500);
});

// ---- start ----

async function start() {
  $("#login").hidden = true;
  $("#app").hidden = false;
  await refresh();
  if (map) map.invalidateSize();
  clearInterval(refreshTimer);
  refreshTimer = setInterval(() => { if (!document.hidden) refresh(); }, REFRESH_MS);
}

api("GET", "/api/admin/members").then(start).catch(() => {});
