// FamLogic web dashboard. Plain JS, no build step.
"use strict";

const $ = (sel) => document.querySelector(sel);

// ---- appearance: this browser's palette and light/dark (the same palettes as the app) ----
const THEMES = [["dracula", "Default"], ["paper", "Warm paper"], ["forest", "Forest"], ["ocean", "Ocean"], ["contrast", "High contrast"]];
// The modules' own names (Thereabouts, Chitchat…) or plain ones (Map, Chat…), per browser.
let plainNames = false;
try { plainNames = localStorage.getItem("plainNames") === "1"; } catch (_) {}
const darkQuery = matchMedia("(prefers-color-scheme: dark)");
let themeId = "dracula", themeMode = "system";
try { themeId = localStorage.getItem("theme") || "dracula"; themeMode = localStorage.getItem("themeMode") || "system"; } catch (_) {}
const isDark = () => themeMode === "dark" || (themeMode === "system" && darkQuery.matches);
function applyAppearance(early = false) {
  document.documentElement.dataset.theme = THEMES.some(([id]) => id === themeId) ? themeId : "dracula";
  document.documentElement.dataset.dark = isDark() ? "1" : "0";
  if (!early) { applyTiles(); if (map) renderPlaces(); } // the map's dimming and fence colours follow
}
applyAppearance(true); // before anything shows, so the login page is already in the right colours
darkQuery.addEventListener("change", () => applyAppearance());
const REFRESH_MS = 10000;
const STALE_MS = 30 * 60 * 1000; // a location older than this is shown faded

let map, refreshTimer, fittedOnce = false;
let circles = [];       // all circles on the server
let lastMembers = [];   // everyone, from the last refresh
let places = [];        // all places
const placeLayers = new Map(); // place id -> Leaflet layer group
let trailLayer = null;  // the clicked person's trail, if they keep one
let serverFeatures = []; // what the server can do, e.g. "geocode"
// Who's using the console: the main admin (admin password), or a person (admin or parent).
let me = { owner: false, admin: true, member: null };
const isAdmin = () => !!me.admin;
const memberBase = () => isAdmin() ? "/api/admin/members" : "/api/members";
// The theme's accent colour (fences on the map use it).
const accentColor = () => getComputedStyle(document.documentElement).getPropertyValue("--accent").trim() || "#8b5cf6";
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

// ---- the ⋯ menu on each row ----

const menu = $("#menu");
let menuAnchor = null;
function closeMenu() {
  menu.hidden = true;
  menuAnchor?.setAttribute("aria-expanded", "false");
  menuAnchor = null;
}
// A ⋯ button that opens a small menu. items: [label, onClick, "danger"?] or "-" for a divider.
function moreButton(label, items) {
  const b = el("button", { class: "more", title: label, "aria-label": label, "aria-haspopup": "menu", "aria-expanded": "false" }, "⋯");
  b.addEventListener("click", (e) => {
    e.stopPropagation();
    if (menuAnchor === b) return closeMenu();
    closeMenu();
    menu.replaceChildren(...items.map((it) => it === "-" ? el("hr") :
      el("button", { role: "menuitem", class: it[2] || "", onclick: () => { closeMenu(); it[1](); } }, it[0])));
    menu.hidden = false;
    const r = b.getBoundingClientRect();
    const w = menu.offsetWidth, h = menu.offsetHeight;
    menu.style.left = Math.max(8, Math.min(r.right - w, innerWidth - w - 8)) + "px";
    menu.style.top = (r.bottom + h + 8 > innerHeight ? Math.max(8, r.top - h - 4) : r.bottom + 4) + "px";
    menuAnchor = b;
    b.setAttribute("aria-expanded", "true");
  });
  return b;
}
document.addEventListener("click", (e) => { if (!menu.hidden && !menu.contains(e.target)) closeMenu(); });
document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeMenu(); });
window.addEventListener("resize", closeMenu);
document.querySelector("aside").addEventListener("scroll", closeMenu);

// ---- tabs: People, Fences, Groups ----

let tab = "people";
try { tab = localStorage.getItem("tab") || "people"; } catch (_) {}
function showTab(name) {
  if (!document.querySelector(`[data-panel="${name}"]`)) name = "people";
  tab = name;
  try { localStorage.setItem("tab", name); } catch (_) {}
  document.querySelectorAll(".tabs [data-tab]").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.tab === name)));
  document.querySelectorAll("[data-panel]").forEach((p) => { p.hidden = p.dataset.panel !== name; });
  closeMenu();
}
document.querySelectorAll(".tabs [data-tab]").forEach((b) => b.addEventListener("click", () => showTab(b.dataset.tab)));
showTab(tab);

function setCount(id, n, alert = false) {
  const c = $(id);
  c.textContent = n ? String(n) : "";
  c.classList.toggle("alert", alert);
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

const ISSUES = {
  location: "No location permission",
  background: "Location not 'Allow all the time'",
  battery: "Battery restrictions on",
  notifications: "Notifications off",
  location_off: "Location off on the phone",
};

// "App 0.9.0 · in touch 2 min ago", plus warnings about anything that could stop sharing.
function health(m) {
  const line = [m.app ? `App ${m.app}` : null, m.lastContact ? `in touch ${ago(m.lastContact)}` : null].filter(Boolean).join(" · ");
  const warns = (m.issues || []).map((i) => ISSUES[i] || i);
  if (m.silent) warns.unshift(`Quiet since ${ago(m.lastContact)}`);
  return el("div", {},
    line ? el("div", { class: "sub" }, line) : null,
    warns.length ? el("div", { class: "health" }, ...warns.map((w) => el("span", { class: "warn" }, w))) : null);
}

// "@taylor · signed in 2 h ago", or a hint when they haven't set a password yet.
function account(m) {
  if (!m.hasPassword) return "No password yet (they can set one in the app)";
  return `@${m.username}` + (m.lastSignIn ? ` · signed in ${ago(m.lastSignIn)}` : "");
}

// A red card at the top of the sidebar while anyone has an SOS active. Click to zoom to them.
function renderSOS(list) {
  const banner = $("#sos-banner");
  banner.hidden = list.length === 0;
  banner.replaceChildren(...list.map((m) =>
    el("button", { class: "sos-item", onclick: () => m.loc && map.setView([m.loc.lat, m.loc.lon], 17) },
      `🆘 SOS from ${m.name} since ${new Date(m.sosSince).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}` +
      (m.sosSeenBy && m.sosSeenBy.length ? ` · seen by ${m.sosSeenBy.join(", ")}` : "") +
      (m.loc ? ` · last seen ${ago(m.loc.time)}, click to show` : " · no location yet"))));
}

// Driving, as of a recent fix (an old "driving" fix means the phone went quiet mid-drive).
const isDriving = (m) => !!(m.loc && m.loc.driving && !m.paused && Date.now() - m.loc.time < 10 * 60 * 1000);

function describe(m) {
  if (isDriving(m) && !m.sosSince) {
    return `Driving · ${ago(m.loc.time)}` + (m.loc.battery >= 0 ? ` · ${m.loc.battery}%${m.loc.charging ? " ⚡" : ""}` : "");
  }
  if (m.place && !m.sosSince && !m.paused && m.loc) {
    return `At ${m.place} · ${ago(m.loc.time)}` + (m.loc.battery >= 0 ? ` · ${m.loc.battery}%${m.loc.charging ? " ⚡" : ""}` : "");
  }
  if (m.sosSince) return `SOS since ${new Date(m.sosSince).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}` + (m.loc ? ` · ${ago(m.loc.time)}` : "");
  if (m.paused) return "Sharing paused";
  if (!m.loc) return "No location yet";
  const parts = [ago(m.loc.time)];
  if (m.loc.battery >= 0) parts.push(`${m.loc.battery}%${m.loc.charging ? " ⚡" : m.loc.saving ? " (saving battery)" : ""}`);
  if (m.loc.watch > 0) parts.push(`watch ${m.loc.watch}%`);
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
    await api("POST", "/api/admin/login", { username: $("#username").value.trim(), password: $("#password").value });
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

// Map styles anyone can pick (remembered in this browser). "Server default" is the admin's choice.
// All free, keyless tiles from OpenStreetMap data. In dark mode a light map is dimmed a little.
const MAP_STYLES = [
  { id: "server", title: "Server default" },
  { id: "osm", title: "OpenStreetMap", light: "https://tile.openstreetmap.org/{z}/{x}/{y}.png", attribution: "&copy; OpenStreetMap contributors" },
  { id: "hot", title: "Humanitarian", light: "https://{s}.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png",
    attribution: "&copy; OpenStreetMap contributors &middot; tiles by HOT, hosted by OSM France" },
  { id: "topo", title: "Topographic", light: "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png", maxZoom: 17,
    attribution: "&copy; OpenStreetMap contributors, SRTM &middot; &copy; OpenTopoMap (CC-BY-SA)" },
];
let mapStyle = "server";
let dimMap = true;
try { dimMap = localStorage.getItem("dimMap") !== "0"; } catch (_) {}
try { mapStyle = localStorage.getItem("mapStyle") || "server"; } catch (_) {}
let serverTiles = null, tileLayer = null, appliedTiles = "";
function applyTiles() {
  if (!map || !serverTiles) return;
  const st = MAP_STYLES.find((s) => s.id === mapStyle) || MAP_STYLES[0];
  const dark = isDark();
  const serverDark = serverTiles.tileUrlDark && serverTiles.tileUrlDark !== serverTiles.tileUrl ? serverTiles.tileUrlDark : "";
  const t = st.id === "server"
    ? { url: dark && serverDark ? serverDark : serverTiles.tileUrl, attribution: serverTiles.tileAttribution, maxZoom: 19, dim: dark && !serverDark }
    : { url: st.light, attribution: st.attribution, maxZoom: st.maxZoom || 19, dim: dark };
  document.body.classList.toggle("dim-map", t.dim && dimMap);
  if (t.url === appliedTiles) return;
  appliedTiles = t.url;
  tileLayer?.remove();
  // Tile servers (OpenStreetMap's in particular) require a Referer; send only our origin.
  tileLayer = L.tileLayer(t.url, { maxZoom: t.maxZoom, attribution: t.attribution, referrerPolicy: "strict-origin-when-cross-origin" }).addTo(map);
  map.setMaxZoom(t.maxZoom);
}
$("#map-style").replaceChildren(...MAP_STYLES.map((s) => el("option", { value: s.id }, s.title)));
$("#map-style").value = MAP_STYLES.some((s) => s.id === mapStyle) ? mapStyle : "server";
$("#dim-map").checked = dimMap;
$("#dim-map").addEventListener("change", (e) => {
  dimMap = e.target.checked;
  try { localStorage.setItem("dimMap", dimMap ? "1" : "0"); } catch (_) {}
  appliedTiles = "";
  applyTiles();
});
$("#map-style").addEventListener("change", (e) => {
  mapStyle = e.target.value;
  try { localStorage.setItem("mapStyle", mapStyle); } catch (_) {}
  applyTiles();
});

function ensureMap(server) {
  serverTiles = server;
  if (map) { applyTiles(); return; }
  map = L.map("map", { zoomControl: true }).setView([20, 0], 2);
  // Fence names only when zoomed in enough to read them without piling up.
  const labelsByZoom = (z) => document.body.classList.toggle("zoomed-out", (typeof z === "number" ? z : map.getZoom()) < 14);
  // zoomanim says where an animated zoom is heading, so the switch happens as it starts, not after.
  map.on("zoomanim", (e) => labelsByZoom(e.zoom));
  map.on("zoom zoomend", () => labelsByZoom());
  labelsByZoom();
  applyTiles();
}

function renderMembers(members) {
  const list = $("#members");
  list.replaceChildren();
  const none = $("#no-members");
  none.hidden = members.length > 0;
  if (circleFilter) none.textContent = `Nobody is in ${circleName(circleFilter)} yet. Use Groups… on a person to add them, or invite someone into it.`;
  else none.innerHTML = "Nobody has joined yet. Use <strong>Invite someone</strong> to add the first phone.";
  const seen = new Set();
  const bounds = [];

  for (const m of members) {
    const stale = !m.loc || Date.now() - m.loc.time > STALE_MS;
    const avatar = el("div", { class: "avatar" + (stale ? " stale" : "") + (m.sosSince ? " sos" : "") + (m.emoji ? " emoji" : "") + (isDriving(m) ? " driving" : ""), style: `background:${m.color}` }, m.emoji || initial(m.name));
    const li = el("li", { class: m.loc ? "clickable" : "" },
      avatar,
      el("div", { class: "grow" },
        el("div", { class: "title" }, m.name),
        el("div", { class: "sub" }, describe(m)),
        isAdmin() ? el("div", { class: "sub" }, account(m)) : null,
        isAdmin() ? health(m) : null,
        circles.length > 1 ? el("div", { class: "chips" }, ...(m.circles || []).map((id) =>
          el("span", {
            class: "chip" + ((m.hiddenFrom || []).includes(id) ? " hidden-from" : ""),
            title: (m.hiddenFrom || []).includes(id) ? `Not sharing their location with ${circleName(id)}` : "",
          }, circleName(id)))) : null,
      ),
      moreButton(`Options for ${m.name}`, !isAdmin() ? [
        ...(m.loc ? [["Show on map", () => { map.setView([m.loc.lat, m.loc.lon], Math.max(map.getZoom(), 15)); showTrail(m); }]] : []),
        ...(m.trips ? [["Trips…", () => openTrips(m)]] : []),
      ] : [
        ...(m.loc ? [["Show on map", () => { map.setView([m.loc.lat, m.loc.lon], Math.max(map.getZoom(), 15)); showTrail(m); }], "-"] : []),
        ["Groups…", () => openCircles(m)],
        ["Rename…", () => rename(m)],
        ["Icon and colour…", () => openIconPicker(m)],
        ...(m.trips ? [["Trips…", () => openTrips(m)]] : []),
        ["Reset sign-in…", () => resetSignIn(m)],
        "-",
        ["Remove from server…", () => removeMember(m), "danger"],
      ]),
    );
    if (m.loc) li.addEventListener("click", () => {
      map.setView([m.loc.lat, m.loc.lon], Math.max(map.getZoom(), 15));
      showTrail(m);
    });
    list.append(li);

    if (!m.loc) continue;
    seen.add(m.id);
    const pos = [m.loc.lat, m.loc.lon];
    bounds.push(pos);
    const icon = L.divIcon({
      className: "pin",
      html: `<div class="avatar${stale ? " stale" : ""}${m.sosSince ? " sos" : ""}${m.emoji ? " emoji" : ""}${isDriving(m) ? " driving" : ""}" style="background:${m.color}"></div>`,
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
    entry.marker.getElement()?.querySelector(".avatar")?.replaceChildren(m.emoji || initial(m.name));
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
  $("#invites-block").hidden = invites.length === 0;
  for (const inv of invites) {
    list.append(el("li", {},
      el("div", { class: "grow" },
        el("div", { class: "title" }, inv.memberId ? `Reset sign-in for ${inv.name}` : (inv.name || "Anyone")),
        el("div", { class: "sub" }, `${inv.code} · ` + (!inv.memberId && circles.length > 1 ? `joins ${circleName(inv.circleId)} · ` : "") +
          `expires ${new Date(inv.expiresAt).toLocaleDateString()}`)),
      moreButton("Invite options", [
        ["Show link and QR code", () => showLink(inv, inv.memberId ? `Sign-in reset for ${inv.name}` : "Invite",
          "Send this link to their phone. It works once and expires in a few days.")],
        ["Copy link", () => copy(inv.link)],
        "-",
        ["Cancel invite", () => cancelInvite(inv), "danger"],
      ]),
    ));
  }
}

async function refresh() {
  try {
    const [data, invites] = isAdmin()
      ? await Promise.all([api("GET", "/api/admin/members"), api("GET", "/api/admin/invites")])
      : [await api("GET", "/api/members?since=9007199254740991"), []];
    $("#family-name").textContent = data.server.name;
    $("#server-version").textContent = `server ${data.server.version}`;
    serverFeatures = data.server.features || [];
    const sos = data.members.filter((m) => m.sosSince);
    document.title = (sos.length ? "🆘 SOS · " : "") + `${data.server.name} · FamLogic`;
    renderSOS(sos);
    ensureMap(data.server);
    circles = data.circles || [];
    lastMembers = data.members;
    places = data.places || [];
    renderCircles();
    renderPlaces();
    renderMembers(visibleMembers());
    renderInvites(invites);
    setCount("#count-people", lastMembers.length, sos.length > 0);
    setCount("#count-fences", places.length);
    setCount("#count-groups", circles.length > 1 ? circles.length : 0);
    if ((moduleInfo("chat") || {}).enabled && (moduleInfo("chat") || {}).available && view !== "chat") await loadChats();
    if (view && view !== "thereabouts") renderView();
  } catch (err) {
    if (err.message !== "unauthorized") console.error(err);
  }
}

// ---- circles ----

function visibleMembers() {
  if (!circles.some((c) => c.id === circleFilter)) circleFilter = "";
  const users = lastMembers.filter((m) => m.role !== "dependent"); // non-users aren't on the map
  return circleFilter ? users.filter((m) => (m.circles || []).includes(circleFilter)) : users;
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
      !isAdmin() ? null : moreButton(`Options for ${c.name}`, [
        ["Show only this group on the map", () => setFilter(c.id)],
        ["Rename…", () => renameCircle(c)],
        ...(circles.length > 1 ? ["-", ["Delete group…", () => deleteCircle(c), "danger"]] : []),
      ]));
  }));
}

$("#circle-filter").addEventListener("change", (e) => setFilter(e.target.value));
function setFilter(id) {
  circleFilter = id;
  $("#circle-filter").value = id;
  try { localStorage.setItem("circleFilter", circleFilter); } catch (_) {}
  fittedOnce = false; // zoom to the people in this circle
  renderMembers(visibleMembers());
  renderPlaces();
}

$("#new-circle-btn").addEventListener("click", async () => {
  const name = prompt("Name of the new group (e.g. Grandparents, Friends):");
  if (!name) return;
  try { await api("POST", "/api/admin/circles", { name }); refresh(); }
  catch (err) { alert(err.message); }
});

async function renameCircle(c) {
  const name = prompt("New name for this group:", c.name);
  if (!name || name === c.name) return;
  try { await api("PATCH", `/api/admin/circles/${encodeURIComponent(c.id)}`, { name }); refresh(); }
  catch (err) { alert(err.message); }
}

async function deleteCircle(c) {
  if (!confirm(`Delete the group "${c.name}"? Its members stay on the server (and in their other groups), but no longer see each other through this group. Its invites and fences go too.`)) return;
  try { await api("DELETE", `/api/admin/circles/${encodeURIComponent(c.id)}`); refresh(); }
  catch (err) { alert(err.message); }
}

// The "Groups…" dialog: tick the circles a person is in.
const circlesDialog = $("#circles-dialog");
circlesDialog.querySelectorAll("[data-close]").forEach((b) => b.addEventListener("click", () => circlesDialog.close()));
function openCircles(m) {
  $("#circles-dialog-title").textContent = `Groups for ${m.name}`;
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


// ---- trails ----

// Draws the person's trail (if they keep one) as a line in their colour.
async function showTrail(m) {
  trailLayer?.remove();
  trailLayer = null;
  if (!m.trail) return;
  try {
    const { points } = await api("GET", `${memberBase()}/${encodeURIComponent(m.id)}/trail`);
    if (points.length < 2) return;
    trailLayer = L.polyline(points.map((p) => [p.lat, p.lon]), { color: m.color, weight: 4, opacity: 0.8 }).addTo(map);
    trailLayer.bindTooltip(`${m.name}'s trail since ${new Date(points[0].time).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}`);
  } catch (err) {
    console.error(err);
  }
}

// ---- places ----

function renderPlaces() {
  const shown = circleFilter ? places.filter((p) => p.circleId === circleFilter) : places;
  $("#places").replaceChildren(...shown.map((p) => el("li", { class: "clickable", onclick: () => map.setView([p.lat, p.lon], 16) },
    el("div", { class: "grow" },
      el("div", { class: "title" }, p.name),
      el("div", { class: "sub" }, `${Math.round(p.radius)} m` + (circles.length > 1 ? ` · ${circleName(p.circleId)}` : "") +
        (p.schedule ? ` · ${scheduleSummary(p.schedule)}` : ""))),
    !isAdmin() ? null : moreButton(`Options for ${p.name}`, [
      ["Show on map", () => map.setView([p.lat, p.lon], 16)],
      ["Edit…", () => editPlace(p)],
      "-",
      ["Delete fence…", () => deletePlace(p), "danger"],
    ]))));
  $("#no-places").hidden = shown.length > 0;

  // On the map: a shaded circle with the name.
  if (!map) return;
  const ids = new Set(shown.map((p) => p.id));
  for (const [id, layer] of placeLayers) {
    if (!ids.has(id)) { layer.remove(); placeLayers.delete(id); }
  }
  for (const p of shown) {
    placeLayers.get(p.id)?.remove();
    const layer = L.layerGroup([
      L.circle([p.lat, p.lon], { radius: p.radius, color: accentColor(), weight: 2, fillOpacity: 0.1, interactive: false }),
      L.marker([p.lat, p.lon], {
        interactive: false,
        icon: L.divIcon({ className: "place-label", html: "<span></span>", iconSize: [0, 0] }),
      }),
    ]).addTo(map);
    // Set the name as text so it can never inject HTML.
    layer.eachLayer((l) => { if (l instanceof L.Marker) l.getElement()?.querySelector("span")?.replaceChildren(p.name); });
    placeLayers.set(p.id, layer);
  }
}

const DAY_NAMES = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
function scheduleSummary(sc) {
  const d = [...sc.days].sort().join(",");
  const days = d === "1,2,3,4,5" ? "Mon–Fri" : d === "0,6" ? "Weekends" : d === "0,1,2,3,4,5,6" ? "Every day"
    : [1, 2, 3, 4, 5, 6, 0].filter((x) => sc.days.includes(x)).map((x) => DAY_NAMES[x]).join(" ");
  return sc.from === sc.to ? `${days}, all day` : `${days} ${sc.from}–${sc.to}`;
}
// Day toggles in the fence dialog, Monday first.
$("#place-days").replaceChildren(...[1, 2, 3, 4, 5, 6, 0].map((d) => {
  const box = el("input", { type: "checkbox", value: String(d) });
  return el("label", {}, box, el("span", {}, DAY_NAMES[d]));
}));
$("#place-sched-on").addEventListener("change", (e) => { $("#place-sched").hidden = !e.target.checked; });
function setScheduleFields(sc) {
  $("#place-sched-on").checked = !!sc;
  $("#place-sched").hidden = !sc;
  const days = sc ? sc.days : [1, 2, 3, 4, 5];
  $("#place-days").querySelectorAll("input").forEach((b) => { b.checked = days.includes(Number(b.value)); });
  $("#place-from").value = sc ? sc.from : "07:00";
  $("#place-to").value = sc ? sc.to : "17:00";
}
function scheduleFromFields() {
  if (!$("#place-sched-on").checked) return null;
  const days = [...$("#place-days").querySelectorAll("input:checked")].map((b) => Number(b.value));
  return { days, from: $("#place-from").value, to: $("#place-to").value, tz: Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC" };
}

const placeDialog = $("#place-dialog");
placeDialog.querySelectorAll("[data-close]").forEach((b) => b.addEventListener("click", () => placeDialog.close()));
// The fence being added or edited: { id?, name, radius, circleId, lat?, lon?, where? }
let placeDraft = null;

function setWhere(text) {
  $("#place-where").textContent = text;
}

function openPlaceDialog(draft) {
  placeDraft = draft;
  const editing = !!draft.id;
  $("#place-dialog-title").textContent = editing ? `Edit ${draft.name}` : "Add a fence";
  $("#place-name").value = draft.name || "";
  $("#place-radius").value = Math.round(draft.radius || 150);
  const sel = $("#place-circle");
  sel.replaceChildren(...circles.map((c) => el("option", { value: c.id }, c.name)));
  sel.value = draft.circleId || circleFilter || (circles[0] && circles[0].id) || "";
  sel.disabled = editing; // a fence stays in its group
  $("#place-circle-label").hidden = circles.length < 2;
  setWhere(draft.where || (draft.lat != null ? (editing ? "Where it is now (search or pick to move it)" : "The spot you picked") : "Not chosen yet"));
  $("#place-search").value = "";
  $("#place-results").replaceChildren();
  $("#place-search-row").hidden = !serverFeatures.includes("geocode");
  $("#place-schedule-box").hidden = !serverFeatures.includes("schedules");
  if (!draft.keepSchedule) setScheduleFields(draft.schedule || null);
  if (!placeDialog.open) placeDialog.showModal();
  if (draft.lat == null && serverFeatures.includes("geocode")) $("#place-search").focus();
}

async function searchAddress() {
  const q = $("#place-search").value.trim();
  if (q.length < 3) return;
  const list = $("#place-results");
  list.replaceChildren(el("li", { class: "muted" }, "Searching…"));
  try {
    const results = await api("GET", `/api/admin/geocode?q=${encodeURIComponent(q)}`);
    if (!results.length) {
      list.replaceChildren(el("li", { class: "muted" }, "Nothing found. Try adding the town, or pick the spot on the map."));
      return;
    }
    list.replaceChildren(...results.map((r) => {
      const li = el("li", {}, r.name);
      li.addEventListener("click", () => {
        placeDraft.lat = r.lat;
        placeDraft.lon = r.lon;
        placeDraft.where = r.name;
        setWhere(r.name);
        list.querySelectorAll("li").forEach((x) => x.classList.remove("chosen"));
        li.classList.add("chosen");
        // Suggest a name from the first part of the address if none yet.
        if (!$("#place-name").value) $("#place-name").value = r.name.split(",")[0].slice(0, 40);
        map.setView([r.lat, r.lon], 16);
      });
      return li;
    }));
  } catch (err) {
    list.replaceChildren(el("li", { class: "muted" }, err.message));
  }
}
$("#place-search-btn").addEventListener("click", searchAddress);
$("#place-search").addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); searchAddress(); } });

$("#place-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  if (placeDraft.lat == null) {
    alert("Choose where the fence goes: search for an address or pick a spot on the map.");
    return;
  }
  const body = { name: $("#place-name").value, radius: Number($("#place-radius").value), lat: placeDraft.lat, lon: placeDraft.lon };
  if (serverFeatures.includes("schedules")) {
    body.schedule = scheduleFromFields();
    if (body.schedule && !body.schedule.days.length) {
      alert("Choose at least one day for the alert hours, or untick \"Only alert at certain times\".");
      return;
    }
  }
  try {
    if (placeDraft.id) {
      await api("PATCH", `/api/admin/places/${encodeURIComponent(placeDraft.id)}`, body);
    } else {
      await api("POST", "/api/admin/places", { ...body, circleId: $("#place-circle").value });
    }
    placeDialog.close();
    refresh();
  } catch (err) {
    alert(err.message);
  }
});

// "Pick a spot on the map": hide the dialog, click the map, come back with the spot.
function pickOnMap(then) {
  if (!map) return;
  document.body.classList.add("picking");
  const hint = el("div", { class: "pick-hint" }, "Click the map where the fence goes (Esc to cancel)");
  $("#map").append(hint);
  const done = () => {
    document.body.classList.remove("picking");
    hint.remove();
    map.off("click", onClick);
    document.removeEventListener("keydown", onKey);
  };
  const onClick = (ev) => { done(); then(ev.latlng.lat, ev.latlng.lng); };
  const onKey = (ev) => { if (ev.key === "Escape") { done(); then(null, null); } };
  map.once("click", onClick);
  document.addEventListener("keydown", onKey);
}

$("#place-pick").addEventListener("click", () => {
  // Keep what was typed so far.
  placeDraft.name = $("#place-name").value;
  placeDraft.radius = Number($("#place-radius").value);
  placeDraft.circleId = $("#place-circle").value;
  placeDraft.keepSchedule = true; // the schedule fields keep what was chosen
  placeDialog.close();
  pickOnMap((lat, lon) => {
    if (lat != null) {
      placeDraft.lat = lat;
      placeDraft.lon = lon;
      placeDraft.where = "The spot you picked";
    }
    openPlaceDialog(placeDraft);
  });
});

$("#new-place-btn").addEventListener("click", () => openPlaceDialog({}));

function editPlace(p) { openPlaceDialog({ ...p }); }

async function deletePlace(p) {
  if (!confirm(`Delete the fence "${p.name}"? Nobody will be told about arriving or leaving it any more.`)) return;
  try { await api("DELETE", `/api/admin/places/${encodeURIComponent(p.id)}`); refresh(); }
  catch (err) { alert(err.message); }
}


// ---- actions ----

async function rename(m) {
  const name = prompt(`New name for ${m.name}:`, m.name);
  if (!name || name === m.name) return;
  try { await api("PATCH", `/api/admin/members/${encodeURIComponent(m.id)}`, { name }); refresh(); }
  catch (err) { alert(err.message); }
}

// ---- trips ----

const IMPERIAL = /-(US|GB|LR|MM)$/i.test(navigator.language || "");
function fmtDistance(m) {
  if (IMPERIAL) { const mi = m / 1609.344; return mi < 0.1 ? `${Math.round(m * 3.28084 / 10) * 10} ft` : mi < 10 ? `${mi.toFixed(1)} mi` : `${Math.round(mi)} mi`; }
  return m < 1000 ? `${Math.round(m / 10) * 10} m` : m < 10000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m / 1000)} km`;
}
const fmtSpeed = (mps) => IMPERIAL ? `${Math.round(mps * 2.23694)} mph` : `${Math.round(mps * 3.6)} km/h`;
function fmtDuration(ms) { const min = Math.max(1, Math.round(ms / 60000)); return min < 60 ? `${min} min` : `${Math.floor(min / 60)} h${min % 60 ? ` ${min % 60} min` : ""}`; }
function tripTitle(t) {
  const a = t.from || (t.fromNear ? `near ${t.fromNear}` : "Start");
  const b = t.ongoing ? "on the way" : t.to || (t.toNear ? `near ${t.toNear}` : "a stop");
  return `${a} → ${b}`;
}
const hm = (ms) => new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
function dayOf(ms) {
  const d = new Date(ms), today = new Date();
  const y = new Date(); y.setDate(today.getDate() - 1);
  if (d.toDateString() === today.toDateString()) return "Today";
  if (d.toDateString() === y.toDateString()) return "Yesterday";
  return d.toLocaleDateString([], { weekday: "short", day: "numeric", month: "short" });
}

const tripsDialog = $("#trips-dialog");
tripsDialog.querySelectorAll("[data-close]").forEach((b) => b.addEventListener("click", () => tripsDialog.close()));
let tripLayer = null, tripPill = null;
function clearTrip() { tripLayer?.remove(); tripLayer = null; tripPill?.remove(); tripPill = null; }

async function openTrips(m, again = false) {
  $("#trips-title").textContent = `${m.name}'s trips`;
  const list = $("#trips-list");
  if (!again) {
    list.replaceChildren(el("li", { class: "muted" }, "Loading…"));
    tripsDialog.showModal();
  }
  try {
    const { trips } = await api("GET", `${memberBase()}/${encodeURIComponent(m.id)}/trips`);
    if (!trips.length) { list.replaceChildren(el("li", { class: "muted" }, "No trips yet.")); return; }
    list.replaceChildren(...trips.map((t) => el("li", { class: "clickable", onclick: () => { tripsDialog.close(); showTrip(m, t); } },
      el("div", { class: "grow" },
        el("div", { class: "title" }, tripTitle(t), t.ongoing ? el("span", { class: "chip" }, "Under way") : null),
        el("div", { class: "sub" }, `${dayOf(t.start)} ${hm(t.start)}${t.ongoing ? "" : "–" + hm(t.end)} · ${fmtDuration(t.end - t.start)} · ${fmtDistance(t.distance)}`),
        el("div", { class: "sub" }, `Average ${fmtSpeed(t.avgSpeed)} · top ${fmtSpeed(t.topSpeed)}`)))));
    // Street names are looked up on the server in the background: ask once more shortly.
    const unnamed = trips.some((t) => (!t.from && !t.fromNear) || (!t.ongoing && !t.to && !t.toNear));
    if (unnamed && !again) setTimeout(() => { if (tripsDialog.open) openTrips(m, true); }, 4000);
  } catch (err) {
    list.replaceChildren(el("li", { class: "muted" }, err.message));
  }
}

function showTrip(m, t) {
  clearTrip();
  trailLayer?.remove();
  const pts = t.points.map((p) => [p.lat, p.lon]);
  tripLayer = L.layerGroup([
    L.polyline(pts, { color: m.color, weight: 5, opacity: 0.85 }),
    L.circleMarker(pts[0], { radius: 6, color: m.color, fillColor: "#fff", fillOpacity: 1, weight: 3 }),
    L.circleMarker(pts[pts.length - 1], { radius: 6, color: "#fff", fillColor: m.color, fillOpacity: 1, weight: 3 }),
  ]).addTo(map);
  map.fitBounds(L.latLngBounds(pts), { padding: [50, 50], maxZoom: 17 });
  tripPill = el("div", { class: "pick-hint trip-pill" },
    `${m.name}: ${tripTitle(t)} · ${fmtDuration(t.end - t.start)} · ${fmtDistance(t.distance)}`,
    el("button", { type: "button", class: "link", onclick: clearTrip }, "Close"));
  $("#map").append(tripPill);
}

// ---- icon picker: an emoji (or the initial) on a colour ----

const ICON_COLORS = ["#e6194b", "#f58231", "#e0a100", "#3cb44b", "#469990", "#0fa3c2",
  "#4363d8", "#6e48d6", "#911eb4", "#f032e6", "#9a6324", "#607d8b"];
const ICON_EMOJI = [
  ["Faces", "😀 😎 🤓 🥳 😇 🤠 🙂 😺 👻 🤖 👽 🎃"],
  ["People", "👩 👨 🧑 👧 👦 👶 👵 👴 🧔 👸 🤴 🦸"],
  ["Animals", "🐶 🐱 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🦉 🦄 🐝 🦋 🐢 🐙 🐬 🐳 🦈 🦖"],
  ["Nature", "🌸 🌻 🌵 🌲 🍀 🍁 🌈 ⭐ 🌙 ☀️ ⚡ ❄️ 🔥 🌊"],
  ["Food", "🍎 🍓 🍉 🍕 🍔 🌮 🍩 🍪 🧁 ☕ 🍦 🥑"],
  ["Activities", "⚽ 🏀 🏈 ⚾ 🎾 🏐 🎮 🎸 🎨 📚 🎣 🏄 🚴 🧗 🏕️ 🎯"],
  ["Travel", "🚗 🚙 🏍️ 🚲 🛴 🚌 🚂 ✈️ 🚀 ⛵ 🏠 🏫"],
  ["Symbols", "❤️ 🧡 💛 💚 💙 💜 🖤 💎 👑 🎵 🍄 🌟"],
];
const iconDialog = $("#icon-dialog");
iconDialog.querySelectorAll("[data-close]").forEach((b) => b.addEventListener("click", () => iconDialog.close()));
let iconTarget = null, pickedEmoji = "", pickedColor = "";
function renderIconPreview() {
  const p = $("#icon-preview");
  p.style.background = pickedColor;
  p.className = "avatar big" + (pickedEmoji ? " emoji" : "");
  p.textContent = pickedEmoji || initial(iconTarget.name);
  document.querySelectorAll("#icon-colors button").forEach((b) => b.classList.toggle("chosen", b.dataset.color === pickedColor));
  document.querySelectorAll("#icon-emoji button").forEach((b) => b.classList.toggle("chosen", b.dataset.emoji === pickedEmoji));
}
function openIconPicker(m) {
  iconTarget = m;
  pickedEmoji = m.emoji || "";
  pickedColor = (m.color || "").toLowerCase();
  $("#icon-title").textContent = `Icon for ${m.name}`;
  $("#icon-colors").replaceChildren(...ICON_COLORS.map((c) =>
    el("button", { type: "button", class: "swatch", "data-color": c, style: `background:${c}`, "aria-label": `Colour ${c}`,
      onclick: () => { pickedColor = c; renderIconPreview(); } })));
  const cell = (e, label) => el("button", { type: "button", class: "emoji-cell", "data-emoji": e, onclick: () => { pickedEmoji = e; renderIconPreview(); } }, label);
  $("#icon-emoji").replaceChildren(
    el("div", { class: "emoji-group" }, el("h3", {}, "Icon"), el("div", { class: "emoji-grid" }, cell("", initial(m.name)))),
    ...ICON_EMOJI.map(([title, list]) => el("div", { class: "emoji-group" }, el("h3", {}, title),
      el("div", { class: "emoji-grid" }, ...list.split(" ").map((e) => cell(e, e))))));
  renderIconPreview();
  iconDialog.showModal();
}
$("#icon-save").addEventListener("click", async () => {
  try {
    await api("PATCH", `/api/admin/members/${encodeURIComponent(iconTarget.id)}`, { emoji: pickedEmoji, color: pickedColor });
    iconDialog.close();
    refresh();
  } catch (err) { alert(err.message); }
});

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
  $("#invite-access").value = "parent";
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
    const access = $("#invite-access").value;
    const inv = await api("POST", "/api/admin/invites", { name: $("#invite-name").value, circleId: $("#invite-circle").value, access });
    showLink(inv, "Invite ready", access === "younger_child"
      ? "Open this link on their phone or tablet (or scan the code). No password needed: it signs that device in as them. It works once and expires in a few days."
      : "Send this link to their phone. It works once and expires in a few days.");
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

// ---- sections: the rail, and the pages besides Thereabouts ----

// Simple line icons (inherit the text colour).
const ICONS = {
  home: '<path d="M3 11 12 4l9 7"/><path d="M5.5 9.5V20h4.5v-6h4v6h4.5V9.5"/>',
  pin: '<path d="M12 21s-7-6.3-7-11.3a7 7 0 0 1 14 0C19 14.7 12 21 12 21z"/><circle cx="12" cy="9.7" r="2.6"/>',
  chat: '<path d="M4 5.5h16v10.5H10l-6 4.5z"/>',
  calendar: '<rect x="3.5" y="5.5" width="17" height="15" rx="2.5"/><path d="M3.5 10h17M8 3.5v4M16 3.5v4"/>',
  check: '<rect x="4" y="4" width="16" height="16" rx="3.5"/><path d="m8 12.3 2.8 2.8L16.2 9"/>',
  star: '<path d="m12 3.5 2.6 5.6 6 .6-4.5 4.1 1.3 6-5.4-3.1-5.4 3.1 1.3-6L3.4 9.7l6-.6z"/>',
  meal: '<path d="M7 3.5v17M4.5 3.5v5a2.5 2.5 0 0 0 5 0v-5M17 20.5V3.5c-2.5 1.5-3.5 5-3.5 8.5h3.5"/>',
  people: '<circle cx="9" cy="8.5" r="3.2"/><path d="M3 20c0-3.6 2.7-6.2 6-6.2s6 2.6 6 6.2"/><circle cx="17" cy="9.5" r="2.5"/><path d="M15.8 14.3c2.9.1 5.2 2.3 5.2 5.7"/>',
  device: '<rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M11 18.5h2"/>',
  camera: '<path d="M4 8.5h3l1.6-2.5h6.8L17 8.5h3v11H4z"/><circle cx="12" cy="13.5" r="3.3"/>',
  key: '<circle cx="8" cy="15" r="4"/><path d="m10.8 12.2 8.7-8.7M16.5 6.5l2.5 2.5M14 9l2 2"/>',
  gear: '<circle cx="12" cy="12" r="3.2"/><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1"/>',
};
const icon = (name) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name] || ""}</svg>`;
document.querySelectorAll("[data-icon]").forEach((s) => { s.innerHTML = icon(s.dataset.icon); });

let modules = [];      // from /api/admin/modules: id, name, description, available, enabled
let modulesLoaded = false;
const MODULE_ICONS = { thereabouts: "pin", chat: "chat", calendar: "calendar", todos: "check", chores: "star", meals: "meal", display: "device", agents: "gear" };
const moduleInfo = (id) => modules.find((m) => m.id === id);
const moduleOn = (id) => !modulesLoaded || !!(moduleInfo(id) || {}).enabled;

// What's coming in each module, for its placeholder page.
const MODULE_PLANS = {
  chat: ["A family group chat, plus one-to-one chats.", "Photos, read receipts, and new messages as notifications through the same always-on connection SOS alerts use.", "Parents can see their children's chats; children are told this in the app.", "Children sign in with a join code from you: no password to forget."],
  calendar: ["A shared family calendar, with CalDAV so phone calendars and other apps can sync both ways.", "Events stored as Markdown in your family's notes folder, so AI agents and Obsidian can read and edit them."],
  todos: ["Quick to-dos and shared lists (shopping, packing) from the app, the web, a Pebble or the Index 01 ring.", "Stored as Markdown task lists (- [ ] Milk) in your family's notes folder."],
  chores: ["A chore chart: who does what, how often, ticked off on phones or the family display.", "Optional points or stars for children.", "Each chore is a Markdown note linked to the people who do it, so it shows up in Obsidian's graph."],
  meals: ["Weekly meal plans and recipes, with ingredients added to the shopping list.", "One Markdown note per week, easy for an AI agent to plan or adjust."],
};

let view = "";
function viewFromHash() { return location.hash.replace(/^#\/?/, "").split("/")[0]; }

function showView(name) {
  if (!name) { try { name = localStorage.getItem("view") || ""; } catch (_) {} }
  if (!name) name = "home";
  if (!isAdmin() && (name === "devices" || name === "settings")) name = "home";
  const known = ["home", "family", "access", "devices", "settings"].includes(name) || !!moduleInfo(name) || name === "thereabouts";
  if (!known || ((moduleInfo(name) || name === "thereabouts") && !moduleOn(name))) name = "home";
  view = name;
  try { localStorage.setItem("view", name); } catch (_) {}
  if (viewFromHash() !== name) history.replaceState(null, "", "#/" + name);
  const built = (moduleInfo(name) || {}).available;
  const pageName = name === "chat" && built ? "chat" : (moduleInfo(name) && name !== "thereabouts" ? "module" : name);
  document.querySelectorAll("[data-page]").forEach((p) => { p.hidden = p.dataset.page !== pageName; });
  document.querySelectorAll(".rail a[data-view]").forEach((a) => {
    if (a.dataset.view === name) a.setAttribute("aria-current", "page");
    else a.removeAttribute("aria-current");
  });
  closeMenu();
  if (name === "thereabouts" && map) setTimeout(() => map.invalidateSize(), 0);
  renderView();
}
window.addEventListener("hashchange", () => showView(viewFromHash()));

function renderRail() {
  document.querySelectorAll(".rail a[data-module]").forEach((a) => {
    const m = moduleInfo(a.dataset.module);
    a.hidden = !!m && !m.enabled;
    if (m) a.querySelector("span:last-child").textContent = displayName(m);
    const soon = !!m && m.enabled && !m.available;
    a.classList.toggle("soon", soon);
    a.title = soon ? `${m.name}: coming soon` : "";
  });
}

function renderView() {
  if (view === "home") renderHome();
  else if (view === "family") renderFamily();
  else if (view === "access") renderAccess();
  else if (view === "devices") renderDevices();
  else if (view === "settings") renderSettings();
  else if (view === "chat" && (moduleInfo("chat") || {}).available) renderChat();
  else if (moduleInfo(view) && view !== "thereabouts") renderModulePage(view);
}

function card(href, iconName, title, text, cls = "") {
  const a = el("a", { class: "card-link " + cls, href });
  a.innerHTML = `<span class="ri">${icon(iconName)}</span>`;
  a.append(el("h3", {}, title), el("p", {}, text));
  return a;
}

function renderHome() {
  $("#home-title").textContent = $("#family-name").textContent || "FamLogic";
  const parents = lastMembers.filter((m) => m.role !== "child").length;
  const kids = lastMembers.length - parents;
  $("#home-sub").textContent = lastMembers.length ? `${lastMembers.length} ${lastMembers.length === 1 ? "person" : "people"}` + (kids ? ` · ${parents} ${parents === 1 ? "parent" : "parents"}, ${kids} ${kids === 1 ? "child" : "children"}` : "") : "Nobody has joined yet: invite your family from the Family page.";
  const cards = [];
  if (moduleOn("thereabouts")) {
    const sos = lastMembers.filter((m) => m.sosSince);
    const atFence = lastMembers.filter((m) => m.place && !m.paused).length;
    const driving = lastMembers.filter(isDriving).length;
    cards.push(sos.length
      ? card("#/thereabouts", "pin", "SOS", `${sos.map((m) => m.name).join(", ")} ${sos.length === 1 ? "needs" : "need"} help. Open the map.`, "sos")
      : card("#/thereabouts", "pin", displayName(moduleInfo("thereabouts") || { name: "Thereabouts", plain: "Map" }), [atFence ? `${atFence} at a fence` : null, driving ? `${driving} driving` : null, `${places.length} ${places.length === 1 ? "fence" : "fences"}`].filter(Boolean).join(" · ") || "Who's where, on the map"));
  }
  for (const m of modules) {
    if (m.id === "thereabouts" || !m.enabled || !MODULE_ICONS[m.id] || !document.querySelector(`.rail a[data-view="${m.id}"]`)) continue;
    const unread = m.id === "chat" ? chatUnread() : 0;
    cards.push(card("#/" + m.id, MODULE_ICONS[m.id], displayName(m) + (unread ? ` · ${unread} new` : ""), m.available ? m.description : "Coming soon. " + m.description));
  }
  const quiet = lastMembers.filter((m) => m.silent || (m.issues || []).length).length;
  cards.push(card("#/family", "people", "Family", "People, parents and children, invites."));
  cards.push(card("#/access", "key", "Access & Control", "What each person can use, their home screen, and the parent PIN."));
  if (isAdmin()) {
    cards.push(card("#/devices", "device", "Devices", quiet ? `${quiet} ${quiet === 1 ? "phone needs" : "phones need"} attention` : "All phones look fine"));
    cards.push(card("#/settings", "gear", "Settings", "Modules, backups, AI and API access."));
  }
  $("#home-cards").replaceChildren(...cards);
}

function avatarOf(m) {
  return el("div", { class: "avatar" + (m.emoji ? " emoji" : ""), style: `background:${m.color}` }, m.emoji || initial(m.name));
}

const ACCESS_LABELS = { admin: "Admin", parent: "Parent", grandparent: "Grandparent", older_child: "Older child", younger_child: "Younger child", dependent: "Doesn't use the app" };
const displayName = (m) => plainNames ? (m.plain || m.name) : m.name;

function renderFamily() {
  const roles = serverFeatures.includes("roles");
  $("#family-list").replaceChildren(...lastMembers.map((m) => {
    const access = m.access || (m.role === "child" ? "younger_child" : m.role === "dependent" ? "dependent" : "parent");
    let control;
    if (!roles || !isAdmin() || access === "dependent") {
      control = el("span", { class: "badge" + (access === "admin" ? " on" : "") }, ACCESS_LABELS[access] || access);
    } else {
      control = el("select", { class: "role-select", "aria-label": `${m.name}'s role` },
        ...["admin", "parent", "grandparent", "older_child", "younger_child"].map((a) => {
          const o = el("option", { value: a }, ACCESS_LABELS[a]);
          // Only the main admin makes or unmakes admins.
          if (!me.owner && (a === "admin" || access === "admin") && a !== access) o.disabled = true;
          return o;
        }));
      control.value = access;
      control.addEventListener("change", async () => {
        try { await api("PUT", `/api/admin/members/${m.id}/role`, { role: control.value }); refresh(); }
        catch (err) { alert(err.message); control.value = access; }
      });
    }
    const sub = access === "dependent" ? "Part of the family; doesn't use the app"
      : access === "younger_child" ? "Signs in with a join code from a parent (no password)"
      : isAdmin() ? account(m) : "";
    return el("li", {},
      avatarOf(m),
      el("div", { class: "grow" },
        el("div", { class: "title" }, m.name),
        sub ? el("div", { class: "sub" }, sub) : null),
      control,
      !isAdmin() ? (m.role !== "dependent" && me.member && me.member.role === "parent" ? moreButton(`Options for ${m.name}`, [["Access & Control…", () => { location.hash = "#/access/" + m.id; }]]) : null) : moreButton(`Options for ${m.name}`, [
        ...(m.role !== "dependent" ? [["Access & Control…", () => { location.hash = "#/access/" + m.id; }], "-"] : []),
        ["Rename…", () => rename(m)],
        ["Icon and colour…", () => openIconPicker(m)],
        ...(access === "dependent" ? [] : [[access === "younger_child" ? "New join code…" : "Reset sign-in…", () => resetSignIn(m)]]),
        "-",
        ["Remove from server…", () => removeMember(m), "danger"],
      ]));
  }));
  if (!lastMembers.length) $("#family-list").append(el("li", { class: "muted" }, "Nobody yet. Invite the first person."));
}

// ---- the parent PIN (admins)
async function loadPIN() {
  try {
    const { set } = await api("GET", "/api/admin/pin");
    $("#pin-status").textContent = set ? "Set. It unlocks settings on children's devices and family displays."
      : "Not set yet. Set one so children's devices can keep their settings locked.";
    $("#pin-set").textContent = set ? "Change PIN…" : "Set PIN…";
    $("#pin-clear").hidden = !set;
  } catch (_) { $("#pin-row").hidden = true; }
}
$("#pin-set").addEventListener("click", async () => {
  const pin = prompt("New parent PIN (4 to 8 digits). Every parent uses the same one:");
  if (pin === null) return;
  if (!/^[0-9]{4,8}$/.test(pin)) { alert("The PIN is 4 to 8 digits."); return; }
  if (prompt("Type it again:") !== pin) { alert("Those didn't match."); return; }
  try { await api("PUT", "/api/admin/pin", { pin }); loadPIN(); } catch (err) { alert(err.message); }
});
$("#pin-clear").addEventListener("click", async () => {
  if (!confirm("Remove the parent PIN? Children's devices will then keep their settings closed until a new one is set.")) return;
  try { await api("PUT", "/api/admin/pin", { pin: "" }); loadPIN(); } catch (err) { alert(err.message); }
});

// ---- Access & Control: what each person can use (parents and admins)
const TILE_NAMES = { map: ["Thereabouts", "Map"], chat: ["Chitchat", "Chat"], sos: ["SOS", "SOS"], checkin: ["Check in", "Check in"],
  calendar: ["Datebook", "Calendar"], todos: ["Odds & Ends", "Lists"], chores: ["Fair Share", "Chores"], meals: ["Pantry", "Meals"] };
const tileName = (t) => (TILE_NAMES[t] || [t, t])[plainNames ? 1 : 0];
const LEVEL_NAMES = { off: "Off", view: "View only", add: "Can add", full: "Full" };
const SIZE_NAMES = { half: "Half width", wide: "Full width", big: "Big" };
const accessOpen = new Set();   // whose panels are open
const accessData = new Map();   // id -> their settings from the server
const caret = '<svg class="acc-caret" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>';

function renderAccess() {
  const focus = location.hash.split("/")[2];
  if (focus && !accessOpen.has(focus)) { accessOpen.add(focus); history.replaceState(null, "", "#/access"); }
  if (isAdmin()) loadPIN();
  const people = lastMembers.filter((m) => m.role !== "dependent");
  $("#access-list").replaceChildren(...people.map((m) => {
    const access = m.access || "parent";
    const head = el("button", { type: "button", class: "acc-head", "aria-expanded": String(accessOpen.has(m.id)) },
      avatarOf(m),
      el("div", {}, el("div", { class: "title" }, m.name), el("div", { class: "sub" }, ACCESS_LABELS[access] || access)));
    head.insertAdjacentHTML("beforeend", caret);
    const body = el("div", { class: "acc-body", id: "acc-" + m.id });
    body.hidden = !accessOpen.has(m.id);
    head.addEventListener("click", () => {
      const open = !accessOpen.has(m.id);
      open ? accessOpen.add(m.id) : accessOpen.delete(m.id);
      head.setAttribute("aria-expanded", String(open));
      body.hidden = !open;
      if (open) fillAccess(m, body);
    });
    if (accessOpen.has(m.id)) fillAccess(m, body);
    return el("li", { class: "acc" }, head, body);
  }));
  if (!people.length) $("#access-list").append(el("li", { class: "muted" }, "Nobody yet. Invite your family from the Family page."));
}

async function fillAccess(m, body) {
  const access = m.access || "parent";
  if (access === "parent" || access === "admin") {
    body.replaceChildren(el("p", { class: "acc-note" }, "Parents can use everything, and arrange their own home screen in the app."));
    return;
  }
  if (!accessData.has(m.id)) body.replaceChildren(el("p", { class: "acc-note" }, "Loading…"));
  try { accessData.set(m.id, await api("GET", `${memberBase()}/${m.id}/kid`)); }
  catch (err) { body.replaceChildren(el("p", { class: "acc-note" }, err.message)); return; }
  drawAccess(m, body);
}

async function saveAccess(m, body, change, label = "Saved") {
  try {
    accessData.set(m.id, await api("PUT", `${memberBase()}/${m.id}/kid`, change));
    drawAccess(m, body);
    const note = body.querySelector(".acc-saved");
    if (note) { note.textContent = label; setTimeout(() => { note.textContent = ""; }, 1800); }
  } catch (err) { alert(err.message); drawAccess(m, body); }
}

function drawAccess(m, body) {
  const d = accessData.get(m.id);
  const child = !!d.settings;
  const parts = [];
  if (child) {
    const s = d.settings;
    // Preset
    const preset = el("div", { class: "preset-row" }, ...[["younger", "Younger child", "Can't read yet: Thereabouts and Chitchat with big pictures, and SOS"], ["older", "Older child", "Everything except family settings"]].map(([id, title, text]) => {
      const r = el("input", { type: "radio", name: "preset-" + m.id, value: id });
      r.checked = s.preset === id;
      r.addEventListener("change", () => {
        if (!confirm(`Switch ${m.name} to the ${title} preset? Their switches start again from it.`)) { drawAccess(m, body); return; }
        saveAccess(m, body, { preset: id });
      });
      return el("label", {}, r, el("strong", {}, title), el("span", { class: "sub" }, text));
    }));
    parts.push(el("h3", {}, "Starting point", el("span", { class: "acc-saved" })), preset);
    // Modules, each with its level and its own features underneath
    parts.push(el("h3", {}, "What they can use"));
    for (const mod of d.modules) {
      const info = moduleInfo(mod.id);
      const lvl = s.modules[mod.id];
      const seg = el("div", { class: "seg", role: "radiogroup", "aria-label": `${mod.name} for ${m.name}` }, ...d.levels.map((l) => {
        const r = el("input", { type: "radio", name: `lvl-${m.id}-${mod.id}`, value: l });
        r.checked = lvl === l;
        r.addEventListener("change", () => saveAccess(m, body, { modules: { [mod.id]: l } }));
        return el("label", {}, r, LEVEL_NAMES[l] || l);
      }));
      const note = info && !info.enabled ? "off for the whole family" : info && !info.available ? "coming soon" : "";
      const feats = d.features.filter((f) => f.module === mod.id);
      parts.push(el("div", { class: "acc-mod" },
        el("div", { class: "name" }, plainNames ? mod.plain : mod.name, note ? el("small", {}, note) : null),
        seg,
        feats.length && lvl !== "off" ? el("div", { class: "acc-feats" }, ...feats.map((f) => {
          const box = el("input", { type: "checkbox" });
          box.checked = !!s.features[f.id];
          box.addEventListener("change", () => saveAccess(m, body, { features: { [f.id]: box.checked } }));
          return el("label", {}, box, f.name);
        })) : null));
    }
    parts.push(el("p", { class: "acc-note" }, "View only: they can look but not change anything (say, recipes they're learning). Can add: they add their own things but can't change or delete other people's."));
  } else {
    parts.push(el("h3", {}, "What they can use", el("span", { class: "acc-saved" })),
      el("p", { class: "acc-note" }, "Grandparents use the whole app for themselves, without parents' controls or the children's chats."));
  }
  // Look
  const opt = (key, text) => {
    const box = el("input", { type: "checkbox" });
    box.checked = !!d[key];
    box.addEventListener("change", () => saveAccess(m, body, { [key]: box.checked }));
    return el("label", {}, box, text);
  };
  parts.push(el("h3", {}, "Easier to use"), el("div", { class: "acc-opts" },
    opt("big", "Bigger buttons and text"),
    opt("pictures", "Picture icons (a tiny map, a talking face) instead of the usual symbols")));
  // Home screen: drag to order, tick to show, choose a size
  parts.push(el("h3", {}, "Home screen"),
    el("p", { class: "acc-note" }, "Drag ☰ to reorder, tick what to show and pick a size. A half-width tile alone on its row stretches to fill it. Nothing ticked: the app's default."),
    homeEditor(d, (home) => saveAccess(m, body, { home })));
  // Their device
  parts.push(el("h3", {}, "Their device"), el("div", { class: "row" },
    el("button", { type: "button", class: "secondary", onclick: async () => {
      try { await api("POST", `${memberBase()}/${m.id}/unlock`, { minutes: 10 }); alert(`Settings on ${m.name}'s device are open for the next 10 minutes.`); }
      catch (err) { alert(err.message); }
    } }, "Open their settings for 10 minutes")),
    el("p", { class: "acc-note" }, "For when you're helping on their device. On the device itself: hold the family name at the top for 5 seconds, then type the parent PIN."));
  body.replaceChildren(...parts);
}

function homeEditor(d, save) {
  const parse = (t) => { const [id, size] = t.split(":"); return { id, size: size || (id === "map" || id === "chat" ? "wide" : "half") }; };
  let on = d.home.map(parse);
  const all = () => on.concat(d.tiles.filter((t) => !on.some((x) => x.id === t)).map((t) => ({ ...parse(t), off: true })));
  const commit = () => save(on.map((t) => `${t.id}:${t.size}`));
  const list = el("ul", { class: "tiles" });
  let dragging = null;
  const draw = () => list.replaceChildren(...all().map((t) => {
    const box = el("input", { type: "checkbox", "aria-label": `Show ${tileName(t.id)}` });
    box.checked = !t.off;
    box.addEventListener("change", () => {
      on = box.checked ? on.concat({ id: t.id, size: t.size }) : on.filter((x) => x.id !== t.id);
      commit();
    });
    const size = el("select", { "aria-label": `${tileName(t.id)} size` }, ...Object.entries(SIZE_NAMES).filter(([k]) => !(t.id === "map" && k === "half")).map(([k, v]) => el("option", { value: k }, v)));
    size.value = t.size;
    size.disabled = !!t.off;
    size.addEventListener("change", () => { on = on.map((x) => x.id === t.id ? { ...x, size: size.value } : x); commit(); });
    const li = el("li", { class: t.off ? "off" : "", draggable: t.off ? "false" : "true" }, el("span", { class: "grip", title: "Drag to reorder" }, "☰"), box, el("span", { class: "grow" }, tileName(t.id)), size);
    li.addEventListener("dragstart", () => { dragging = t.id; li.classList.add("dragging"); });
    li.addEventListener("dragend", () => li.classList.remove("dragging"));
    li.addEventListener("dragover", (e) => { if (dragging && !t.off) e.preventDefault(); });
    li.addEventListener("drop", (e) => {
      e.preventDefault();
      if (!dragging || dragging === t.id) return;
      const from = on.findIndex((x) => x.id === dragging), to = on.findIndex((x) => x.id === t.id);
      const [moved] = on.splice(from, 1);
      on.splice(to, 0, moved);
      dragging = null;
      commit();
    });
    return li;
  }));
  draw();
  return list;
}

$("#family-dependent").addEventListener("click", async () => {
  const name = prompt("Their name (e.g. a baby who doesn't use the app):");
  if (!name) return;
  try { await api("POST", "/api/admin/dependents", { name }); refresh(); }
  catch (err) { alert(err.message); }
});
$("#logout-btn3").addEventListener("click", () => $("#logout-btn").click());
$("#theme-select").replaceChildren(...THEMES.map(([id, name]) => el("option", { value: id }, name)));
$("#theme-select").value = document.documentElement.dataset.theme;
$("#mode-select").value = themeMode;
$("#plain-names").checked = plainNames;
$("#plain-names").addEventListener("change", (e) => {
  plainNames = e.target.checked;
  try { localStorage.setItem("plainNames", plainNames ? "1" : "0"); } catch (_) {}
  renderRail();
  renderView();
});
$("#theme-select").addEventListener("change", (e) => { themeId = e.target.value; try { localStorage.setItem("theme", themeId); } catch (_) {} applyAppearance(); });
$("#mode-select").addEventListener("change", (e) => { themeMode = e.target.value; try { localStorage.setItem("themeMode", themeMode); } catch (_) {} applyAppearance(); });
$("#family-invite").addEventListener("click", () => $("#invite-btn").click());

function renderDevices() {
  const list = $("#device-list");
  list.replaceChildren(...lastMembers.map((m) => el("li", {},
    avatarOf(m),
    el("div", { class: "grow" },
      el("div", { class: "title" }, `${m.name}'s phone`),
      m.app || m.lastContact ? health(m) : el("div", { class: "sub" }, "Hasn't reported yet")),
    moreButton(`Options for ${m.name}'s phone`, [
      ["Sign out this phone (new sign-in link)…", () => resetSignIn(m)],
    ]))));
  if (!lastMembers.length) list.append(el("li", { class: "muted" }, "No phones yet."));
}

// Settings: Modules, Server, AI & API.
let settingsTab = "modules";
document.querySelectorAll("#settings-tabs [data-sub]").forEach((b) => b.addEventListener("click", () => { settingsTab = b.dataset.sub; renderSettings(); }));

function moduleRows(target, checked) {
  target.replaceChildren(...modules.map((m) => {
    const box = el("input", { type: "checkbox", "data-module": m.id });
    box.checked = checked(m);
    return el("li", {}, el("label", {},
      box,
      el("div", { class: "grow" },
        el("div", { class: "title" }, m.name, m.available ? null : el("span", { class: "badge" }, "coming soon")),
        el("div", { class: "sub" }, m.description))));
  }));
}
const chosenModules = (target) => Object.fromEntries([...target.querySelectorAll("input[data-module]")].map((b) => [b.dataset.module, b.checked]));

function renderSettings() {
  document.querySelectorAll("#settings-tabs [data-sub]").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.sub === settingsTab)));
  document.querySelectorAll("[data-sub-panel]").forEach((p) => { p.hidden = p.dataset.subPanel !== settingsTab; });
  if (settingsTab === "modules" && !$("#module-list").contains(document.activeElement)) moduleRows($("#module-list"), (m) => m.enabled);
  if (settingsTab === "server") {
    const facts = [["Family", $("#family-name").textContent], ["Server", $("#server-version").textContent.replace(/^server /, "")],
      ["Modules on", modules.filter((m) => m.enabled).map((m) => m.name + (m.available ? "" : " (soon)")).join(", ") || "none"],
      ["Address search", serverFeatures.includes("geocode") ? "on" : "off"], ["Quiet-phone alerts", serverFeatures.includes("silence") ? "on" : "off"]];
    $("#server-facts").replaceChildren(...facts.flatMap(([k, v]) => [el("dt", {}, k), el("dd", {}, v)]));
  }
}

$("#modules-save").addEventListener("click", async () => {
  try {
    const res = await api("PUT", "/api/admin/modules", { enabled: chosenModules($("#module-list")), setupDone: true });
    setModules(res);
    $("#modules-msg").textContent = "Saved";
    setTimeout(() => ($("#modules-msg").textContent = ""), 2000);
    refresh();
  } catch (err) { alert(err.message); }
});
$("#logout-btn2").addEventListener("click", () => $("#logout-btn").click());

function renderModulePage(id) {
  const m = moduleInfo(id);
  $("#module-title").textContent = displayName(m);
  $("#module-desc").textContent = m.description;
  const box = $("#module-soon");
  box.replaceChildren(
    el("p", {}, el("strong", {}, "Coming soon. "), "It's switched on for your family, so it will appear here and in the app when it arrives."),
    el("ul", { class: "plain" }, ...(MODULE_PLANS[id] || []).map((t) => el("li", {}, t))));
}

function setModules(res) {
  modules = res.modules || [];
  modulesLoaded = true;
  renderRail();
}

async function loadModules() {
  try {
    const res = await api("GET", "/api/admin/modules");
    setModules(res);
    if (!res.setupDone && isAdmin()) openSetup();
  } catch (err) {
    // An older server: no modules, everything is Thereabouts.
    if (err.message !== "unauthorized") modulesLoaded = false;
  }
}

// First run: which modules does this family want?
const setupDialog = $("#setup-dialog");
function openSetup() {
  moduleRows($("#setup-modules"), (m) => m.enabled);
  if (!setupDialog.open) setupDialog.showModal();
}
$("#setup-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  try {
    setModules(await api("PUT", "/api/admin/modules", { enabled: chosenModules($("#setup-modules")), setupDone: true }));
    setupDialog.close();
    showView(view === "home" ? "home" : view);
    refresh();
  } catch (err) { alert(err.message); }
});

// ---- chat ----
// People see their own chats; parents also see (read only) any chat their children
// are in. The main admin (admin password, no account) sees every chat, read only.

let chatConvs = [];
let chatOpen = "";        // the open conversation's id
let chatMsgs = [];
let chatTimer = null;
const personById = (id) => lastMembers.find((m) => m.id === id);
const nameOf = (id) => (personById(id) || {}).name || "Someone";
const chatUnread = () => chatConvs.reduce((n, c) => n + (c.unread || 0), 0);
const chatBase = () => me.owner ? "/api/admin/chat" : "/api/chat";

async function loadChats() {
  try {
    chatConvs = (await api("GET", chatBase())).conversations || [];
  } catch (err) {
    chatConvs = [];
    $("#chat-note").textContent = err.message;
  }
}

function renderChatList() {
  $("#chat-note").textContent = me.owner ? "You're the main admin: you can read every chat, but not post. Log in with your own account to chat."
    : me.member && me.member.role === "parent" && chatConvs.some((c) => c.oversight) ? "Chats marked “Watching” are your children's: you can read them, not post." : "";
  $("#chat-convs").replaceChildren(...chatConvs.map((c) => {
    const last = c.last ? (c.last.text || "Photo") : "No messages yet";
    const who = c.last && c.kind !== "direct" ? nameOf(c.last.sender) + ": " : "";
    return el("li", { class: "clickable" + (c.id === chatOpen ? " active" : ""), onclick: () => openChat(c.id) },
      el("div", { class: "avatar" + (c.emoji ? " emoji" : ""), style: `background:${c.color || (c.kind === "direct" ? (personById(c.members.find((id) => id !== (me.member || {}).id)) || {}).color || "var(--accent)" : "var(--accent)")}` },
        c.emoji || (c.kind === "family" ? "★" : (c.title || "?").trim().charAt(0).toUpperCase())),
      el("div", { class: "grow" },
        el("div", { class: "title" }, c.title, c.oversight ? el("span", { class: "badge" }, "Watching") : null,
          c.status ? el("span", { class: "badge warn" }, STATUS_LABELS[c.status] || c.status) : null),
        el("div", { class: "sub" }, (who + last).slice(0, 80))),
      c.unread ? el("span", { class: "badge on" }, String(c.unread)) : null);
  }));
}

const STATUS_LABELS = { pending: "Needs OK", paused: "Paused", blocked: "Blocked" };
const hasChildIn = (conv) => conv.members.some((id) => (personById(id) || {}).role === "child");
// Parents (and the main admin) manage chats with a child in them.
const canModerate = (conv) => conv.kind !== "family" && (me.owner || (me.member && me.member.role === "parent" && hasChildIn(conv)));
const modPath = (id) => (me.owner ? "/api/admin/chat/" : "/api/chat/") + encodeURIComponent(id) + "/moderate";

async function moderate(conv, action) {
  if (action === "delete" && !confirm(`Delete "${conv.title}" and all its messages and photos for everyone? This can't be undone.`)) return;
  if (action === "block" && !confirm(`Block "${conv.title}"? The children in it won't see it any more; parents still can.`)) return;
  try {
    await api("POST", modPath(conv.id), { action });
    if (action === "delete") { chatOpen = ""; chatMsgs = []; $("#chat-messages").replaceChildren(); $("#chat-mod").replaceChildren(); $("#chat-title").textContent = "Choose a chat"; $("#chat-sub").textContent = ""; $("#chat-banner").hidden = true; }
    await loadChats();
    renderChatList();
    if (chatOpen) await pollThread();
  } catch (err) { alert(err.message); }
}

async function deleteMessage(conv, m) {
  if (!confirm("Delete this message for everyone?")) return;
  try {
    await api("DELETE", (me.owner ? "/api/admin/chat/" : "/api/chat/") + `${encodeURIComponent(conv.id)}/messages/${m.id}`);
    chatMsgs = chatMsgs.filter((x) => x.id !== m.id);
    await pollThread();
  } catch (err) { alert(err.message); }
}

function renderModeration(conv) {
  const mod = canModerate(conv);
  const b = (label, action, cls = "secondary") => el("button", { type: "button", class: cls, onclick: () => moderate(conv, action) }, label);
  $("#chat-mod").replaceChildren(...(!mod ? [] : [
    conv.status === "pending" ? b("Say yes", "approve", "") : null,
    conv.status === "pending" || conv.status === "blocked" ? null : conv.status === "paused" ? b("Resume", "resume") : b("Pause", "pause"),
    conv.status === "blocked" ? b("Unblock", "unblock") : conv.status === "pending" ? null : b("Block", "block"),
    b("Delete", "delete", "danger"),
  ].filter(Boolean)));
  const banner = $("#chat-banner");
  const text = conv.status === "pending" ? (mod ? "A child started this group. Nobody can post until a parent says yes (or delete it to say no)." : "Waiting for a parent to say yes.")
    : conv.status === "paused" ? "Paused by a parent: everyone can read it, nobody can post."
    : conv.status === "blocked" ? "Blocked by a parent: hidden from the children in it." : "";
  banner.textContent = text;
  banner.hidden = !text;
}

function dayLabel(ms) {
  const d = new Date(ms), today = new Date();
  if (d.toDateString() === today.toDateString()) return "Today";
  const y = new Date(today); y.setDate(today.getDate() - 1);
  if (d.toDateString() === y.toDateString()) return "Yesterday";
  return d.toLocaleDateString([], { weekday: "long", day: "numeric", month: "long" });
}

function renderThread(data) {
  const conv = data.conversation;
  $("#chat-title").textContent = conv.title;
  $("#chat-sub").textContent = conv.kind === "family" ? "Everyone in the family" : conv.kind === "group" ? conv.members.map(nameOf).join(", ") : "";
  const box = $("#chat-messages");
  const atBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 60;
  const mine = (me.member || {}).id;
  const reads = data.reads || {};
  // "Seen by" under my latest message: everyone else who has read up to it.
  const myLast = [...chatMsgs].reverse().find((m) => m.sender === mine);
  const seenBy = myLast ? Object.entries(reads).filter(([id, upTo]) => id !== mine && upTo >= myLast.id).map(([id]) => nameOf(id)) : [];
  let lastDay = "";
  const nodes = [];
  for (const m of chatMsgs) {
    const day = dayLabel(m.time);
    if (day !== lastDay) { nodes.push(el("div", { class: "chat-day" }, day)); lastDay = day; }
    const isMine = m.sender === mine;
    const bubble = el("div", { class: "bubble" });
    if (m.photo) bubble.append(el("img", { src: `/api/chat-media/${encodeURIComponent(m.photo)}`, alt: "Photo", loading: "lazy" }));
    if (m.text) bubble.append(m.text);
    const canDelete = isMine || canModerate(conv);
    nodes.push(el("div", { class: "msg" + (isMine ? " mine" : "") },
      isMine || conv.kind === "direct" && !conv.oversight ? null : el("div", { class: "who" }, nameOf(m.sender)),
      bubble,
      el("div", { class: "meta" }, new Date(m.time).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) +
        (myLast && m.id === myLast.id && seenBy.length ? ` · Seen by ${seenBy.join(", ")}` : ""),
        canDelete ? el("button", { type: "button", class: "del", onclick: () => deleteMessage(conv, m) }, "Delete") : null)));
  }
  if (!chatMsgs.length) nodes.push(el("p", { class: "muted" }, "No messages yet. Say hello!"));
  box.replaceChildren(...nodes);
  if (atBottom || box.dataset.conv !== conv.id) box.scrollTop = box.scrollHeight;
  box.dataset.conv = conv.id;
  renderModeration(conv);
  const canPost = !!data.canPost && !me.owner;
  $("#chat-compose").hidden = !canPost;
  $("#chat-readonly").hidden = canPost;
  $("#chat-readonly").textContent = me.owner ? "Read only (main admin)."
    : conv.status && conv.members.includes((me.member || {}).id) ? "" : "You can read this chat because a child of yours is in it. Only the people in it can post.";
  if (!$("#chat-readonly").textContent) $("#chat-readonly").hidden = true;
}

async function openChat(id) {
  if (chatOpen !== id) { chatMsgs = []; $("#chat-messages").replaceChildren(); }
  chatOpen = id;
  renderChatList();
  await pollThread();
}

async function pollThread() {
  if (!chatOpen) return;
  try {
    const after = chatMsgs.length ? chatMsgs[chatMsgs.length - 1].id : 0;
    const data = await api("GET", `${chatBase()}/${encodeURIComponent(chatOpen)}/messages` + (after && !me.owner ? `?after=${after}` : ""));
    if (after && !me.owner) chatMsgs = chatMsgs.concat(data.messages); else chatMsgs = data.messages;
    renderThread(data);
    const newest = chatMsgs.length ? chatMsgs[chatMsgs.length - 1].id : 0;
    if (data.canPost && newest) {
      await api("POST", `/api/chat/${encodeURIComponent(chatOpen)}/read`, { upTo: newest }).catch(() => {});
      const c = chatConvs.find((x) => x.id === chatOpen);
      if (c && c.unread) { c.unread = 0; renderChatList(); }
    }
  } catch (err) { console.error(err); }
}

async function renderChat() {
  await loadChats();
  if (!chatOpen && chatConvs.length) chatOpen = chatConvs[0].id;
  renderChatList();
  await pollThread();
  clearInterval(chatTimer);
  chatTimer = setInterval(() => {
    if (view !== "chat" || document.hidden) return;
    pollThread();
    loadChats().then(renderChatList);
  }, 4000);
}

$("#chat-compose").addEventListener("submit", async (e) => {
  e.preventDefault();
  const text = $("#chat-text").value.trim();
  if (!text || !chatOpen) return;
  $("#chat-text").value = "";
  try {
    await api("POST", `/api/chat/${encodeURIComponent(chatOpen)}/messages`, { text });
    await pollThread();
    loadChats().then(renderChatList);
  } catch (err) { alert(err.message); $("#chat-text").value = text; }
});

$("#chat-photo").addEventListener("change", async () => {
  const file = $("#chat-photo").files[0];
  $("#chat-photo").value = "";
  if (!file || !chatOpen) return;
  const form = new FormData();
  form.append("photo", file);
  form.append("text", $("#chat-text").value.trim());
  $("#chat-text").value = "";
  try {
    const res = await fetch(`/api/chat/${encodeURIComponent(chatOpen)}/messages`, { method: "POST", body: form, credentials: "same-origin" });
    if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error || res.statusText);
    await pollThread();
  } catch (err) { alert(err.message); }
});

// New chat: one person → one-to-one; several → a named group.
const chatNewDialog = $("#chat-new-dialog");
$("#chat-new").addEventListener("click", () => {
  const mine = (me.member || {}).id;
  const people = lastMembers.filter((m) => m.id !== mine && m.role !== "dependent");
  $("#chat-new-people").replaceChildren(...people.map((m) => el("li", {}, el("label", {},
    el("input", { type: "checkbox", value: m.id, onchange: () => { $("#chat-new-name-label").hidden = chatNewDialog.querySelectorAll("input[type=checkbox]:checked").length < 2; } }),
    avatarOf(m), el("div", { class: "grow" }, el("div", { class: "title" }, m.name))))));
  $("#chat-new-name").value = "";
  $("#chat-new-name-label").hidden = true;
  chatNewDialog.showModal();
});
chatNewDialog.querySelectorAll("[data-close]").forEach((b) => b.addEventListener("click", () => chatNewDialog.close()));
$("#chat-new-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const ids = [...chatNewDialog.querySelectorAll("input[type=checkbox]:checked")].map((b) => b.value);
  if (!ids.length) return;
  try {
    const conv = ids.length === 1
      ? await api("POST", "/api/chat/direct", { to: ids[0] })
      : await api("POST", "/api/chat/groups", { name: $("#chat-new-name").value || "Group", members: ids });
    chatNewDialog.close();
    await loadChats();
    openChat(conv.id);
  } catch (err) { alert(err.message); }
});

// ---- start ----

async function start() {
  try { me = await api("GET", "/api/admin/session"); } catch (_) { return; }
  document.body.classList.toggle("parent-mode", !me.admin);
  document.body.classList.toggle("owner-mode", !!me.owner);
  $("#whoami").textContent = me.member ? `Logged in as ${me.member.name}${me.admin ? " (admin)" : ""}` : "Logged in as the main admin";
  $("#login").hidden = true;
  $("#app").hidden = false;
  await loadModules();
  await refresh();
  showView(viewFromHash());
  if (map) map.invalidateSize();
  clearInterval(refreshTimer);
  refreshTimer = setInterval(() => { if (!document.hidden) refresh(); }, REFRESH_MS);
}

api("GET", "/api/admin/session").then(start).catch(() => {});
