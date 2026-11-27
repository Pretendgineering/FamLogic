// FamLogic web dashboard. Plain JS, no build step.
"use strict";

const $ = (sel) => document.querySelector(sel);

// ---- appearance: this browser's palette and light/dark (the same palettes as the app) ----
const THEMES = [["dracula", "Default"], ["hannah", "Hannah's Theme"], ["forest", "Hobbit Door"], ["ocean", "Boring Blue"], ["paper", "Lofi Cabin"], ["noir", "Logic Noir"], ["orange", "Safety Cone"], ["chill", "Spider-Man"], ["nightshift", "Night Shift"], ["gamer", "Gamer Mode"], ["couch", "Grandma's Couch"], ["contrast", "High contrast"]];
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
      sosMark(), `SOS from ${m.name} since ${new Date(m.sosSince).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}` +
      (m.sosSeenBy && m.sosSeenBy.length ? ` · seen by ${m.sosSeenBy.join(", ")}` : "") +
      (m.loc ? ` · last seen ${ago(m.loc.time)}, click to show` : " · no location yet"))));
}

// Driving, as of a recent fix (an old "driving" fix means the phone went quiet mid-drive).
const isDriving = (m) => !!(m.loc && m.loc.driving && !m.paused && Date.now() - m.loc.time < 10 * 60 * 1000);

function describe(m) {
  if (isDriving(m) && !m.sosSince) {
    return `Driving · ${ago(m.loc.time)}` + (m.loc.battery >= 0 ? ` · ${m.loc.battery}%${m.loc.charging ? ", charging" : ""}` : "");
  }
  if (m.place && !m.sosSince && !m.paused && m.loc) {
    return `At ${m.place} · ${ago(m.loc.time)}` + (m.loc.battery >= 0 ? ` · ${m.loc.battery}%${m.loc.charging ? ", charging" : ""}` : "");
  }
  if (m.sosSince) return `SOS since ${new Date(m.sosSince).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}` + (m.loc ? ` · ${ago(m.loc.time)}` : "");
  if (m.paused) return "Sharing paused";
  if (!m.loc) return "No location yet";
  const parts = [ago(m.loc.time)];
  if (m.loc.battery >= 0) parts.push(`${m.loc.battery}%${m.loc.charging ? ", charging" : m.loc.saving ? " (saving battery)" : ""}`);
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
  // "Show everyone": fit the map to every person sharing (and the fences, if nobody is).
  const Everyone = L.Control.extend({ options: { position: "topright" }, onAdd() {
    const b = L.DomUtil.create("button", "map-everyone");
    b.type = "button"; b.innerHTML = icon("people") + "<span>Show everyone</span>"; b.title = "Fit the map to everyone";
    L.DomEvent.disableClickPropagation(b);
    b.addEventListener("click", () => {
      const pts = everyoneBounds.length ? everyoneBounds : places.map((p) => [p.lat, p.lon]);
      if (pts.length) map.fitBounds(pts, { padding: [50, 50], maxZoom: 15 });
    });
    return b;
  } });
  new Everyone().addTo(map);
  applyTiles();
}
let everyoneBounds = [];

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

  everyoneBounds = bounds;
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

async function refresh(periodic = false) {
  try {
    const [data, invites] = isAdmin()
      ? await Promise.all([api("GET", "/api/admin/members"), api("GET", "/api/admin/invites")])
      : [await api("GET", "/api/members?since=9007199254740991"), []];
    $("#family-name").textContent = data.server.name;
    $("#server-version").textContent = `server ${data.server.version}`;
    serverFeatures = data.server.features || [];
    const sos = data.members.filter((m) => m.sosSince);
    document.title = (sos.length ? "SOS · " : "") + `${data.server.name} · FamLogic`;
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
    // The 10-second refresh redraws a page only if something on it changed: pages
    // you fill in (Access & Control, Family) were being rebuilt under your hands.
    if (view && view !== "thereabouts") {
      const formPage = view === "access" || view === "family";
      const typing = document.activeElement && document.activeElement.closest(".page") && document.activeElement.matches("input, select, textarea");
      if (!(periodic && formPage && (drawnSig[view] === peopleSig() || typing))) renderView();
    }
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

const ICON_COLORS = ["#e6194b", "#c2185b", "#ff6f61", "#f58231", "#ff9000", "#e0a100", "#c0ca33", "#3cb44b", "#1e5631", "#00a88c", "#00e3bd", "#469990",
  "#0fa3c2", "#4363d8", "#1a237e", "#6e48d6", "#911eb4", "#f032e6", "#ff8fab", "#a85c6f", "#9a6324", "#5d4037", "#607d8b", "#2b2b2b"];
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
  upkeep: '<path d="M17.5 4.5H7a2 2 0 0 0 0 4h1M8 6.5V19.5h10.5M16.5 4.5V17.5a2 2 0 0 0 4 0V16.5h-2M11 10h3M11 13.5h3"/>',
  tv: '<rect x="3" y="5" width="18" height="12" rx="2.5"/><path d="M8.5 20.5h7M12 17v3.5"/>',
  walk: '<circle cx="13" cy="4.5" r="1.8"/><path d="m9.5 21 2.5-6.5 2.5 2.5V21M8 11l3-3.5h3l2.5 3.5 2.5 1M11 7.5 9.5 13l-2 2"/>',
  car: '<path d="M4 16.5v-4l2-5.2A2 2 0 0 1 7.9 6h8.2a2 2 0 0 1 1.9 1.3l2 5.2v4H4z"/><path d="M4 12.5h16"/><circle cx="7.5" cy="16.5" r="1.8"/><circle cx="16.5" cy="16.5" r="1.8"/>',
  wallet: '<rect x="3.5" y="6" width="17" height="13" rx="2.5"/><path d="M3.5 9.5h17M15.5 14h2"/><path d="M6 6l9-2.5 1 2.5"/>',
  paw: '<circle cx="7" cy="10" r="1.7"/><circle cx="10.3" cy="6.5" r="1.7"/><circle cx="13.7" cy="6.5" r="1.7"/><circle cx="17" cy="10" r="1.7"/><path d="M12 11.5c-2.6 0-5 3.4-5 5.7 0 1.6 1.4 2.3 2.7 2.3.9 0 1.5-.5 2.3-.5s1.4.5 2.3.5c1.3 0 2.7-.7 2.7-2.3 0-2.3-2.4-5.7-5-5.7z"/>',
  idcard: '<rect x="3.5" y="5.5" width="17" height="13" rx="2.5"/><circle cx="9" cy="11" r="2"/><path d="M5.8 16c.6-1.6 1.8-2.4 3.2-2.4s2.6.8 3.2 2.4M14.5 10h3M14.5 13.5h3"/>',
  house: '<path d="M3.5 11 12 4l8.5 7"/><path d="M5.5 9.5V20h13V9.5"/><path d="M10 20v-5h4v5"/>',
  book: '<path d="M6.5 3.5h11a1 1 0 0 1 1 1v15a1 1 0 0 1-1 1h-11a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2z"/><circle cx="12" cy="10" r="2.2"/><path d="M8.5 16c.7-1.7 2-2.5 3.5-2.5s2.8.8 3.5 2.5M4.5 7.5h-1M4.5 12h-1M4.5 16.5h-1"/>',
  mic: '<rect x="9" y="3.5" width="6" height="11" rx="3"/><path d="M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v2.5"/>',
  spark: '<path d="M12 3.5c.6 3.9 2.6 5.9 6.5 6.5-3.9.6-5.9 2.6-6.5 6.5-.6-3.9-2.6-5.9-6.5-6.5 3.9-.6 5.9-2.6 6.5-6.5z"/><path d="M18.5 15.5c.3 1.5 1 2.2 2.5 2.5-1.5.3-2.2 1-2.5 2.5-.3-1.5-1-2.2-2.5-2.5 1.5-.3 2.2-1 2.5-2.5z"/>',
  sos: '<path fill="currentColor" stroke="none" fill-rule="evenodd" d="M12 6.2a5.8 5.8 0 1 1 0 11.6a5.8 5.8 0 1 1 0-11.6ZM11.05 9.1a.95 .95 0 0 1 1.9 0L12.75 12.6a.75 .75 0 0 1-1.5 0ZM12 13.9a1.05 1.05 0 1 1 0 2.1a1.05 1.05 0 1 1 0-2.1Z"/><path d="M18.9 8.2A8 8 0 0 1 18.9 15.8M5.1 15.8A8 8 0 0 1 5.1 8.2M21.4 6.4A11 11 0 0 1 21.4 17.6M2.6 17.6A11 11 0 0 1 2.6 6.4"/>',
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
const MODULE_ICONS = { thereabouts: "pin", chat: "chat", calendar: "calendar", todos: "check", chores: "upkeep", meals: "meal", display: "tv", agents: "spark", routines: "check", outings: "walk", garage: "car", ledger: "wallet", pets: "paw", profiles: "idcard", homestead: "house", meetings: "people", contacts: "book" };
const moduleInfo = (id) => modules.find((m) => m.id === id);
const moduleOn = (id) => !modulesLoaded || !!(moduleInfo(id) || {}).enabled;

// What's coming in each module, for its placeholder page.
const MODULE_PLANS = {
  outings: ["Family walks, and each person's runs and bike rides: route, distance, time and pace.", "Steps from the phone or Pebble, for anyone who wants them shown (on Home, tiles, the watch).", "Meals and macros: log what you ate by voice or from Pantry's recipes; ties into groceries and the meal plan.", "Health data from the phone (Health Connect) and Pebble: sleep, heart rate, weight, and whatever else you choose to track.", "Each part switches on or off, per person."],
  garage: ["Each car as a note: fuel, mileage and service, with graphs and averages over time.", "Tell Steward \"I just filled up the Sienna, 15 gallons, $56\" and it's logged.", "Oil changes and maintenance as reminders on the calendar. Later: OBD-II."],
  ledger: ["Fixed bills, flexible spending and paying off debt (snowball, avalanche), with interest.", "Bills on the calendar, so you see what's going out and when.", "Grocery receipts by photo or by voice; SimpleFIN or bank statements to import."],
  pets: ["Feeding and walks as routines or chores, medicines, vet visits and shots as reminders.", "One printable page for a pet sitter."],
  profiles: ["Allergies, medicines, sleep and naps, likes and dislikes, birthday, sizes.", "Parents' names, phone numbers and emergency contacts.", "One printable page for babysitters and grandparents."],
  homestead: ["Bin and recycling day on the calendar, and as a chore if you like.", "Air filters, smoke alarms, gutters and the other upkeep a home needs, owned or rented, as reminders."],
  contacts: ["The family's address book as notes, synced with phones over CardDAV.", "Linked to people, pets, cars and the home (the vet, the mechanic, the plumber)."],
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
  stewardFab();
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

// Home: a dashboard of everything at once (who's where, today, what's coming,
// chores, lists, meals, routines, chats, devices); each panel opens its page.
let homeDrawn = "";
async function renderHome() {
  $("#home-title").textContent = $("#family-name").textContent || "FamLogic";
  const people = lastMembers.filter((m) => m.role !== "dependent" && m.access !== "display");
  $("#home-sub").textContent = lastMembers.length ? `${people.length} ${people.length === 1 ? "person" : "people"}` : "Nobody has joined yet: invite your family from the Family page.";
  const on = (id) => moduleOn(id) && (moduleInfo(id) || {}).available !== false;
  const midnight = new Date(); midnight.setHours(0, 0, 0, 0);
  const get = (cond, path) => cond ? api("GET", path).catch(() => null) : Promise.resolve(null);
  const [feed, events, chores, lists, meals, routines, profiles, home] = await Promise.all([
    get(true, `/api/feed?from=${midnight.getTime()}&to=${Date.now() + 60000}`),
    get(on("calendar"), `/api/events?from=${midnight.getTime()}&to=${midnight.getTime() + 8 * 86400000}`),
    get(on("chores"), "/api/chores"), get(on("todos"), "/api/lists"), get(on("meals"), "/api/meals"), get(on("routines"), "/api/routines"),
    get(on("profiles"), "/api/profiles"), get(on("homestead"), "/api/home"),
  ]);
  if (view !== "home") return;
  const sig = JSON.stringify([lastMembers.map((m) => [m.id, describe(m), m.sosSince]), feed, events, chores, lists, meals, routines, profiles, home, chatConvs.map((c) => [c.id, c.unread, c.last && c.last.id]), places.length]);
  if (sig === homeDrawn && $("#home-dash").childElementCount) return;
  homeDrawn = sig;
  const name = (id) => (personById(id) || {}).name || "?";
  const time = (ms) => new Date(ms).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
  const panel = (href, ic, title, body, cls = "") => {
    const a = el("a", { class: "dash-panel " + cls, href });
    const h = el("div", { class: "dash-head" }); h.innerHTML = `<span class="ri">${ic ? icon(ic) : ""}</span>`;
    h.append(el("span", { class: "dash-title" }, title));
    a.append(h, body);
    return a;
  };
  const rows = (items, empty) => items.length ? el("ul", { class: "dash-list" }, ...items) : el("p", { class: "muted small" }, empty);
  const li = (main, sub, extra) => el("li", {}, el("div", { class: "grow" }, el("div", { class: "dash-main" }, main), sub ? el("div", { class: "dash-sub" }, sub) : null), extra || null);
  const out = [];
  const top = [];
  if (moduleOn("thereabouts")) {
    const sos = people.filter((m) => m.sosSince);
    out.push(panel("#/thereabouts", sos.length ? "sos" : "pin", sos.length ? "SOS" : "Who's where",
      rows(people.map((m) => el("li", {}, avatarOf(m), el("div", { class: "grow" }, el("div", { class: "dash-main" }, m.name), el("div", { class: "dash-sub" }, describe(m))))), "Nobody's sharing yet."),
      sos.length ? "sos" : "dash-people"));
  }
  if (feed) {
    const at = (e) => e.place || (places.find((p) => p.id === e.placeId) || {}).name || "a place";
    const label = { arrived: (e) => `got to ${at(e)}`, left: (e) => `left ${at(e)}`, checkin: (e) => "checked in" + (e.note ? `: ${e.note}` : ""),
      sos: () => "sent an SOS", sos_end: () => "is OK", drive_start: () => "started driving", trip: () => "finished a trip",
      chore_done: (e) => `did "${e.title}"`, reward_spent: (e) => `got "${e.title}"` };
    top.push(panel("#/thereabouts", "people", "Today", rows(feed.items.slice(0, 14).map((e) => li(`${e.name} ${(label[e.type] || (() => e.type))(e)}`, time(e.time))), "Nothing yet today.")));
  }
  if (events) out.push(panel("#/calendar", "calendar", displayName(moduleInfo("calendar")),
    rows(events.events.slice(0, 8).map((e) => li(e.title, (new Date(e.start) < new Date(midnight.getTime() + 86400000) ? "Today" : new Date(e.start).toLocaleDateString([], { weekday: "short", day: "numeric" })) + " · " + (e.allDay ? "all day" : time(e.start)) + (e.where ? " · " + e.where : ""))), "Nothing in the next week.")));
  if (chores) {
    const due = chores.chores.filter((c) => !c.finished && (!c.due || c.due <= chores.today));
    out.push(panel("#/chores", "upkeep", displayName(moduleInfo("chores")),
      el("div", {}, rows(due.slice(0, 8).map((c) => li(c.title, c.who.length ? c.who.map(name).join(", ") : "Anyone", c.stars ? el("span", { class: "badge" }, "★ " + c.stars) : null)), "Nothing due."),
        Object.keys(chores.stars || {}).length ? el("p", { class: "dash-sub" }, "Stars: " + Object.entries(chores.stars).map(([id, n]) => `${name(id)} ${n}`).join(" · ")) : null)));
  }
  if (lists) out.push(panel("#/todos", "check", displayName(moduleInfo("todos")),
    rows(lists.lists.slice(0, 6).map((l) => { const open = l.items.filter((i) => !i.done); return li(l.title, open.length ? open.slice(0, 4).map((i) => i.text).join(", ") + (open.length > 4 ? ` +${open.length - 4}` : "") : "all done", el("span", { class: "badge" }, String(open.length))); }), "No lists yet.")));
  if (routines) {
    const nowRows = Object.entries(routines.now || {}).map(([who, id]) => { const r = routines.routines.find((x) => x.id === id); return r ? li(`${name(who)}: ${r.title}`, `${r.items.filter((i) => i.done).length} of ${r.items.length} done`) : null; }).filter(Boolean);
    out.push(panel("#/routines", "check", displayName(moduleInfo("routines")), rows(nowRows, "No routine on right now.")));
  }
  if (meals) {
    const n = new Date(), today = `${n.getFullYear()}-${String(n.getMonth() + 1).padStart(2, "0")}-${String(n.getDate()).padStart(2, "0")}`;
    out.push(panel("#/meals", "meal", displayName(moduleInfo("meals")),
      rows(meals.days.filter((d) => d.date >= today).map((d) => el("li", {}, el("span", { class: "dash-day" }, new Date(d.date + "T12:00").toLocaleDateString([], { weekday: "short" })),
        el("div", { class: "grow dash-main" + (Object.keys(d.meals).length ? "" : " muted") }, Object.entries(d.meals).map(([k, v]) => `${k}: ${v}`).join(" · ") || "Not planned"))), "This week's done.")));
  }
  // Homestead: the home's upkeep coming up.
  if (home) {
    const soon = home.jobs.slice().sort((a, b) => (a.due || "").localeCompare(b.due || "")).slice(0, 6);
    out.push(panel("#/homestead", "house", displayName(moduleInfo("homestead")),
      rows(soon.map((c) => li(c.title, (c.due && c.due <= new Date().toISOString().slice(0, 10) ? "Due now" : "Next " + c.due) + " · " + EVERY_TEXT(c.every))), "Set up your home to see its upkeep here.")));
  }
  // Dossier: allergies at a glance, and whose birthday is next.
  if (profiles) {
    const today = new Date(); today.setHours(0, 0, 0, 0);
    const nextBday = (v) => { const d = new Date(v + "T12:00"); if (isNaN(d)) return null; const n = new Date(today.getFullYear(), d.getMonth(), d.getDate()); if (n < today) n.setFullYear(n.getFullYear() + 1); return n; };
    const rowsP = profiles.people.filter((p) => p.profile.Allergies || p.profile.Birthday).map((p) => {
      const b = p.profile.Birthday ? nextBday(p.profile.Birthday) : null;
      const allergy = (p.profile.Allergies || "").split("\n").map((l) => l.replace(/^[-*]\s*/, "").trim()).filter(Boolean).join(", ");
      return li(p.name, [allergy && "Allergies: " + allergy, b && "Birthday " + b.toLocaleDateString([], { day: "numeric", month: "short" })].filter(Boolean).join(" · "));
    });
    out.push(panel("#/profiles", "idcard", displayName(moduleInfo("profiles")), rows(rowsP, "No profiles filled in yet.")));
  }
  if (moduleOn("chat") && chatConvs.length) out.push(panel("#/chat", "chat", displayName(moduleInfo("chat")),
    rows(chatConvs.filter((c) => c.last).slice(0, 6).map((c) => li(c.title + (c.unread ? ` (${c.unread} new)` : ""), (c.last.text || "Photo").slice(0, 80))), "No messages yet.")));
  if (isAdmin()) {
    const quiet = lastMembers.filter((m) => m.silent || (m.issues || []).length);
    out.push(panel("#/devices", "device", "Devices", rows(quiet.map((m) => li(m.name, m.silent ? "Not heard from in a while" : (m.issues || []).join(", "))), "All phones look fine.")));
  }
  // Who's where sits beside the map, under the feed.
  const peoplePanel = out.find((p) => p.classList.contains("dash-people") || p.classList.contains("sos"));
  if (peoplePanel) { out.splice(out.indexOf(peoplePanel), 1); top.push(peoplePanel); }
  $("#home-feed").replaceChildren(...top);
  $("#home-top").hidden = !moduleOn("thereabouts") && !feed;
  $("#home-dash").replaceChildren(...out);
  homeMapDraw();
}

// The home map: everyone sharing, fitted to them, a click away from the full map.
let homeMap = null, homeTiles = "", homeMarkers = [], homeFitFor = "";
function homeMapDraw() {
  const box = $("#home-map");
  if (!box || !moduleOn("thereabouts") || typeof L === "undefined") return;
  if (!homeMap) {
    homeMap = L.map(box, { zoomControl: false, attributionControl: true, scrollWheelZoom: false }).setView([20, 0], 2);
  }
  if (appliedTiles && appliedTiles !== homeTiles) {
    homeTiles = appliedTiles;
    homeMap.eachLayer((l) => l.remove());
    L.tileLayer(appliedTiles, { maxZoom: 19, attribution: (serverTiles || {}).tileAttribution || "© OpenStreetMap contributors", referrerPolicy: "strict-origin-when-cross-origin" }).addTo(homeMap);
    homeMarkers = [];
  }
  homeMarkers.forEach((m) => m.remove());
  homeMarkers = [];
  const pts = [];
  for (const m of lastMembers) {
    if (!m.loc || m.paused) continue;
    const pos = [m.loc.lat, m.loc.lon];
    pts.push(pos);
    homeMarkers.push(L.marker(pos, { icon: L.divIcon({ className: "pin", iconSize: [34, 34], iconAnchor: [17, 17],
      html: `<div class="avatar${m.sosSince ? " sos" : ""}${m.emoji ? " emoji" : ""}" style="background:${m.color}">${m.emoji || initial(m.name)}</div>` }) }).bindTooltip(m.name).addTo(homeMap));
  }
  if (!pts.length) for (const p of places) pts.push([p.lat, p.lon]);
  // Fit to everyone once, and again only when who's on the map changes, so a
  // zoom or pan isn't undone by the 10-second refresh.
  const fitFor = lastMembers.filter((m) => m.loc && !m.paused).map((m) => m.id).join(",") || "places";
  setTimeout(() => {
    homeMap.invalidateSize();
    if (pts.length && fitFor !== homeFitFor) { homeFitFor = fitFor; homeMap.fitBounds(pts, { padding: [40, 40], maxZoom: 15 }); }
  }, 0);
}

function avatarOf(m) {
  return el("div", { class: "avatar" + (m.emoji ? " emoji" : ""), style: `background:${m.color}` }, m.emoji || initial(m.name));
}

const ACCESS_LABELS = { display: "Family display", admin: "Admin", parent: "Parent", grandparent: "Grandparent", older_child: "Older child", younger_child: "Younger child", dependent: "Doesn't use the app" };
const displayName = (m) => plainNames ? (m.plain || m.name) : m.name;

function renderFamily() {
  drawnSig.family = peopleSig();
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
  calendar: ["Datebook", "Calendar"], todos: ["Docket", "Lists"], chores: ["Upkeep", "Chores"], meals: ["Pantry", "Meals"],
  routines: ["Rhythms", "Routines"], nudges: ["Nudges", "Nudges"], ride: ["Pickup", "Pickup"], steward: ["Steward", "AI"] };
const tileName = (t) => t.startsWith("convo.") ? "Chat shortcut" : (TILE_NAMES[t] || [t, t])[plainNames ? 1 : 0];
const LEVEL_NAMES = { off: "Off", view: "View only", add: "Can add", full: "Full" };
const SIZE_NAMES = { "1x1": "1 column", "2x1": "2 columns", "3x1": "3 columns", "1x2": "1 column, tall", "2x2": "2 columns, tall", "3x2": "3 columns, tall" };
// Older sizes, shown as their new names.
const OLD_SIZES = { half: "1x1", wide: "3x1", big: "3x2" };
const accessOpen = new Set();   // whose panels are open
const accessData = new Map();   // id -> their settings from the server
const caret = '<svg class="acc-caret" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>';

// What the Access and Family pages show about people, to tell when they need redrawing.
const drawnSig = {};
function peopleSig() {
  return lastMembers.map((m) => [m.id, m.name, m.access, m.role, m.emoji, m.color, m.voice, m.username, m.hasPassword].join("|")).join(";");
}

// The family's initial ("The Smiths" → S), for the family chat's crest; null if the name has no surname.
function familyInitial() {
  const skip = new Set(["the", "our", "family", "famlogic", "household", "home", "clan", "house", "crew", "of", "and", "&"]);
  const word = ($("#family-name").textContent || "").split(/[\s\-_]+/).find((w) => w && !skip.has(w.toLowerCase()));
  const ch = word && [...word].find((x) => /\p{L}/u.test(x));
  return ch ? ch.toUpperCase() : null;
}

function renderAccess() {
  drawnSig.access = peopleSig();
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
    // Switches that belong to no one module (the feed beside Home).
    const general = d.features.filter((f) => !f.module);
    if (general.length) parts.push(el("div", { class: "acc-mod" },
      el("div", { class: "name" }, "Home"),
      el("div", { class: "acc-feats" }, ...general.map((f) => {
        const box = el("input", { type: "checkbox" });
        box.checked = !!s.features[f.id];
        box.addEventListener("change", () => saveAccess(m, body, { features: { [f.id]: box.checked } }));
        return el("label", {}, box, f.name);
      }))));
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
    opt("pictures", "Picture icons (a tiny map, a paper airplane) instead of the usual symbols")));
  // The voice their messages are read aloud in (they can change it themselves in the app too).
  const voice = el("select", { "aria-label": `${m.name}'s reading voice` },
    ...[["", "No preference"], ["female", "Female"], ["male", "Male"]].map(([v, t]) => el("option", { value: v }, t)));
  voice.value = m.voice || "";
  voice.addEventListener("change", async () => {
    try { await api("PATCH", `/api/admin/members/${encodeURIComponent(m.id)}`, { voice: voice.value }); m.voice = voice.value; }
    catch (err) { alert(err.message); voice.value = m.voice || ""; }
  });
  parts.push(el("h3", {}, "Reading voice"),
    el("p", { class: "acc-note" }, "When someone has their messages read aloud, they hear this voice."), voice);
  // Home screen: drag to order, tick to show, choose a size
  parts.push(el("h3", {}, "Home screen"),
    el("p", { class: "acc-note" }, "Drag ☰ to reorder, tick what to show and pick a size (in columns: phones show 2 to 5 across, as each person chooses). Nothing ticked: the app's default."),
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
  // "2x1@0" keeps its column (set in the app) but the menu shows the size part.
  const parse = (t) => { const [id, size] = t.split(":"); const sz = size || (id === "map" || id === "chat" ? "wide" : "half"); const [dims, col] = (OLD_SIZES[sz] || sz).split("@"); return { id, size: dims, col }; };
  let on = d.home.map(parse);
  const all = () => on.concat(d.tiles.filter((t) => !on.some((x) => x.id === t)).map((t) => ({ ...parse(t), off: true })));
  const commit = () => save(on.map((t) => `${t.id}:${t.size}${t.col !== undefined ? "@" + t.col : ""}`));
  const list = el("ul", { class: "tiles" });
  let dragging = null;
  const draw = () => list.replaceChildren(...all().map((t) => {
    const box = el("input", { type: "checkbox", "aria-label": `Show ${tileName(t.id)}` });
    box.checked = !t.off;
    box.addEventListener("change", () => {
      on = box.checked ? on.concat({ id: t.id, size: t.size }) : on.filter((x) => x.id !== t.id);
      commit();
    });
    const size = el("select", { "aria-label": `${tileName(t.id)} size` }, ...Object.entries(SIZE_NAMES).map(([k, v]) => el("option", { value: k }, v)));
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
  const kindName = (k) => ({ tablet: "Tablet", display: "Family display", tv: "TV" }[k] || "Phone");
  // A family display is a thing, not a person: "Living room TV", not "Living room TV's phone".
  const deviceTitle = (m, d) => m.access === "display" ? m.name : `${m.name}'s ${kindName(d.kind)}`;
  const rows = [];
  for (const m of lastMembers) {
    const devs = m.devices || [];
    if (!devs.length) {
      if (m.role !== "dependent") rows.push(el("li", {}, avatarOf(m), el("div", { class: "grow" }, el("div", { class: "title" }, m.name), el("div", { class: "sub" }, "Not signed in anywhere"))));
      continue;
    }
    devs.forEach((d, i) => {
      const out = el("button", { type: "button", class: "secondary" }, "Sign out");
      out.addEventListener("click", async () => {
        if (!confirm(m.access === "display" ? `Sign out ${m.name}? Pair it again with a new display code.` : `Sign out ${m.name}'s ${kindName(d.kind).toLowerCase()}? They can sign in again with their password (or a new join code).`)) return;
        try { await api("DELETE", `/api/admin/members/${m.id}/devices/${d.id}`); await refresh(); } catch (err) { alert(err.message); }
      });
      rows.push(el("li", {}, i === 0 ? avatarOf(m) : el("div", { class: "avatar-space" }),
        el("div", { class: "grow" },
          el("div", { class: "title" }, deviceTitle(m, d)),
          el("div", { class: "sub" }, [m.access === "display" ? (d.kind === "tv" ? "TV" : "Family display") : null, d.app ? "app " + d.app : null, d.lastSeen ? "last seen " + ago(d.lastSeen) : "not seen yet", "signed in " + new Date(d.created).toLocaleDateString()].filter(Boolean).join(" · ")),
          i === 0 && (m.app || m.lastContact) ? health(m) : null),
        out));
    });
  }
  list.replaceChildren(...rows);
  if (!rows.length) list.append(el("li", { class: "muted" }, "No devices yet."));
}

// Settings: Modules, Server, AI & API.
let settingsTab = "modules";
document.querySelectorAll("#settings-tabs [data-sub]").forEach((b) => b.addEventListener("click", () => {
  settingsTab = b.dataset.sub;
  if (settingsTab === "agents") $("#agents-panel").replaceChildren(); // fresh each time it's opened
  renderSettings();
}));

function moduleRows(target, checked) {
  target.replaceChildren(...modules.map((m) => {
    const box = el("input", { type: "checkbox", class: "switch", role: "switch", "data-module": m.id });
    box.checked = checked(m);
    return el("li", {}, el("label", {},
      box,
      el("div", { class: "grow" },
        el("div", { class: "title" }, m.name, m.available ? null : el("span", { class: "badge" }, "coming soon")),
        el("div", { class: "sub" }, m.description))));
  }));
}
// A few questions about the family's life, each suggesting modules (first-run setup,
// and Settings → Modules → Help me choose). The family still decides.
const MODULE_QUESTIONS = [
  ["See where everyone is, and SOS?", ["thereabouts"]],
  ["Family chat?", ["chat"]],
  ["A shared calendar (appointments, activities, co-op or school)?", ["calendar"]],
  ["One-off jobs, errands and projects to keep track of, and shopping lists?", ["todos"]],
  ["Chores, maybe with stars for the children?", ["chores"]],
  ["Daily routines (mornings, bedtime, a homeschool day)?", ["routines"]],
  ["Plan meals and keep recipes?", ["meals"]],
  ["An AI helper you can just tell things to?", ["agents"]],
  ["Pets?", ["pets"]],
  ["A car, or a few?", ["garage"]],
  ["A home to look after, owned or rented (filters, bin day)?", ["homestead"]],
  ["Help with bills, the budget or grocery spending?", ["ledger"]],
  ["Walks, runs, steps or health tracking?", ["outings"]],
  ["Allergies, medicines and emergency contacts in one place?", ["profiles"]],
  ["A shared address book?", ["contacts"]],
];
function moduleQuestions(target, isOn) {
  target.replaceChildren(...MODULE_QUESTIONS.map(([q, ids]) => {
    const box = el("input", { type: "checkbox", class: "switch", role: "switch", "data-ids": ids.join(",") });
    box.checked = ids.some((id) => isOn(id));
    return el("li", {}, el("label", { class: "switch-label" }, box, q));
  }));
}
// The modules the answers suggest: {id: true/false} for every module a question covers.
const suggestedModules = (target) => Object.fromEntries([...target.querySelectorAll("input[data-ids]")].flatMap((b) => b.dataset.ids.split(",").map((id) => [id, b.checked])));
let modulesDirty = false; // ticked by hand (or by suggestions) and not saved yet: the refresh leaves the list alone

const chosenModules = (target) => Object.fromEntries([...target.querySelectorAll("input[data-module]")].map((b) => [b.dataset.module, b.checked]));

function renderSettings() {
  document.querySelectorAll("#settings-tabs [data-sub]").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.sub === settingsTab)));
  document.querySelectorAll("[data-sub-panel]").forEach((p) => { p.hidden = p.dataset.subPanel !== settingsTab; });
  if (settingsTab === "modules" && !modulesDirty && !$("#module-list").contains(document.activeElement)) moduleRows($("#module-list"), (m) => m.enabled);
  // Drawn once per visit (not by the 10-second refresh, which would wipe a key that's being shown).
  if (settingsTab === "agents" && !$("#agents-panel").childElementCount) renderKeys();
  if (settingsTab === "server") {
    const facts = [["Family", $("#family-name").textContent], ["Server", $("#server-version").textContent.replace(/^server /, "")],
      ["Modules on", modules.filter((m) => m.enabled).map((m) => m.name + (m.available ? "" : " (soon)")).join(", ") || "none"],
      ["Address search", serverFeatures.includes("geocode") ? "on" : "off"], ["Quiet-phone alerts", serverFeatures.includes("silence") ? "on" : "off"]];
    $("#server-facts").replaceChildren(...facts.flatMap(([k, v]) => [el("dt", {}, k), el("dd", {}, v)]));
    renderVault();
  }
}

// Where the family's Markdown notes are, and whether FamLogic can write there.
async function renderVault() {
  let v;
  try { v = await api("GET", "/api/admin/vault"); } catch { return; }
  const ok = v.writable;
  const facts = [["Folder (in the container)", v.path || "not set up"],
    ["Status", ok ? `working, ${v.notes} notes` : "can't write there"],
    ["Files belong to user", v.user]];
  if (v.error) facts.push(["Problem", v.error]);
  $("#vault-facts").replaceChildren(...facts.flatMap(([k, val]) => [el("dt", {}, k), el("dd", {}, val)]));
  $("#vault-help").replaceChildren(
    "Lists, chores, meals and people as Markdown files, for Obsidian, Syncthing and AI agents. To keep them in a folder on the host, mount it at ",
    el("code", {}, "/data/vault"), " in docker-compose.yml and set ", el("code", {}, "PUID"), "/", el("code", {}, "PGID"),
    " to your user (see the README). How the notes are written: ", el("a", { href: "/api/v1/vault/guide", target: "_blank" }, "the formatting guide"), ".");
}

$("#module-list").addEventListener("change", () => { modulesDirty = true; });
$("#modules-help").addEventListener("click", () => {
  const box = $("#modules-quiz");
  box.hidden = !box.hidden;
  if (!box.hidden) moduleQuestions($("#modules-quiz-list"), (id) => (moduleInfo(id) || {}).enabled);
});
$("#modules-suggest").addEventListener("click", () => {
  const want = suggestedModules($("#modules-quiz-list"));
  $("#module-list").querySelectorAll("input[data-module]").forEach((b) => { if (b.dataset.module in want) b.checked = want[b.dataset.module]; });
  modulesDirty = true;
  $("#modules-quiz").hidden = true;
  $("#modules-msg").textContent = "Ticked to match your answers. Check them, then Save.";
});
$("#modules-save").addEventListener("click", async () => {
  try {
    const res = await api("PUT", "/api/admin/modules", { enabled: chosenModules($("#module-list")), setupDone: true });
    modulesDirty = false;
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
  // Switching between module pages: start from an empty page (and forget what the
  // last one drew), so one page's content never lingers on another's.
  if (box.dataset.module !== id) {
    box.dataset.module = id;
    box.replaceChildren();
    upkeepDrawn = listsDrawn = calDrawn = profilesDrawn = homeDrawnH = corkDrawn = "";
  }
  if (id === "chores" && m.available) { box.classList.remove("soon"); renderUpkeep(box); return; }
  if (id === "todos" && m.available) { box.classList.remove("soon"); renderLists(box); return; }
  if (id === "calendar" && m.available) { box.classList.remove("soon"); renderCalendar(box); return; }
  if (id === "routines" && m.available) { box.classList.remove("soon"); renderRoutines(box); return; }
  if (id === "meals" && m.available) { box.classList.remove("soon"); renderMeals(box); return; }
  if (id === "profiles" && m.available) { box.classList.remove("soon"); renderProfiles(box); return; }
  if (id === "homestead" && m.available) { box.classList.remove("soon"); renderHomestead(box); return; }
  if (id === "meetings" && m.available) { box.classList.remove("soon"); renderMeetings(box); return; }
  if (id === "display" && m.available) { box.classList.remove("soon"); renderCorkboard(box); return; }
  box.classList.add("soon");
  box.replaceChildren(
    el("p", {}, el("strong", {}, "Coming soon. "), "It's switched on for your family, so it will appear here and in the app when it arrives."),
    serverFeatures.includes("steward") ? el("p", { class: "muted small" }, "You can start now: tell Steward (the button in the corner) and it keeps the notes in the vault, ready for when this arrives.") : null,
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

// Guided setup: a few questions instead of settings, the first time (and again
// from Settings → Server → Run setup again). Every step can be changed later.
const setupDialog = $("#setup-dialog");
let setupStep = 0;
const setupMade = []; // invites made along the way, shown at the end
let setupSuggested = null; // modules the first questions suggested
const SETUP_STEPS = [
  { title: "Welcome to FamLogic", render(box) {
      box.append(el("p", {}, "A few questions about your family's life, so FamLogic can suggest what to switch on. You choose on the next page, and can change it any time."),
        el("ul", { class: "list quiz", id: "setup-quiz" }));
      moduleQuestions($("#setup-quiz"), (id) => setupSuggested ? !!setupSuggested[id] : !!(moduleInfo(id) || {}).enabled);
    },
    async save() { setupSuggested = suggestedModules($("#setup-quiz")); } },
  { title: "What to switch on", render(box) {
      box.append(el("p", {}, "Suggested from your answers. Switch on what your family wants; modules marked coming soon can be chosen now."), el("ul", { class: "list modules", id: "setup-modules" }));
      moduleRows($("#setup-modules"), (m) => setupSuggested && m.id in setupSuggested ? setupSuggested[m.id] : m.enabled);
    },
    async save() { setModules(await api("PUT", "/api/admin/modules", { enabled: chosenModules($("#setup-modules")), setupDone: true })); } },
  { title: "About your family", render(box) {
      box.append(el("label", {}, "Your family's name", el("input", { id: "setup-family", value: $("#family-name").textContent || "", maxlength: "40", placeholder: "e.g. The Bakers" })),
        el("p", { class: "muted small" }, "A parent PIN keeps settings closed on children's phones and family displays; you type it to open them."),
        el("label", {}, "Parent PIN (4 to 8 digits, optional)", el("input", { id: "setup-pin", inputmode: "numeric", maxlength: "8", placeholder: "Leave empty to set later" })));
    },
    async save() {
      const name = $("#setup-family").value.trim();
      if (name) { await api("PUT", "/api/admin/family", { name }); $("#family-name").textContent = name; }
      const pin = $("#setup-pin").value.trim();
      if (pin) await api("PUT", "/api/admin/pin", { pin });
    } },
  { title: "Who's in your family?", render(box) {
      const rows = el("div", { id: "setup-people", class: "setup-people" });
      const addRow = () => rows.append(el("div", { class: "row" }, el("input", { placeholder: "Name", maxlength: "40" }),
        el("select", {}, ...[["parent", "A parent"], ["grandparent", "A grandparent"], ["older_child", "A child who reads"], ["younger_child", "A younger child"], ["dependent", "Doesn't use the app (a baby)"]].map(([v, t]) => el("option", { value: v }, t)))));
      addRow(); addRow();
      const more = el("button", { type: "button", class: "link" }, "Add another person");
      more.addEventListener("click", addRow);
      box.append(el("p", { class: "muted small" }, "Leave a row empty to skip it. You can add people any time on the Family page."), rows, more);
    },
    async save() {
      for (const r of $("#setup-people").children) {
        const name = r.querySelector("input").value.trim();
        const access = r.querySelector("select").value;
        if (!name) continue;
        if (access === "dependent") { await api("POST", "/api/admin/dependents", { name }); continue; }
        setupMade.push(await api("POST", "/api/admin/invites", { name, access }));
      }
    } },
  { title: "Places", render(box) {
      box.append(el("p", { class: "muted small" }, "Fences tell you when someone arrives or leaves. Add Home now, and one more place you go often if you like (a co-op, lessons, work, Grandma's). You can draw more on the map later."),
        el("label", {}, "Home address", el("input", { id: "setup-home", placeholder: "Street, town" })),
        el("div", { class: "row" }, el("label", {}, "Another place (optional)", el("input", { id: "setup-other-name", placeholder: "e.g. Co-op", maxlength: "40" })),
          el("label", { class: "grow" }, "Its address", el("input", { id: "setup-school", placeholder: "Street, town" }))));
      if (!serverFeatures.includes("geocode")) box.append(el("p", { class: "banner" }, "Address search is off on this server: add fences on the map instead."));
    },
    async save() {
      for (const [id, name] of [["setup-home", "Home"], ["setup-school", ($("#setup-other-name") || {}).value?.trim() || "Place"]]) {
        const q = ($("#" + id) || {}).value?.trim();
        if (!q || !serverFeatures.includes("geocode")) continue;
        const res = await api("GET", "/api/admin/geocode?q=" + encodeURIComponent(q));
        const hit = (res.results || res)[0];
        if (!hit) { alert(`Couldn't find "${q}". Add ${name} on the map instead.`); continue; }
        await api("POST", "/api/admin/places", { circleId: (circles[0] || {}).id, name, lat: hit.lat, lon: hit.lon, radius: name === "Home" ? 120 : 200 });
      }
    } },
  { title: "Steward (optional)", render(box) {
      box.append(el("p", {}, "Steward is the family's AI helper. With it on, a small button sits in the corner of every page, in the app and here: tell it what to add, change or remove (\"Sam has a dentist visit Friday at 3\", \"add eggs to Shopping\", \"save this recipe\") and it does it for you."),
        el("p", { class: "muted small" }, "It needs an AI model: your own (Ollama, LocalAI, llama.cpp) or a cloud one with an OpenAI-compatible address. Skip this to set it up later in Settings → AI & API."));
      stewardSettings().then((f) => { f.id = "setup-steward"; box.append(f); });
    },
    async save() {
      // The walkthrough saves as it goes; here, Steward's module comes on if it's set up.
      let d;
      try { d = await api("GET", "/api/admin/steward"); } catch (_) { return; }
      if (d.config.enabled && d.config.model && !moduleOn("agents")) {
        const on = Object.fromEntries(modules.filter((m) => m.enabled).map((m) => [m.id, true]));
        on.agents = true;
        setModules(await api("PUT", "/api/admin/modules", { enabled: on, setupDone: true }));
      }
    } },
  { title: "Getting phones connected", render(box) {
      if (!setupMade.length) { box.append(el("p", {}, "When you invite people on the Family page, each gets a link or code for their phone.")); return; }
      box.append(el("p", {}, "Open each link on that person's phone (or show the code to scan). Younger children need no password."),
        el("ul", { class: "list" }, ...setupMade.map((inv) => {
          const show = el("button", { type: "button", class: "secondary" }, "Show code");
          show.addEventListener("click", () => showLink(inv, `For ${inv.name}`, "Open this on their phone, or scan the code."));
          return el("li", {}, el("div", { class: "grow" }, el("div", { class: "title" }, inv.name), el("div", { class: "sub" }, inv.link)), show);
        })));
    }, async save() {} },
  { title: "You're set", render(box) {
      box.append(el("ul", { class: "plain" },
        el("li", {}, el("strong", {}, "Family: "), "people, roles and invites."),
        el("li", {}, el("strong", {}, "Access & Control: "), "what each child can use, their home screen, the parent PIN."),
        el("li", {}, el("strong", {}, "Settings: "), "modules, backups, AI and API, and Run setup again.")));
    }, async save() {} },
];
function renderSetup() {
  const st = SETUP_STEPS[setupStep];
  const box = $("#setup-step");
  box.replaceChildren(el("h2", {}, st.title));
  st.render(box);
  $("#setup-step-count").textContent = `Step ${setupStep + 1} of ${SETUP_STEPS.length}`;
  $("#setup-back").hidden = setupStep === 0;
  $("#setup-later").hidden = setupStep === 0 || setupStep === SETUP_STEPS.length - 1;
  $("#setup-next").textContent = setupStep === SETUP_STEPS.length - 1 ? "Done" : "Next";
}
function openSetup() {
  setupStep = 0;
  setupMade.length = 0;
  renderSetup();
  if (!setupDialog.open) setupDialog.showModal();
}
$("#setup-back").addEventListener("click", () => { setupStep = Math.max(0, setupStep - 1); renderSetup(); });
$("#setup-later").addEventListener("click", () => { setupDialog.close(); refresh(); });
$("#setup-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  try {
    await SETUP_STEPS[setupStep].save();
    if (setupStep === SETUP_STEPS.length - 1) { setupDialog.close(); showView(view === "home" ? "home" : view); refresh(); return; }
    setupStep++;
    renderSetup();
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
      el("div", { class: "avatar" + (c.emoji ? " emoji" : "") + (!c.emoji && c.kind === "family" ? " crest" : ""), style: `background:${c.color || (c.kind === "direct" ? (personById(c.members.find((id) => id !== (me.member || {}).id)) || {}).color || "var(--accent)" : "var(--accent)")}` },
        c.emoji || (c.kind === "family" ? crestLetter(familyInitial() || "★") : (c.title || "?").trim().charAt(0).toUpperCase())),
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
  refreshTimer = setInterval(() => { if (!document.hidden) refresh(true); }, REFRESH_MS);
}

api("GET", "/api/admin/session").then(start).catch(() => {});


// ---- Upkeep (chores): the same chores as the app, kept as notes in the vault ----

const REPEATS = [["", "Once"], ["day", "Every day"], ["weekdays", "Weekdays"], ["week", "Every week"], ["2 weeks", "Every 2 weeks"], ["month", "Every month"]];
const repeatText = (e) => (REPEATS.find(([k]) => k === e) || [e, e ? "Every " + e : "Once"])[1];
let upkeepDrawn = "";
let upkeepEdit = null; // the chore being changed in the form, if any
const WHENS = [["", "No reminder"], ["morning", "Morning"], ["after school", "Afternoon"], ["evening", "Evening"], ["bedtime", "Bedtime"]];

async function renderUpkeep(box, force = false) {
  // Don't redraw under someone typing a new chore.
  if (!force && box.contains(document.activeElement) && document.activeElement.matches("input, select, textarea")) return;
  let data, rewards = { rewards: [], asks: [], canEdit: false };
  try { data = await api("GET", "/api/chores"); }
  catch (err) { box.replaceChildren(el("p", { class: "muted" }, err.message)); return; }
  try { rewards = await api("GET", "/api/rewards"); } catch (_) {}
  if (box.dataset.module !== "chores") return; // the page changed while loading
  // Redraw only when something changed (the 10-second refresh calls this too).
  const sig = JSON.stringify([data, rewards, upkeepEdit && upkeepEdit.id]);
  if (!force && sig === upkeepDrawn && box.childElementCount) return;
  upkeepDrawn = sig;
  const people = lastMembers.filter((m) => m.role !== "dependent");
  const name = (id) => (people.find((m) => m.id === id) || {}).name || "?";
  const today = data.today;
  const act = async (method, path, body) => {
    try { await api(method, path, body); renderUpkeep(box, true); } catch (err) { alert(err.message); }
  };

  // Add a chore (or change the one picked with "Change").
  const ed = upkeepEdit;
  const title = el("input", { placeholder: "e.g. Unload the dishwasher", maxlength: "80", value: ed ? ed.title : "" });
  // Who it's for: a toggle button per person (lit when chosen), like the app.
  const who = el("div", { class: "toggles" }, ...people.map((m) => {
    const b = el("button", { type: "button", class: "toggle", "data-id": m.id, "aria-pressed": ed && ed.who.includes(m.id) ? "true" : "false" }, avatarOf(m), el("span", {}, m.name));
    b.addEventListener("click", () => b.setAttribute("aria-pressed", b.getAttribute("aria-pressed") === "true" ? "false" : "true"));
    return b;
  }));
  const every = el("select", {}, ...REPEATS.map(([k, v]) => el("option", { value: k }, v)));
  const when = el("select", {}, ...WHENS.map(([k, v]) => el("option", { value: k }, v)));
  if (ed) { every.value = ed.every || ""; when.value = WHENS.some(([k]) => k === ed.when) ? ed.when : ""; }
  const due = el("input", { type: "date", value: ed && ed.due ? ed.due : today });
  const stars = el("input", { type: "number", min: "0", max: "100", value: ed ? String(ed.stars || 0) : "0", style: "width:4.5em" });
  const nudge = (n) => { const b = el("button", { type: "button", class: "secondary small" }, (n > 0 ? "+" : "−") + Math.abs(n));
    b.addEventListener("click", () => { stars.value = Math.max(0, Math.min(100, (Number(stars.value) || 0) + n)); }); return b; };
  const starPicker = el("div", { class: "stars-pick" }, el("span", {}, "Stars"), nudge(-10), nudge(-5), nudge(-1), stars, nudge(1), nudge(5), nudge(10));
  const notes = el("textarea", { rows: "2", placeholder: "Details (optional)", maxlength: "2000" });
  if (ed) notes.value = ed.notes || "";
  const cancel = el("button", { type: "button", class: "secondary" }, "Cancel");
  cancel.addEventListener("click", () => { upkeepEdit = null; renderUpkeep(box, true); });
  const add = el("form", { class: "upkeep-add" },
    el("label", {}, "Chore ", title), el("div", { class: "muted small" }, "For"), who,
    el("div", { class: "row" }, el("label", {}, "Repeats ", every), el("label", {}, "From ", due), el("label", {}, "Reminder ", when)), starPicker,
    notes, el("div", { class: "row" }, el("button", { type: "submit" }, ed ? "Save changes" : "Add chore"), ed ? cancel : null));
  add.addEventListener("submit", (ev) => {
    ev.preventDefault();
    if (!title.value.trim()) return;
    const body = { title: title.value.trim(), who: [...who.querySelectorAll('[aria-pressed="true"]')].map((b) => b.dataset.id),
      every: every.value, due: due.value, when: when.value, stars: Number(stars.value) || 0, notes: notes.value };
    if (ed) { upkeepEdit = null; act("PATCH", `/api/chores/${ed.id}`, body); } else act("POST", "/api/chores", body);
  });

  // The chores: due now, coming up, done.
  const row = (c) => {
    const late = c.due && c.due < today;
    const faces = c.who.length ? c.who.map((id) => { const m = people.find((p) => p.id === id); return m ? avatarOf(m) : null; }) : [el("span", { class: "muted small" }, "Anyone")];
    const done = el("button", { type: "button", class: "tonal", title: "Ticked off" }, "✓ Done");
    done.addEventListener("click", () => {
      // Shared: who did it? A button per person, in place of Done.
      if (c.who.length > 1) {
        done.replaceWith(el("div", { class: "toggles" }, el("span", { class: "muted small" }, "Who did it?"), ...c.who.map((id) => {
          const m = people.find((p) => p.id === id);
          const b = el("button", { type: "button", class: "toggle" }, m ? avatarOf(m) : null, el("span", {}, name(id)));
          b.addEventListener("click", () => act("POST", `/api/chores/${c.id}/done`, { by: id }));
          return b;
        })));
        return;
      }
      act("POST", `/api/chores/${c.id}/done`, c.who[0] ? { by: c.who[0] } : {});
    });
    const undo = el("button", { type: "button", class: "link", title: "It wasn't really done" }, "Undo");
    undo.addEventListener("click", () => act("POST", `/api/chores/${c.id}/undo`, {}));
    const change = el("button", { type: "button", class: "link" }, "Change");
    change.addEventListener("click", () => { upkeepEdit = c; renderUpkeep(box, true).then(() => box.querySelector(".upkeep-add input")?.focus()); });
    const retire = el("button", { type: "button", class: "link" }, "Retire");
    retire.addEventListener("click", () => { if (confirm(`Retire "${c.title}"? Its note stays in the vault.`)) act("DELETE", `/api/chores/${c.id}`); });
    const last = c.done[0];
    return el("li", { class: "chore" + (late ? " late" : "") },
      el("div", { class: "faces" }, ...faces),
      el("div", { class: "grow" },
        el("div", { class: "title" }, c.title, c.stars ? el("span", { class: "badge" }, "★ " + c.stars) : null),
        el("div", { class: "sub" }, [repeatText(c.every), c.finished ? "done" : (c.due ? (late ? "late: " : "due ") + c.due : ""),
          last ? `last done ${last.date}${last.by ? " by " + name(last.by) : ""}` : ""].filter(Boolean).join(" · ")),
        c.notes ? el("div", { class: "muted small" }, c.notes) : null),
      c.finished ? null : done, last ? undo : null, data.canEdit ? change : null, data.canEdit ? retire : null);
  };
  const now = data.chores.filter((c) => !c.finished && (!c.due || c.due <= today));
  const later = data.chores.filter((c) => !c.finished && c.due && c.due > today);
  const finished = data.chores.filter((c) => c.finished);
  const section = (h, list, empty) => [el("h2", {}, h), list.length ? el("ul", { class: "list" }, ...list.map(row)) : el("p", { class: "muted" }, empty)];

  // Stars, for the children who have any (or could).
  const kids = people.filter((m) => m.role === "child");
  const starRow = (m) => {
    const n = data.stars[m.id] || 0;
    const btn = (label, body) => { const b = el("button", { type: "button", class: "secondary" }, label); b.addEventListener("click", () => act("POST", `/api/members/${m.id}/stars`, body)); return b; };
    return el("li", {}, avatarOf(m), el("div", { class: "grow" }, el("div", { class: "title" }, m.name), el("div", { class: "sub" }, `★ ${n}`)),
      el("div", { class: "stars-pick" }, btn("−10", { add: -10 }), btn("−5", { add: -5 }), btn("−1", { add: -1 }),
        btn("+1", { add: 1 }), btn("+5", { add: 5 }), btn("+10", { add: 10 }), btn("Spend all", { set: 0 })));
  };

  // Rewards: a child asks, a parent says yes (the stars come off) or no.
  const rewardBox = [];
  const pending = rewards.asks.filter((a) => a.status === "asked");
  if (pending.length) rewardBox.push(el("ul", { class: "list" }, ...pending.map((a) => {
    const m = people.find((p) => p.id === a.memberId);
    const yes = el("button", { type: "button" }, "Yes");
    yes.addEventListener("click", () => act("POST", `/api/rewards/asks/${a.id}/approve`));
    const no = el("button", { type: "button", class: "secondary" }, "No");
    no.addEventListener("click", () => act("POST", `/api/rewards/asks/${a.id}/decline`));
    return el("li", {}, m ? avatarOf(m) : null, el("div", { class: "grow" },
      el("div", { class: "title" }, `${m ? m.name : "Someone"} asked for ${a.title}`),
      el("div", { class: "sub" }, `★ ${a.cost} · has ★ ${data.stars[a.memberId] || 0}`)), no, yes);
  })));
  if (rewards.rewards.length) rewardBox.push(el("ul", { class: "list" }, ...rewards.rewards.map((r) => {
    const rm = el("button", { type: "button", class: "link" }, "Remove");
    rm.addEventListener("click", () => { if (confirm(`Remove "${r.title}"?`)) act("DELETE", `/api/rewards/${r.id}`); });
    return el("li", {}, el("div", { class: "grow" }, el("div", { class: "title" }, r.title)), el("span", { class: "badge" }, "★ " + r.cost), rewards.canEdit ? rm : null);
  })));
  if (rewards.canEdit) {
    const rt = el("input", { placeholder: "e.g. 30 minutes of screen time", maxlength: "60" });
    const rc = el("input", { type: "number", min: "1", max: "1000", value: "10", style: "width:5em" });
    const rf = el("form", { class: "row upkeep-reward" }, el("label", { class: "grow" }, "Reward ", rt), el("label", {}, "★ ", rc), el("button", { type: "submit" }, "Add reward"));
    rf.addEventListener("submit", (ev) => { ev.preventDefault(); if (rt.value.trim()) act("POST", "/api/rewards", { title: rt.value.trim(), cost: Number(rc.value) || 1 }); });
    rewardBox.push(rf);
  }

  box.replaceChildren(
    ...section("Due now", now, "Nothing due."),
    ...section("Coming up", later, "Nothing coming up."),
    ...(finished.length ? section("Done", finished, "") : []),
    ...(data.canEdit ? [el("h2", {}, ed ? `Change "${ed.title}"` : "Add a chore"), add] : []),
    ...(kids.length ? [el("h2", {}, "Stars"), el("ul", { class: "list" }, ...kids.map(starRow))] : []),
    ...(rewardBox.length ? [el("h2", {}, "Rewards"), ...rewardBox] : []),
    el("p", { class: "muted small" }, "Chores are Markdown notes in the vault's Chores folder: open it in Obsidian, or let your AI agent add and tick them off (Settings → AI & API)."));
}

// ---- Settings → AI: Steward's model (built in, at home, or in the cloud, with a
// fallback), its test, and keys for outside agents (Hermes, over MCP) ----

// A short note at the bottom of the page that fades away ("Saved").
function toast(text) {
  const t = el("div", { class: "toast", role: "status" }, text);
  document.body.append(t);
  setTimeout(() => t.classList.add("gone"), 1800);
  setTimeout(() => t.remove(), 2400);
}

const CLOUD_PRESETS = [
  ["openrouter", "OpenRouter (many models, one key)", "https://openrouter.ai/api/v1", "qwen/qwen3.5-9b"],
  ["openai", "OpenAI", "https://api.openai.com/v1", "gpt-5-mini"],
  ["anthropic", "Anthropic", "https://api.anthropic.com/v1", "claude-haiku-5-5"],
  ["other", "Another (any OpenAI-compatible address)", "", ""],
];
const secs = (s) => s < 1 ? "under a second" : s < 60 ? `about ${Math.round(s)} s` : `about ${Math.round(s / 60)} min`;
const gb = (n) => `${n.toFixed(n < 10 ? 1 : 0)} GB`;

async function renderKeys() {
  const box = $("#agents-panel");
  if (!box) return;
  box.replaceChildren(await stewardSettings(true));
}

// The whole AI page (full) or, in first-time setup, just the model part.
async function stewardSettings(full = false) {
  const wrap = el("div", { class: "ai" });
  const draw = async () => {
    let d;
    try { d = await api("GET", "/api/admin/steward"); } catch (err) { wrap.replaceChildren(el("p", {}, err.message)); return; }
    wrap.replaceChildren(...aiPage(d, full, draw).filter(Boolean));
  };
  await draw();
  return wrap;
}

function aiSwitch(checked, text, sub) {
  const input = el("input", { type: "checkbox", class: "switch", role: "switch", ...(checked ? { checked: "" } : {}) });
  return [input, el("label", { class: "ai-switch" }, el("span", { class: "grow" }, el("span", { class: "title" }, text), sub ? el("span", { class: "sub" }, sub) : null), input)];
}

function aiPage(d, full, redraw) {
  const c = d.config;
  const save = async (patch, msg) => {
    try { await api("PUT", "/api/admin/steward", { ...c, ...patch, key: patch.key || "" }); if (msg) toast(msg); redraw(); }
    catch (err) { alert(err.message); }
  };
  const parts = [];
  if (full) {
    const on = (moduleInfo("agents") || {}).enabled;
    if (!on) parts.push(el("p", { class: "banner" }, "Switch on ", el("strong", {}, "Steward"), " in Modules for any of this to work."));
  }
  // 1. On or off, and who.
  const [en, enRow] = aiSwitch(c.enabled, "Let the family use Steward", "The round button in the corner of each page, in the app and here.");
  const [ev, evRow] = aiSwitch(c.everyone, "Children too", "They can only do what they could with a tap.");
  en.addEventListener("change", () => save({ enabled: en.checked }));
  ev.addEventListener("change", () => save({ everyone: ev.checked }));
  parts.push(el("section", { class: "ai-card" }, el("h2", {}, "Steward"), enRow, evRow));

  // 2. Where it thinks.
  const where = c.source || (c.url ? (/127\.0\.0\.1|localhost|:11434|:8080/.test(c.url) ? "server" : "cloud") : "builtin");
  let shown = where;
  const choices = [["builtin", "On this server", "Built in. Nothing else to set up; speed depends on this computer."],
    ["server", "On a computer at home", "A model server on a stronger computer (a gaming PC's graphics card): Ollama, llama.cpp, LM Studio."],
    ["cloud", "In the cloud", "A provider's model. Quickest and cleverest; needs an account and sends requests out."]];
  const pane = el("div", { class: "ai-pane" });
  const grid = el("div", { class: "ai-choices", role: "radiogroup" }, ...choices.map(([id, title, sub]) => {
    const b = el("button", { type: "button", class: "ai-choice", role: "radio", "aria-checked": String(id === shown) },
      el("span", { class: "title" }, title), el("span", { class: "sub" }, sub), id === where && c.model ? el("span", { class: "chip" }, "In use") : null);
    b.addEventListener("click", () => { shown = id; grid.querySelectorAll(".ai-choice").forEach((x) => x.setAttribute("aria-checked", String(x === b))); pane.replaceChildren(...paneFor(id)); });
    return b;
  }));
  const paneFor = (id) => id === "builtin" ? builtinPane(d, redraw) : id === "server" ? serverPane(d, save) : cloudPane(d, save, "main");
  pane.replaceChildren(...paneFor(shown));
  parts.push(el("section", { class: "ai-card" }, el("h2", {}, "Where Steward thinks"),
    c.model ? el("p", { class: "muted small" }, "Now: ", el("strong", {}, c.model), " ", { builtin: "(built in)", server: "(on a model server)", cloud: "(in the cloud)" }[where] || "") : el("p", { class: "muted small" }, "Not chosen yet."),
    grid, pane));
  if (!full) return parts;

  // 3. Fallback.
  parts.push(fallbackCard(d, save));
  // 4. Test.
  parts.push(testCard(d, redraw));
  // 5. Advanced.
  const mode = el("div", { class: "seg", role: "radiogroup" }, ...[["skills", "Small prompt (skills)"], ["full", "Every action typed"]].map(([v, t]) => {
    const r = el("input", { type: "radio", name: "ai-mode", value: v, ...((c.mode || "skills") === v ? { checked: "" } : {}) });
    r.addEventListener("change", () => save({ mode: v }, "Saved"));
    return el("label", {}, r, t);
  }));
  parts.push(el("section", { class: "ai-card" }, el("h2", {}, "How Steward talks to the model"),
    el("p", { class: "muted small" }, "Small prompt: about 900 tokens, with the skill it needs opened as it goes. Best for local models, and cheaper for cloud ones. Every action typed: about 4,000 tokens each time; some big cloud models are a little surer with it."), mode));
  // 6. Agents.
  parts.push(agentsCard(d));
  return parts;
}

function builtinPane(d, redraw) {
  const sp = d.spec, st = d.brain || {};
  const out = [];
  const facts = [sp.cpu || sp.arch, `${sp.cores} cores${sp.avx512 ? ", AVX-512" : sp.avx2 ? ", AVX2" : ""}`, `${gb(sp.memGB)} memory (${gb(sp.freeMemGB)} free)`, `${gb(sp.diskFreeGB)} free disk`, sp.gpu || "no graphics card visible here"];
  out.push(el("div", { class: "ai-spec" }, el("span", { class: "title" }, "This computer"), el("span", { class: "sub" }, facts.join(" · "))));
  if (!sp.engine) out.push(el("p", { class: "banner" }, "This FamLogic doesn't have the built-in engine (it's in the published Docker image). Use a model server instead."));
  if (sp.gpu === "NVIDIA graphics card") out.push(el("p", { class: "muted small" }, "There's a graphics card here: the built-in engine uses the CPU, so for speed run llama.cpp's CUDA image beside FamLogic (see \"On a computer at home\") and point Steward at it."));
  if (st.downloadError) out.push(el("p", { class: "banner" }, "The download stopped: " + st.downloadError));
  if (st.runError) out.push(el("p", { class: "banner" }, st.runError));
  const installed = new Set(d.installed || []);
  const inUse = d.config.source === "builtin" ? d.config.model : "";
  const act = async (action, model) => { try { await api("POST", "/api/admin/steward/brain", { action, model }); redraw(); } catch (err) { alert(err.message); } };
  const rows = d.models.map(({ model: m, estimate: e }) => {
    const btns = [];
    if (st.downloading === m.id) {
      const pct = st.total ? Math.round(100 * st.got / st.total) : 0;
      btns.push(el("div", { class: "ai-progress", title: `${pct}%` }, el("span", { style: `width:${pct}%` })), el("button", { type: "button", class: "secondary small", onclick: () => act("cancel", m.id) }, "Stop"));
    } else if (installed.has(m.id)) {
      if (inUse === m.id) btns.push(el("span", { class: "chip" }, st.ready ? "In use" : st.running ? "Starting…" : "Chosen"));
      else btns.push(el("button", { type: "button", class: "small", onclick: () => act("use", m.id) }, "Use"));
      if (inUse !== m.id) btns.push(el("button", { type: "button", class: "secondary small", onclick: () => confirm(`Delete ${m.name} (${gb(m.sizeGB)})?`) && act("delete", m.id) }, "Delete"));
    } else if (sp.engine && e.fits) {
      btns.push(el("button", { type: "button", class: "secondary small", disabled: st.downloading ? "" : null, onclick: () => act("download", m.id) }, `Download ${gb(m.sizeGB)}`));
    }
    const speed = e.fits ? el("span", { class: `ai-verdict v-${e.verdict.replace(" ", "-")}` }, `${secs(e.typicalS)} an answer`) : el("span", { class: "ai-verdict v-too-slow" }, "Too big here");
    return el("li", { class: "ai-model" + (inUse === m.id ? " current" : "") },
      el("div", { class: "grow" }, el("div", { class: "title" }, m.name, " ", speed),
        el("div", { class: "sub" }, m.good), el("div", { class: "sub" }, [`${gb(m.sizeGB)} download`, `needs ${gb(m.ramGB)} memory`, m.licence].join(" · ")),
        e.fits ? null : el("div", { class: "sub warn" }, e.why)),
      el("div", { class: "ai-btns" }, ...btns));
  });
  out.push(el("ul", { class: "ai-models" }, ...rows),
    el("p", { class: "muted small" }, "Times are rough guesses for a usual request on this computer; the first answer after starting takes longer. Run the test below for real numbers."));
  if (st.downloading) setTimeout(() => { if (document.contains(out[0])) redraw(); }, 2000);
  else if (st.running && !st.ready) setTimeout(() => { if (document.contains(out[0])) redraw(); }, 3000);
  return out;
}

function snippet(text) {
  const pre = el("pre", { class: "ai-snippet" }, text);
  const b = el("button", { type: "button", class: "secondary small", onclick: () => { navigator.clipboard.writeText(text); b.textContent = "Copied"; } }, "Copy");
  return el("div", { class: "ai-snippet-wrap" }, pre, b);
}

function serverPane(d, save) {
  const c = d.config;
  const url = el("input", { placeholder: "http://192.168.1.20:8081/v1", value: c.source === "server" ? c.url : "" });
  const model = el("input", { placeholder: "e.g. qwen3.5-4b", value: c.source === "server" ? c.model : "", list: "ai-server-models" });
  const opts = el("datalist", { id: "ai-server-models" });
  const status = el("p", { class: "muted small" });
  const find = el("button", { type: "button", class: "secondary" }, "Find its models");
  find.addEventListener("click", async () => {
    status.textContent = "Asking it…";
    try { const r = await api("GET", "/api/admin/steward/models?url=" + encodeURIComponent(url.value.trim())); opts.replaceChildren(...r.models.map((m) => el("option", { value: m }))); status.textContent = r.models.length ? `It has: ${r.models.join(", ")}` : "It answered, with no models."; if (r.models.length && !model.value) model.value = r.models[0]; }
    catch (err) { status.textContent = err.message; }
  });
  const use = el("button", { type: "button" }, "Use it");
  use.addEventListener("click", () => save({ source: "server", url: url.value.trim(), model: model.value.trim(), key: "", clearKey: true, enabled: true }, "Steward uses it now"));
  return [
    el("p", {}, "On the computer that'll run the model (a graphics card helps a lot), start a model server. Either of these works:"),
    el("p", { class: "muted small" }, "llama.cpp in Docker, on an NVIDIA graphics card (drop --gpus all and use the \"server\" image for CPU only):"),
    snippet("docker run -d --name steward-model --gpus all -p 8081:8080 --restart unless-stopped \\\n  -v steward-models:/root/.cache/llama.cpp ghcr.io/ggml-org/llama.cpp:server-cuda \\\n  -hf unsloth/Qwen3.5-4B-GGUF:Q4_K_M --jinja -c 12288 -ngl 99 --host 0.0.0.0 --port 8080"),
    el("p", { class: "muted small" }, "Or Ollama (its address is then http://that-computer:11434/v1):"),
    snippet("ollama pull qwen3.5:4b"),
    el("p", { class: "muted small" }, "On a 12 GB graphics card, the 9B models fit easily and answer in a second or two; the 4B ones are quicker still."),
    el("div", { class: "row" }, el("label", { class: "grow" }, "Its address", url), find), opts, status,
    el("label", {}, "Model", model),
    el("div", { class: "row" }, use)];
}

// The cloud form, for the main model (which = "main") or the fallback.
function cloudPane(d, save, which) {
  const c = d.config, cur = which === "main" ? (c.source === "cloud" ? c : {}) : (c.fallback || {});
  const preset = el("select", {}, ...CLOUD_PRESETS.map(([id, t]) => el("option", { value: id }, t)));
  const url = el("input", { placeholder: "https://…/v1", value: cur.url || "" });
  const model = el("input", { placeholder: "model name", value: cur.model || "" });
  const hasKey = which === "main" ? d.hasKey && c.source === "cloud" : d.hasFallbackKey;
  const key = el("input", { type: "password", autocomplete: "off", placeholder: hasKey ? "Saved (leave empty to keep it)" : "The provider's API key" });
  const match = CLOUD_PRESETS.find(([, , u]) => u && u === cur.url);
  preset.value = match ? match[0] : (cur.url ? "other" : "openrouter");
  const fill = () => { const p = CLOUD_PRESETS.find(([id]) => id === preset.value); if (p && p[2]) { url.value = p[2]; if (!model.value || CLOUD_PRESETS.some(([, , , m]) => m === model.value)) model.value = p[3]; } };
  if (!cur.url) fill();
  preset.addEventListener("change", fill);
  const go = el("button", { type: "button" }, which === "main" ? "Use it" : "Save the fallback");
  go.addEventListener("click", () => {
    if (which === "main") save({ source: "cloud", url: url.value.trim(), model: model.value.trim(), key: key.value, enabled: true }, "Steward uses it now");
    else save({ fallback: { url: url.value.trim(), model: model.value.trim(), key: key.value, source: "cloud" } }, "Saved");
  });
  return [el("label", {}, "Provider", preset), el("label", {}, "Address", url),
    el("div", { class: "row" }, el("label", { class: "grow" }, "Model", model), el("label", { class: "grow" }, "Key", key)),
    which === "main" ? el("p", { class: "muted small" }, "What the family asks, and what Steward reads to answer it (lists, chores, the calendar), goes to the provider.") : null,
    el("div", { class: "row" }, go)];
}

function fallbackCard(d, save) {
  const c = d.config, has = !!(c.fallback && c.fallback.url);
  const [on, onRow] = aiSwitch(has, "Hand over to a second model when the first can't answer", "When it's down, too slow, or (if you allow it) says a request is too much for it. Never after it's already changed something, so nothing happens twice.");
  const body = el("div", { class: "ai-pane" });
  const [ho, hoRow] = aiSwitch(!!c.handOff, "Let the first model hand over hard requests", "Small models can pass on long rambles and recipes they'd get wrong.");
  ho.addEventListener("change", () => save({ handOff: ho.checked }, "Saved"));
  const timeout = el("select", {}, ...[[30, "30 seconds"], [60, "1 minute"], [90, "90 seconds"], [180, "3 minutes"]].map(([v, t]) => el("option", { value: v }, t)));
  timeout.value = String(c.timeout || 90);
  timeout.addEventListener("change", () => save({ timeout: Number(timeout.value) }, "Saved"));
  const fill = () => body.replaceChildren(...(on.checked ? [...cloudPane(d, save, "fallback"), hoRow, el("label", {}, "Wait for the first model at most", timeout)] : []));
  on.addEventListener("change", () => { if (!on.checked && has) save({ fallback: null, handOff: false }, "No fallback"); fill(); });
  fill();
  return el("section", { class: "ai-card" }, el("h2", {}, "If it can't answer"), onRow, body);
}

function testCard(d, redraw) {
  const ev = d.eval;
  const run = async (levels) => { try { await api("POST", "/api/admin/steward/eval", { levels }); setTimeout(redraw, 1500); } catch (err) { alert(err.message); } };
  const out = [el("h2", {}, "Test it"),
    el("p", { class: "muted small" }, "Runs real family requests (\"add milk to the shopping list\", \"I unloaded the dishwasher\") on a made-up family, with the model Steward uses now, and checks each one actually worked. Your family's things aren't touched."),
    el("div", { class: "row" }, el("button", { type: "button", onclick: () => run(["quick"]) }, "Quick test (8)"), el("button", { type: "button", class: "secondary", onclick: () => run(["quick", "more", "safety"]) }, "Everything (21)"))];
  if (ev) {
    const running = !ev.done;
    out.push(el("p", {}, el("strong", {}, `${ev.passed} of ${ev.results ? ev.results.length : 0} right`), running ? ` (running, ${ev.results ? ev.results.length : 0} of ${ev.total})` : "",
      ev.medianMs ? ` · ${secs(ev.medianMs / 1000)} a request` : "", ev.avgPromptTokens ? ` · ${ev.avgPromptTokens} prompt tokens an answer` : "", ` · ${ev.model}`));
    out.push(el("ul", { class: "list compact ai-results" }, ...(ev.results || []).map((r) => el("li", {}, el("span", { class: r.pass ? "ok-dot" : "bad-dot" }), el("span", { class: "grow" }, r.id, r.why ? el("span", { class: "sub" }, " · " + r.why) : null), el("span", { class: "muted small" }, (r.ms / 1000).toFixed(1) + " s")))));
    if (running) setTimeout(() => { if (document.contains(out[0])) redraw(); }, 2500);
  }
  if (d.turns && d.turns.length) {
    out.push(el("h3", {}, "Recent answers"), el("ul", { class: "list compact" }, ...d.turns.slice(-8).reverse().map((t) => el("li", { class: "small" },
      el("span", { class: t.ok ? "ok-dot" : "bad-dot" }), el("span", { class: "grow" }, `${new Date(t.time).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })} · ${t.model}${t.fallback ? " (fallback: " + t.why + ")" : ""}`, t.error ? el("span", { class: "sub" }, " · " + t.error) : null),
      el("span", { class: "muted" }, `${(t.ms / 1000).toFixed(1)} s · ${t.prompt} read${t.cached ? ` (${t.cached} kept)` : ""} · ${t.completion} written`)))));
  }
  return el("section", { class: "ai-card" }, ...out);
}

function agentsCard(d) {
  const box = el("div");
  (async () => {
    let data;
    try { data = await api("GET", "/api/admin/keys"); } catch (err) { box.replaceChildren(el("p", {}, err.message)); return; }
    const name = el("input", { placeholder: "Who'll use it, e.g. Hermes on the NAS", maxlength: "40" });
    const scopes = el("div", { class: "ai-scopes" }, ...data.scopes.map((sc) => {
      const i = el("input", { type: "checkbox", class: "switch", role: "switch", value: sc.id, ...(["location", "notes"].includes(sc.id) ? {} : { checked: "" }) });
      return el("label", { class: "ai-switch" }, el("span", { class: "grow" }, el("span", { class: "title" }, sc.id.charAt(0).toUpperCase() + sc.id.slice(1)), el("span", { class: "sub" }, sc.text)), i);
    }));
    const shown = el("div");
    const days = el("select", {}, ...[["0", "Never"], ["30", "In 30 days"], ["90", "In 90 days"], ["365", "In a year"]].map(([v, t]) => el("option", { value: v }, t)));
    days.value = "90";
    const make = el("button", { type: "submit" }, "Make a key");
    const form = el("form", { class: "ai-form" }, el("label", {}, "Name", name), el("p", { class: "muted small" }, "It may:"), scopes,
      el("div", { class: "row end" }, el("label", { class: "grow" }, "Expires", days), make));
    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      try {
        const res = await api("POST", "/api/admin/keys", { name: name.value.trim(), scopes: [...scopes.querySelectorAll("input:checked")].map((i) => i.value), days: Number(days.value) });
        shown.replaceChildren(el("p", {}, el("strong", {}, "Copy this key now: "), "it won't be shown again."),
          el("div", { class: "copy" }, el("input", { readonly: "", value: res.key }), el("button", { type: "button", class: "secondary", onclick: (e) => { navigator.clipboard.writeText(res.key); e.target.textContent = "Copied"; } }, "Copy")));
        name.value = "";
        drawList(await api("GET", "/api/admin/keys"));
      } catch (err) { alert(err.message); }
    });
    const list = el("ul", { class: "list" }), log = el("ul", { class: "list compact" });
    const drawList = (dd) => {
      list.replaceChildren(...(dd.keys.length ? dd.keys.map((k) => {
        const revoke = el("button", { type: "button", class: "secondary small" }, "Revoke");
        revoke.addEventListener("click", async () => { if (confirm(`Revoke "${k.name}"? It stops working at once.`)) { await api("DELETE", `/api/admin/keys/${k.id}`); drawList(await api("GET", "/api/admin/keys")); } });
        return el("li", {}, el("div", { class: "grow" }, el("div", { class: "title" }, k.name, " ", el("code", {}, k.prefix + "…")),
          el("div", { class: "sub" }, [k.scopes.join(", "), k.expires ? (k.expires < Date.now() ? "expired" : `expires ${new Date(k.expires).toLocaleDateString()}`) : "never expires",
            k.uses ? `used ${k.uses} times, last ${new Date(k.lastUsed).toLocaleString()}` : "not used yet"].join(" · "))), revoke);
      }) : [el("li", { class: "muted" }, "No keys yet.")]));
      const names = Object.fromEntries(dd.keys.map((k) => [k.id, k.name]));
      log.replaceChildren(...dd.log.slice(-20).reverse().map((u) => el("li", { class: "small" }, `${new Date(u.time).toLocaleString()} · ${names[u.key] || "(revoked key)"} · ${u.method} ${u.path}`)));
    };
    drawList(data);
    box.replaceChildren(
      el("p", {}, "An outside agent (Hermes, Claude Desktop, any MCP client) can look after the family's things too, with a key: it gets the same skills Steward has, opening only the ones it needs."),
      el("label", {}, "MCP address", el("div", { class: "copy" }, el("input", { readonly: "", value: d.mcpURL }), el("button", { type: "button", class: "secondary", onclick: (e) => { navigator.clipboard.writeText(d.mcpURL); e.target.textContent = "Copied"; } }, "Copy"))),
      el("p", { class: "muted small" }, "For Hermes, download ", el("a", { href: "/api/v1/agent-skill", download: "SKILL.md" }, "FamLogic's skill"), " into ~/.hermes/skills/famlogic/: it teaches Hermes FamLogic once, instead of with every request. Plain HTTP is described at ", el("a", { href: "/api/v1/openapi.json", target: "_blank" }, "/api/v1/openapi.json"), "."),
      el("h3", {}, "Keys"), list, el("h3", {}, "Make a key"), form, shown, el("h3", {}, "Recent uses"), log);
  })();
  return el("section", { class: "ai-card" }, el("h2", {}, "Outside agents"), box);
}

// Docket: shared lists and to-dos (Markdown notes in the vault's Lists/ and To-dos/).
let listsDrawn = "";
let listOpen = "";
async function renderLists(box, force = false) {
  if (!force && box.contains(document.activeElement) && document.activeElement.matches("input, select, textarea")) return;
  let data;
  try { data = await api("GET", "/api/lists"); } catch (err) { box.replaceChildren(el("p", { class: "muted" }, err.message)); return; }
  if (box.dataset.module !== "todos") return; // the page changed while loading
  const sig = JSON.stringify([data, listOpen]);
  if (!force && sig === listsDrawn && box.childElementCount) return;
  listsDrawn = sig;
  const people = lastMembers.filter((m) => m.role !== "dependent");
  const act = async (method, path, body) => { try { await api(method, path, body); renderLists(box, true); } catch (err) { alert(err.message); } };
  const listCard = (l) => {
    const open = listOpen === l.id || data.lists.length === 1;
    const left = l.items.filter((i) => !i.done).length;
    const head = el("div", { class: "list-head clickable", onclick: () => { listOpen = open ? "" : l.id; renderLists(box, true); } },
      el("div", { class: "title" }, l.title), el("span", { class: "muted small" }, `${l.kind === "todos" ? "To-dos" : "List"} · ${left ? left + " to go" : "all done"}`));
    if (!open) return el("div", { class: "panel list-card" }, head);
    const rows = [...l.items.filter((i) => !i.done), ...l.items.filter((i) => i.done)].map((it) => {
      const box2 = el("input", { type: "checkbox", ...(it.done ? { checked: "" } : {}) });
      box2.addEventListener("change", () => act("POST", `/api/lists/${l.id}/items/${it.id}`, { done: box2.checked }));
      const rm = el("button", { type: "button", class: "link" }, "Remove");
      rm.addEventListener("click", () => act("DELETE", `/api/lists/${l.id}/items/${it.id}`));
      const who = people.find((p) => p.id === it.who);
      return el("li", { class: it.done ? "done-item" : "" }, el("label", { class: "item-check" }, box2, el("span", {}, it.text)),
        el("span", { class: "muted small" }, [it.section, who && who.name, it.due].filter(Boolean).join(" · ")), rm);
    });
    const input = el("input", { placeholder: "Add an item", maxlength: "200" });
    const add = el("form", { class: "row list-add" }, input, el("button", { type: "submit" }, "Add"));
    add.addEventListener("submit", (ev) => { ev.preventDefault(); if (input.value.trim()) act("POST", `/api/lists/${l.id}/items`, { text: input.value.trim() }); });
    const clear = el("button", { type: "button", class: "secondary" }, "Clear ticked");
    clear.addEventListener("click", () => act("POST", `/api/lists/${l.id}/clear`));
    const retire = el("button", { type: "button", class: "link" }, "Retire list");
    retire.addEventListener("click", () => { if (confirm(`Retire "${l.title}"? Its note stays in the vault.`)) act("DELETE", `/api/lists/${l.id}`); });
    return el("div", { class: "panel list-card" }, head, el("ul", { class: "list compact" }, ...rows), add, el("div", { class: "row" }, clear, retire));
  };
  // A new list.
  const title = el("input", { placeholder: "e.g. Shopping, Beach trip", maxlength: "60" });
  const kind = el("select", {}, el("option", { value: "list" }, "Shared list"), el("option", { value: "todos" }, "To-dos"));
  const make = el("form", { class: "row list-add" }, title, kind, el("button", { type: "submit" }, "New list"));
  make.addEventListener("submit", (ev) => { ev.preventDefault(); if (title.value.trim()) act("POST", "/api/lists", { title: title.value.trim(), kind: kind.value }); });
  box.replaceChildren(...(data.lists.length ? data.lists.map(listCard) : [el("p", { class: "muted" }, "No lists yet.")]),
    ...(data.canEdit ? [el("h2", {}, "Make a list"), make] : []));
}

// Datebook: the family calendar (Markdown notes in Calendar/; CalDAV for phones).
let calDrawn = "";
// Datebook's calendar views (the same as the app's and the TV's): the list, 3 days, the work
// week, the week, and the month, each event in its calendar's or its person's colour.
let calMode = (() => { try { return localStorage.getItem("calMode") || "list"; } catch (_) { return "list"; } })();
let calAnchor = (() => { const d = new Date(); d.setHours(0, 0, 0, 0); return d.getTime(); })();
let calPicked = calAnchor;
const CAL_MODES = [["list", "List"], ["3", "3 days"], ["work", "Work week"], ["week", "Week"], ["month", "Month"]];

function calDay(t, add = 0) { const d = new Date(t); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() + add); return d.getTime(); }
function calDaysFor(mode, anchor) {
  const d = new Date(anchor);
  const monday = calDay(anchor, -((d.getDay() + 6) % 7));
  if (mode === "3") return [0, 1, 2].map((i) => calDay(anchor, i));
  if (mode === "work") return [0, 1, 2, 3, 4].map((i) => calDay(monday, i));
  if (mode === "week") return [0, 1, 2, 3, 4, 5, 6].map((i) => calDay(monday, i));
  const first = new Date(d.getFullYear(), d.getMonth(), 1).getTime();
  const start = calDay(first, -((new Date(first).getDay() + 6) % 7));
  return Array.from({ length: 42 }, (_, i) => calDay(start, i));
}
function calStep(mode, anchor, by) {
  if (mode === "month") { const d = new Date(anchor); return new Date(d.getFullYear(), d.getMonth() + by, 1).getTime(); }
  return calDay(anchor, (mode === "3" ? 3 : 7) * by);
}
const calOn = (events, day) => events.filter((e) => e.start < calDay(day, 1) && Math.max(e.end || 0, e.start + 1) > day);
const calColor = (e) => e.color || "var(--accent)";

async function calendarViews(box, rerender) {
  const modes = el("div", { class: "cal-modes" }, ...CAL_MODES.map(([m, t]) => {
    const b = el("button", { type: "button", class: m === calMode ? "toggle on" : "toggle", "aria-pressed": String(m === calMode) }, t);
    b.addEventListener("click", () => { calMode = m; try { localStorage.setItem("calMode", m); } catch (_) {} rerender(); });
    return b;
  }));
  if (calMode === "list") return [modes];
  const days = calDaysFor(calMode, calAnchor);
  let events = [];
  try { events = (await api("GET", `/api/events?from=${days[0]}&to=${calDay(days[days.length - 1], 1)}`)).events; } catch (_) {}
  const nav = (by, label) => { const b = el("button", { type: "button", class: "secondary" }, label); b.addEventListener("click", () => { calAnchor = by === 0 ? calDay(Date.now()) : calStep(calMode, calAnchor, by); calPicked = by === 0 ? calAnchor : calPicked; rerender(); }); return b; };
  const fmt = (t, o) => new Date(t).toLocaleDateString([], o);
  const title = calMode === "month" ? fmt(calAnchor, { month: "long", year: "numeric" }) : `${fmt(days[0], { day: "numeric", month: "short" })} – ${fmt(days[days.length - 1], { day: "numeric", month: "short" })}`;
  const head = el("div", { class: "cal-head" }, nav(-1, "‹"), el("h2", { class: "grow" }, title), nav(1, "›"), nav(0, "Today"));
  const today = calDay(Date.now());
  const chip = (e) => el("div", { class: "cal-chip", style: `--cal:${calColor(e)}` },
    el("span", { class: "muted small" }, e.allDay ? "All day" : new Date(e.start).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })), el("span", {}, e.title));
  if (calMode !== "month") {
    const cols = el("div", { class: "cal-cols", style: `--n:${days.length}` }, ...days.map((d) => el("div", { class: "cal-col" + (d === today ? " today" : "") },
      el("div", { class: "cal-colhead" }, el("span", { class: "muted small" }, fmt(d, { weekday: "short" })), el("span", { class: "cal-num" }, String(new Date(d).getDate()))),
      ...(calOn(events, d).length ? calOn(events, d).map(chip) : [el("span", { class: "muted small" }, "—")]))));
    return [modes, head, cols];
  }
  const month = new Date(calAnchor).getMonth();
  const grid = el("div", { class: "cal-month" }, ...days.slice(0, 7).map((d) => el("div", { class: "cal-wd muted small" }, fmt(d, { weekday: "short" }))),
    ...days.map((d) => {
      const list = calOn(events, d);
      const cell = el("button", { type: "button", class: "cal-cell" + (new Date(d).getMonth() !== month ? " other" : "") + (d === today ? " today" : "") + (d === calPicked ? " picked" : "") },
        el("span", { class: "cal-num" }, String(new Date(d).getDate())),
        ...list.slice(0, 3).map((e) => el("span", { class: "cal-bar", style: `--cal:${calColor(e)}` }, e.title)),
        list.length > 3 ? el("span", { class: "muted small" }, `+${list.length - 3}`) : null);
      cell.addEventListener("click", () => { calPicked = d; rerender(); });
      return cell;
    }));
  const picked = calOn(events, calPicked);
  const dayList = el("div", { class: "cal-daylist" }, el("h2", {}, fmt(calPicked, { weekday: "long", day: "numeric", month: "long" })),
    ...(picked.length ? picked.map(chip) : [el("p", { class: "muted" }, "Nothing on.")]));
  return [modes, head, grid, dayList];
}

async function renderCalendar(box, force = false) {
  if (!force && box.contains(document.activeElement) && document.activeElement.matches("input, select, textarea")) return;
  const from = new Date(); from.setHours(0, 0, 0, 0);
  let data;
  let feeds = { feeds: [], canManage: false };
  try {
    [data, feeds] = await Promise.all([api("GET", `/api/events?from=${from.getTime()}&to=${from.getTime() + 45 * 86400000}`), api("GET", "/api/calendar/feeds").catch(() => feeds)]);
  } catch (err) { box.replaceChildren(el("p", { class: "muted" }, err.message)); return; }
  if (box.dataset.module !== "calendar") return; // the page changed while loading
  const sig = JSON.stringify([data, feeds]);
  if (!force && sig === calDrawn && box.childElementCount) return;
  calDrawn = sig;
  const act = async (method, path, body) => { try { await api(method, path, body); renderCalendar(box, true); } catch (err) { alert(err.message); } };
  const people = lastMembers.filter((m) => m.role !== "dependent");
  const days = {};
  for (const e of data.events) {
    const d = new Date(Math.max(e.start, from.getTime())); d.setHours(0, 0, 0, 0);
    (days[d.getTime()] = days[d.getTime()] || []).push(e);
  }
  const agenda = Object.keys(days).sort().map((k) => {
    const d = new Date(Number(k));
    const label = d.getTime() === from.getTime() ? "Today" : d.toLocaleDateString([], { weekday: "long", day: "numeric", month: "long" });
    return [el("h2", {}, label), el("ul", { class: "list" }, ...days[k].map((e) => {
      const rm = el("button", { type: "button", class: "link" }, "Delete");
      rm.addEventListener("click", () => { if (confirm(`Delete "${e.title}"${e.recurring ? " (every time it repeats)" : ""}?`)) act("DELETE", `/api/events/${e.id}`); });
      // Outside calendars' events: their colour and name, and read-only.
      return el("li", e.calendar ? { class: "outside", style: `--cal:${e.color || "var(--muted)"}` } : {}, el("span", { class: "feed-time muted small" }, e.allDay ? "All day" : new Date(e.start).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })),
        el("div", { class: "grow" }, el("div", { class: "title" }, e.title, e.recurring ? el("span", { class: "badge" }, "repeats") : null),
          el("div", { class: "sub" }, [e.calendar, e.where, (e.who || []).map((id) => (people.find((p) => p.id === id) || {}).name).filter(Boolean).join(", ")].filter(Boolean).join(" · "))),
        data.canEdit && !e.calendar ? rm : null);
    }))];
  }).flat();
  // Add an event.
  const title = el("input", { placeholder: "e.g. Dentist (Sam)", maxlength: "100" });
  const day = el("input", { type: "date", value: from.toISOString().slice(0, 10) });
  const t1 = el("input", { type: "time", value: "17:00" });
  const t2 = el("input", { type: "time", value: "18:00" });
  const allDay = el("input", { type: "checkbox", class: "switch", role: "switch" });
  const where = el("input", { placeholder: "Where (optional)", maxlength: "100" });
  const every = el("select", {}, ...[["", "Once"], ["day", "Every day"], ["weekdays", "Weekdays"], ["week", "Every week"], ["2 weeks", "Every 2 weeks"], ["month", "Every month"], ["year", "Every year"]].map(([v, t]) => el("option", { value: v }, t)));
  const who = el("div", { class: "toggles" }, ...people.map((m) => {
    const b = el("button", { type: "button", class: "toggle", "data-id": m.id, "aria-pressed": "false" }, avatarOf(m), el("span", {}, m.name));
    b.addEventListener("click", () => b.setAttribute("aria-pressed", b.getAttribute("aria-pressed") === "true" ? "false" : "true"));
    return b;
  }));
  const form = el("form", { class: "upkeep-add" }, el("label", {}, "What", title),
    el("div", { class: "row" }, el("label", {}, "Day", day), el("label", {}, "From", t1), el("label", {}, "To", t2), el("label", { class: "switch-label" }, allDay, "All day")),
    el("div", { class: "row" }, el("label", { class: "grow" }, "Where", where), el("label", {}, "Repeats", every)),
    el("div", { class: "muted small" }, "Who"), who, el("div", { class: "row" }, el("button", { type: "submit" }, "Add event")));
  form.addEventListener("submit", (ev) => {
    ev.preventDefault();
    if (!title.value.trim()) return;
    act("POST", "/api/events", { title: title.value.trim(), start: allDay.checked ? day.value : `${day.value}T${t1.value}`, end: allDay.checked ? "" : `${day.value}T${t2.value}`,
      where: where.value.trim(), every: every.value, who: [...who.querySelectorAll('[aria-pressed="true"]')].map((b) => b.dataset.id) });
  });
  // Outside calendars (school, sports, holidays): parents add a link; everyone sees them.
  const feedRows = feeds.feeds.map((f) => {
    const rm = el("button", { type: "button", class: "link danger" }, "Remove");
    rm.addEventListener("click", () => { if (confirm(`Stop showing "${f.name}"?`)) act("DELETE", `/api/calendar/feeds/${f.id}`); });
    const again = el("button", { type: "button", class: "link" }, "Refresh");
    again.addEventListener("click", () => act("POST", `/api/calendar/feeds/${f.id}/refresh`));
    return el("li", {}, el("span", { class: "cal-dot", style: `background:${f.color}` }),
      el("div", { class: "grow" }, el("div", { class: "title" }, f.name),
        el("div", { class: "sub" }, f.error ? "Problem: " + f.error : `${f.events} ${f.events === 1 ? "event" : "events"} · updated ${f.fetched ? ago(f.fetched) : "never"}`)),
      feeds.canManage ? again : null, feeds.canManage ? rm : null);
  });
  const fName = el("input", { placeholder: "e.g. School, Holidays", maxlength: "40" });
  const fURL = el("input", { placeholder: "https://… or webcal://… (.ics)" });
  const fColor = el("input", { type: "color", value: "#3a7bd5", "aria-label": "Colour" });
  const fForm = el("form", { class: "upkeep-add" },
    el("div", { class: "row" }, el("label", { class: "grow" }, "Name", fName), el("label", {}, "Colour", fColor)),
    el("label", {}, "Link", fURL), el("div", { class: "row" }, el("button", { type: "submit", class: "tonal" }, "Add calendar")));
  fForm.addEventListener("submit", (ev) => { ev.preventDefault(); if (fName.value.trim() && fURL.value.trim()) act("POST", "/api/calendar/feeds", { name: fName.value.trim(), url: fURL.value.trim(), color: fColor.value }); });
  const outside = (feedRows.length || feeds.canManage) ? [el("h2", {}, "Outside calendars"),
    el("p", { class: "muted small" }, "Calendars from a link, like school terms, sports fixtures or holidays. Everyone sees them; they update every few hours and show on phones too."),
    feedRows.length ? el("ul", { class: "list" }, ...feedRows) : null, feeds.canManage ? fForm : null] : [];
  const sync = el("div", { class: "panel" }, el("h2", {}, "On your phone"),
    el("p", { class: "muted small" }, "The family calendar syncs both ways with phones and computers (CalDAV). Sign in with your FamLogic username and password."),
    el("ul", { class: "plain small" },
      el("li", {}, el("strong", {}, "Android: "), "install DAVx5 (F-Droid or Play Store), add an account with URL ", el("code", {}, location.origin + "/dav/"), ", then switch on the calendar."),
      el("li", {}, el("strong", {}, "iPhone or Mac: "), "Settings → Calendar → Accounts → Add Account → Other → CalDAV, server ", el("code", {}, location.host), "."),
      el("li", {}, el("strong", {}, "Thunderbird: "), "New Calendar → On the network, location ", el("code", {}, location.origin + "/dav/"), ".")));
  const views = await calendarViews(box, () => renderCalendar(box, true));
  if (box.dataset.module !== "calendar") return;
  box.replaceChildren(...views, ...(calMode !== "list" ? [] : agenda.length ? agenda : [el("p", { class: "muted" }, "Nothing on the calendar for the next few weeks.")]),
    ...(data.canEdit ? [el("h2", {}, "Add an event"), form] : []), ...outside.filter(Boolean), sync);
}

// Huddle: family meetings. Parents write a short agenda ("## Topic", lines about it,
// "- Job @Name", "> note"); the family clicks through it, hands out jobs and keeps notes;
// then the jobs go onto the Docket.
let meetingOpen = null; // the meeting being presented: { m, at }
function agendaToSlides(text) {
  const slides = [];
  let cur = null;
  let body = [];
  const flush = () => { if (cur) { cur.text = body.join("\n").trim(); slides.push(cur); } body = []; };
  for (const raw of text.split("\n")) {
    const l = raw.replace(/\s+$/, "");
    if (l.startsWith("#")) { flush(); cur = { title: l.replace(/^#+/, "").trim(), jobs: [], notes: [] }; }
    else if (!cur) { if (l.trim()) cur = { title: l.trim(), jobs: [], notes: [] }; }
    else if (/^[-*] /.test(l)) {
      let t = l.slice(2).replace(/^\[ \] /, "").trim();
      const m = (" " + t).match(/\s@([\p{L}\p{N}_\-]+)/u);
      const who = m ? m[1].replace(/_/g, " ") : "";
      if (m) t = (" " + t).replace(m[0], "").trim();
      if (t) cur.jobs.push({ text: t, who });
    } else if (l.startsWith(">")) { const n = l.slice(1).trim(); if (n) cur.notes.push(n); }
    else body.push(l);
  }
  flush();
  return slides;
}
function slidesToAgenda(m, people) {
  const name = (id) => (people.find((p) => p.id === id) || {}).name;
  return m.slides.map((s) => ["## " + s.title, s.text, ...s.jobs.map((j) => "- " + j.text + (name(j.who) ? " @" + name(j.who).replace(/ /g, "_") : "")), ...s.notes.map((n) => "> " + n)].filter(Boolean).join("\n")).join("\n\n");
}

async function renderMeetings(box, editingId = null) {
  if (box.contains(document.activeElement) && document.activeElement.matches("input, textarea, select")) return;
  let d;
  try { d = await api("GET", "/api/meetings"); } catch (err) { box.replaceChildren(el("p", { class: "muted" }, err.message)); return; }
  if (box.dataset.module !== "meetings") return;
  const people = lastMembers.filter((p) => p.role !== "dependent" && p.access !== "display");
  if (meetingOpen) { presentMeeting(box, d, people); return; }
  const parts = [];
  if (!d.meetings.length) parts.push(el("p", { class: "muted" }, d.canEdit ? "No meetings yet. Make one below, or ask Steward (\"set up a family meeting about the holidays and chores\")." : "No meetings yet."));
  parts.push(el("ul", { class: "list" }, ...d.meetings.map((m) => {
    const jobs = m.slides.reduce((n, s) => n + s.jobs.length, 0);
    const start = el("button", { type: "button", class: "tonal" }, "Start");
    start.addEventListener("click", () => { meetingOpen = { id: m.id, at: 0 }; renderMeetings(box); });
    const edit = d.canEdit ? el("button", { type: "button", class: "secondary" }, "Edit") : null;
    if (edit) edit.addEventListener("click", () => renderMeetings(box, m.id));
    return el("li", {}, el("div", { class: "grow" }, el("div", { class: "title" }, m.title),
      el("div", { class: "sub" }, [m.date, `${m.slides.length} ${m.slides.length === 1 ? "topic" : "topics"}`, jobs ? `${jobs} ${jobs === 1 ? "job" : "jobs"}` : null].filter(Boolean).join(" · ") + " — " + m.slides.map((s) => s.title).join(", "))), start, edit);
  })));
  if (d.canEdit) {
    const editing = d.meetings.find((m) => m.id === editingId);
    const title = el("input", { maxlength: "80", value: editing ? editing.title : "Family meeting" });
    const date = el("input", { type: "date", value: editing ? editing.date : new Date().toISOString().slice(0, 10) });
    const agenda = el("textarea", { rows: "12", placeholder: "## Weekend plans\nThe park on Saturday, or the pool?\n- Pack the picnic @Sam\n- Bring the football\n\n## Chores for the week\n- Hoover the stairs" });
    agenda.value = editing ? slidesToAgenda(editing, people) : "";
    const save = el("button", { type: "submit" }, editing ? "Save meeting" : "Make the meeting");
    const form = el("form", { class: "upkeep-add" }, el("div", { class: "row" }, el("label", { class: "grow" }, "Title", title), el("label", {}, "Date", date)),
      el("label", {}, "Topics", agenda),
      el("p", { class: "muted small" }, "Each topic starts with ## and becomes a slide; lines under it are what it says. \"- Job @Name\" hands out a job (without @Name, it asks for a volunteer); \"> \" is a note. Names: " + people.map((p) => p.name.replace(/ /g, "_")).join(", ") + "."),
      el("div", { class: "row" }, save, editing ? (() => { const c = el("button", { type: "button", class: "secondary" }, "Cancel"); c.addEventListener("click", () => renderMeetings(box)); return c; })() : null,
        editing ? (() => { const r = el("button", { type: "button", class: "link danger" }, "Remove"); r.addEventListener("click", async () => { if (confirm(`Remove "${editing.title}"?`)) { await api("POST", `/api/meetings/${editing.id}/delete`).catch(() => {}); renderMeetings(box); } }); return r; })() : null));
    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const body = { title: title.value.trim(), date: date.value, slides: agendaToSlides(agenda.value) };
      try { await api(editing ? "PUT" : "POST", editing ? `/api/meetings/${editing.id}` : "/api/meetings", body); renderMeetings(box); } catch (err) { alert(err.message); }
    });
    parts.push(el("h2", {}, editing ? "Edit the meeting" : "New meeting"), form);
  }
  box.replaceChildren(...parts);
}

async function presentMeeting(box, d, people) {
  let m = d.meetings.find((x) => x.id === meetingOpen.id);
  if (!m) { meetingOpen = null; renderMeetings(box); return; }
  const draw = () => {
    const at = Math.min(meetingOpen.at, Math.max(0, m.slides.length - 1));
    const s = m.slides[at];
    const act = async (method, path, body) => { try { m = (await api(method, path, body)).meeting; draw(); } catch (err) { alert(err.message); } };
    const back = el("button", { type: "button", class: "secondary" }, "Close");
    back.addEventListener("click", () => { meetingOpen = null; renderMeetings(box); });
    if (!s) { box.replaceChildren(el("p", {}, "This meeting has no topics yet."), back); return; }
    const jobs = el("ul", { class: "list" }, ...s.jobs.map((j, k) => {
      const pick = el("select", { class: "who" }, el("option", { value: "" }, "Needs a volunteer"), ...people.map((p) => el("option", { value: p.id }, p.name)));
      pick.value = j.who || "";
      pick.disabled = !d.canRun;
      pick.addEventListener("change", () => act("POST", `/api/meetings/${m.id}/slides/${at}/jobs/${k}`, { who: pick.value }));
      const rm = d.canRun ? el("button", { type: "button", class: "link danger" }, "Remove") : null;
      if (rm) rm.addEventListener("click", () => act("POST", `/api/meetings/${m.id}/slides/${at}/jobs/${k}`, { remove: true }));
      return el("li", {}, el("span", {}, j.sent ? "📋" : "○"), el("div", { class: "grow" }, el("div", { class: "title" }, j.text)), pick, rm);
    }));
    const add = (kind, placeholder) => {
      const input = el("input", { placeholder, maxlength: kind === "jobs" ? "120" : "300" });
      const f = el("form", { class: "row" }, el("label", { class: "grow" }, input), el("button", { type: "submit", class: "secondary" }, "Add"));
      f.addEventListener("submit", (ev) => { ev.preventDefault(); if (input.value.trim()) act("POST", `/api/meetings/${m.id}/slides/${at}/${kind}`, { text: input.value.trim() }); });
      return f;
    };
    const prev = el("button", { type: "button", class: "secondary" }, "‹ Back");
    prev.disabled = at === 0;
    prev.addEventListener("click", () => { meetingOpen.at = at - 1; draw(); });
    const next = el("button", { type: "button" }, "Next ›");
    next.disabled = at >= m.slides.length - 1;
    next.addEventListener("click", () => { meetingOpen.at = at + 1; draw(); });
    const send = d.canRun ? el("button", { type: "button", class: "tonal" }, "Send jobs to the Docket") : null;
    if (send) send.addEventListener("click", async () => {
      try { const r = await api("POST", `/api/meetings/${m.id}/send`); m = r.meeting; draw(); alert(r.sent ? `${r.sent} ${r.sent === 1 ? "job" : "jobs"} on the Docket` : "Nothing new to send (jobs need someone first)."); } catch (err) { alert(err.message); }
    });
    box.replaceChildren(
      el("div", { class: "row" }, el("h2", { class: "grow" }, m.title), back),
      el("div", { class: "panel slide" }, el("div", { class: "muted small" }, `${at + 1} of ${m.slides.length}`), el("h1", { class: "slide-title" }, s.title), s.text ? el("p", { class: "slide-text" }, s.text) : null),
      el("h2", {}, "Jobs"), s.jobs.length ? jobs : el("p", { class: "muted small" }, "None yet."), d.canRun ? add("jobs", "Add a job") : null,
      el("h2", {}, "Notes"), ...(s.notes.length ? s.notes.map((n) => el("p", {}, "• " + n)) : [el("p", { class: "muted small" }, "None yet.")]), d.canRun ? add("notes", "Add a note") : null,
      el("div", { class: "row" }, prev, next, send));
  };
  draw();
}
document.addEventListener("keydown", (e) => {
  if (!meetingOpen || viewFromHash() !== "meetings" || e.target.matches("input, textarea, select")) return;
  if (e.key === "ArrowRight" || e.key === "ArrowLeft") {
    const btn = [...document.querySelectorAll("main:not([hidden]) button")].find((b) => b.textContent === (e.key === "ArrowRight" ? "Next ›" : "‹ Back"));
    if (btn && !btn.disabled) btn.click();
  }
});

// Rhythms: routines, and who's on which now.
async function renderRoutines(box) {
  let d;
  try { d = await api("GET", "/api/routines"); } catch (err) { box.replaceChildren(el("p", { class: "muted" }, err.message)); return; }
  if (box.dataset.module !== "routines") return; // the page changed while loading
  if (box.contains(document.activeElement) && document.activeElement.matches("input, textarea")) return;
  const name = (id) => (personById(id) || {}).name || "?";
  const cards = d.routines.map((r) => {
    const on = Object.entries(d.now).filter(([, id]) => id === r.id).map(([who]) => name(who));
    return el("div", { class: "panel list-card" }, el("div", { class: "list-head" }, el("div", { class: "title" }, r.title),
      el("span", { class: "muted small" }, [r.who.length ? r.who.map(name).join(", ") : "Everyone", r.starts && "from " + r.starts, (r.days || []).join(" ")].filter(Boolean).join(" · "))),
      on.length ? el("p", { class: "small", style: "color:var(--secondary);margin:4px 6px" }, "On now for " + on.join(", ")) : null,
      el("ul", { class: "list compact" }, ...r.items.map((i) => el("li", { class: i.done ? "done-item" : "" }, el("span", { class: "item-check" }, el("span", {}, (i.done ? "✓ " : "") + (i.emoji ? i.emoji + " " : "") + i.text))))));
  });
  const title = el("input", { placeholder: "e.g. Bedtime", maxlength: "60" });
  const starts = el("input", { type: "time", value: "19:30" });
  const steps = el("textarea", { rows: "4", placeholder: "Steps, one per line" });
  const form = el("form", { class: "upkeep-add" }, el("div", { class: "row" }, el("label", { class: "grow" }, "Routine", title), el("label", {}, "Starts", starts)), steps, el("div", { class: "row" }, el("button", { type: "submit" }, "Make routine")));
  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    if (!title.value.trim()) return;
    try { await api("POST", "/api/routines", { title: title.value.trim(), starts: starts.value, items: steps.value.split("\n").map((x) => x.trim()).filter(Boolean) }); renderRoutines(box); } catch (err) { alert(err.message); }
  });
  box.replaceChildren(...(cards.length ? cards : [el("p", { class: "muted" }, "No routines yet.")]), ...(d.canEdit ? [el("h2", {}, "Make a routine"), form] : []));
}

// Pantry: this week's meals and the recipes.
let mealsWeek = "";
async function renderMeals(box) {
  let d;
  try { d = await api("GET", "/api/meals" + (mealsWeek ? "?week=" + mealsWeek : "")); } catch (err) { box.replaceChildren(el("p", { class: "muted" }, err.message)); return; }
  if (box.dataset.module !== "meals") return; // the page changed while loading
  if (box.contains(document.activeElement) && document.activeElement.matches("input, textarea")) return;
  mealsWeek = d.week;
  const meals = ["Breakfast", "Lunch", "Dinner"];
  const rows = d.days.map((day) => {
    const dt = new Date(day.date + "T12:00");
    return el("li", {}, el("span", { class: "feed-time muted small" }, dt.toLocaleDateString([], { weekday: "short", day: "numeric" })),
      el("div", { class: "grow meal-row" }, ...meals.map((m) => {
        const inp = el("input", { value: day.meals[m] || "", "aria-label": m, maxlength: "100", ...(d.canEdit ? {} : { readonly: "" }) });
        inp.addEventListener("change", async () => { try { await api("PUT", `/api/meals/${d.week}`, { date: day.date, meal: m, dish: inp.value }); } catch (err) { alert(err.message); } });
        return inp;
      })));
  });
  const shift = (by) => {
    const [y, w] = d.week.split("-W").map(Number);
    const jan4 = new Date(y, 0, 4); const mon = new Date(jan4); mon.setDate(jan4.getDate() - ((jan4.getDay() + 6) % 7) + (w - 1 + by) * 7);
    const t = new Date(mon); t.setDate(t.getDate() + 3); const y2 = t.getFullYear(); const j4 = new Date(y2, 0, 4);
    const wk = 1 + Math.round(((t - j4) / 86400000 - 3 + ((j4.getDay() + 6) % 7)) / 7);
    mealsWeek = `${y2}-W${String(wk).padStart(2, "0")}`; renderMeals(box);
  };
  const prev = el("button", { type: "button", class: "secondary" }, "‹ Last week"); prev.addEventListener("click", () => shift(-1));
  const next = el("button", { type: "button", class: "secondary" }, "Next week ›"); next.addEventListener("click", () => shift(1));
  const recipes = d.recipes.map((r) => {
    const shop = el("button", { type: "button", class: "link" }, "Add to Shopping");
    shop.addEventListener("click", async () => { try { const x = await api("POST", `/api/recipes/${r.id}/shop`); alert(`Added ${x.added} to Shopping.`); } catch (err) { alert(err.message); } });
    return el("li", {}, el("div", { class: "grow" }, el("div", { class: "title" }, r.title), el("div", { class: "sub" }, r.ingredients.join(", "))), shop);
  });
  const rt = el("input", { placeholder: "Recipe name", maxlength: "80" });
  const ing = el("textarea", { rows: "4", placeholder: "Ingredients, one per line" });
  const method = el("textarea", { rows: "3", placeholder: "Method" });
  const form = el("form", { class: "upkeep-add" }, rt, ing, method, el("div", { class: "row" }, el("button", { type: "submit" }, "Add recipe")));
  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    if (!rt.value.trim()) return;
    try { await api("POST", "/api/recipes", { title: rt.value.trim(), ingredients: ing.value.split("\n").map((x) => x.trim()).filter(Boolean), method: method.value }); renderMeals(box); } catch (err) { alert(err.message); }
  });
  box.replaceChildren(el("div", { class: "row", style: "align-items:center" }, prev, el("strong", {}, "Week " + Number(d.week.split("-W")[1])), next), el("div", { class: "meals-head" }, el("span"), ...meals.map((m) => el("span", { class: "muted small" }, m))), el("ul", { class: "list meals-week" }, ...rows),
    el("h2", {}, "Recipes"), recipes.length ? el("ul", { class: "list" }, ...recipes) : el("p", { class: "muted" }, "No recipes yet."),
    ...(d.canEdit ? [el("h2", {}, "Add a recipe"), form] : []));
}

// Pairing a family display: a one-time code for the display's FamLogic app.
document.getElementById("pair-display")?.addEventListener("click", async () => {
  const name = prompt("A name for the display", "Kitchen display");
  if (!name) return;
  try {
    const inv = await api("POST", "/api/admin/invites", { name: name.trim(), access: "display" });
    showLink(inv, "Pair the display", "On the display, open FamLogic and scan this code or type the code. It signs in as the family display: it shows the family's day, shares no location and posts nothing.");
    refresh();
  } catch (err) { alert(err.message); }
});
$("#rerun-setup")?.addEventListener("click", () => openSetup());

// Restore a backup zip: it replaces everything, so ask first; the server restarts to finish.
$("#restore-file")?.addEventListener("change", async (ev) => {
  const file = ev.target.files[0];
  ev.target.value = "";
  if (!file) return;
  if (!confirm(`Restore "${file.name}"?\n\nEverything on this server is replaced by the backup. What's here now is kept in backups/before-restore-… on the server.`)) return;
  const msg = $("#restore-msg");
  msg.textContent = "Uploading…";
  try {
    const res = await fetch("/api/admin/restore", { method: "POST", headers: { "Content-Type": "application/zip" }, body: file, credentials: "same-origin" });
    const out = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(out.error || `Restore failed (${res.status})`);
    msg.textContent = "Restoring and restarting…";
    const back = async () => { try { if ((await fetch("/healthz")).ok) { location.reload(); return; } } catch (_) {} setTimeout(back, 1500); };
    setTimeout(back, 2500);
  } catch (err) { msg.textContent = ""; alert(err.message); }
});

// The crest's letter, centred by its ink (blackletter capitals sit high and lean,
// so ordinary text centring leaves them off-centre): measured once the font is in,
// then drawn as SVG text at the measured spot.
function crestLetter(ch) {
  const span = el("span", {}, ch);
  if (ch === "★" || !document.fonts) return span;
  document.fonts.load("100px UnifrakturMaguntia").then(() => {
    const c = document.createElement("canvas").getContext("2d");
    c.font = "100px UnifrakturMaguntia";
    const m = c.measureText(ch);
    const L = m.actualBoundingBoxLeft, R = m.actualBoundingBoxRight, A = m.actualBoundingBoxAscent, D = m.actualBoundingBoxDescent;
    const k = 60 / Math.max(L + R, A + D, 1);
    const x = 50 - k * (R - L) / 2, y = 50 + k * (A - D) / 2;
    span.outerHTML = `<svg viewBox="0 0 100 100" width="100%" height="100%" aria-hidden="true"><text x="${x.toFixed(1)}" y="${y.toFixed(1)}" font-family="UnifrakturMaguntia" font-size="${(100 * k).toFixed(1)}" fill="currentColor">${ch}</text></svg>`;
  }).catch(() => {});
  return span;
}

function sosMark() { const s = el("span", { class: "sos-mark" }); s.innerHTML = icon("sos"); return s; }

// ---- Steward, from any page: a small round button in the corner (not on Chat,
// which has its own voice). It opens a panel that knows which page you're on, and
// the page redraws after each answer so changes show at once.
const STEWARD_PAGES = ["home", "thereabouts", "calendar", "todos", "chores", "routines", "meals", "family", "profiles", "homestead"];
function stewardAllowed() {
  if (!serverFeatures.includes("steward")) return false;
  if (isAdmin() || !me.member) return true;
  return ["parent", "grandparent"].includes(me.member.role) || serverFeatures.includes("steward-kids");
}
function stewardFab() {
  const fab = $("#steward-fab");
  if (!fab) return;
  if (!fab.childElementCount) { fab.innerHTML = icon("spark"); $("#steward-icon").innerHTML = icon("spark"); $("#steward-talk").innerHTML = icon("mic"); }
  $("#steward-talk").hidden = !serverFeatures.includes("voice-stt");
  const page = STEWARD_PAGES.includes(view) || (moduleInfo(view) && view !== "chat" && view !== "agents");
  fab.hidden = !(stewardAllowed() && page) || !$("#steward-panel").hidden;
  if (!page) $("#steward-panel").hidden = true;
  $("#steward-where").textContent = view === "home" ? "" : "· " + (moduleInfo(view) ? displayName(moduleInfo(view)) : view.charAt(0).toUpperCase() + view.slice(1));
}
// After Steward's latest answer: what it changed, with Undo.
let stewardUndo = { canUndo: false, changed: [] };
function stewardDraw(msgs, undo) {
  if (undo) stewardUndo = { canUndo: !!undo.canUndo, changed: undo.changed || [] };
  const list = $("#steward-msgs");
  list.replaceChildren(...msgs.map((m) => el("li", { class: m.role === "user" ? "mine" : "" }, m.text)));
  if (stewardUndo.canUndo && msgs.length && msgs[msgs.length - 1].role === "assistant") {
    const btn = el("button", { type: "button", class: "link" }, "Undo");
    btn.addEventListener("click", async () => {
      try { const res = await api("POST", "/api/steward/undo"); stewardDraw(res.messages || [], { canUndo: false }); stewardAfter(); }
      catch (err) { alert(err.message); }
    });
    list.append(el("li", { class: "hint changed" }, el("span", { class: "muted small" }, "Changed: " + stewardUndo.changed.join(", ")), btn));
  }
  if (!msgs.length) list.append(el("li", { class: "muted hint" }, "Tell Steward what to add, change or remove here (\"add milk and eggs to Shopping\", \"move the dentist to Friday at 3\"), or ramble off everything you need to do today and it sorts it into chores, the calendar and the Docket."));
  list.scrollTop = list.scrollHeight;
}
$("#steward-fab")?.addEventListener("click", async () => {
  $("#steward-panel").hidden = false;
  $("#steward-fab").hidden = true;
  $("#steward-title").textContent = displayName(moduleInfo("agents") || { name: "Steward", plain: "AI" });
  stewardDraw([]);
  try { const res = await api("GET", "/api/steward"); stewardDraw(res.messages || [], res); } catch (err) { stewardDraw([{ role: "assistant", text: err.message }]); }
  $("#steward-text").focus();
});
$("#steward-close")?.addEventListener("click", () => { $("#steward-panel").hidden = true; stewardFab(); });
$("#steward-clear")?.addEventListener("click", async () => { try { await api("DELETE", "/api/steward"); } catch (_) {} stewardDraw([], { canUndo: false }); });
// After Steward changes something, the page shows it at once.
function stewardAfter() { upkeepDrawn = listsDrawn = calDrawn = homeDrawn = profilesDrawn = homeDrawnH = ""; renderView(); }
$("#steward-text")?.addEventListener("keydown", (ev) => { if (ev.key === "Enter" && !ev.shiftKey) { ev.preventDefault(); $("#steward-form").requestSubmit(); } });
$("#steward-form")?.addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const box = $("#steward-text"), text = box.value.trim();
  if (!text || box.disabled) return;
  box.value = ""; box.disabled = true;
  await stewardSay(text);
  box.disabled = false; box.focus();
});
async function stewardSay(text) {
  const shown = [...$("#steward-msgs").querySelectorAll("li:not(.hint)")].map((li) => ({ role: li.classList.contains("mine") ? "user" : "assistant", text: li.textContent }));
  stewardDraw([...shown, { role: "user", text }, { role: "assistant", text: "Thinking…" }], { canUndo: false });
  try {
    const res = await api("POST", "/api/steward", { text, page: view });
    stewardDraw(res.messages || [], res);
    stewardAfter();
  } catch (err) { stewardDraw([...shown, { role: "user", text }, { role: "assistant", text: err.message }]); }
}

// Talk to Steward from the console: hold the microphone button, let go to send. The
// browser records 16 kHz mono and the server turns it into text (when speech is on).
let talkRec = null;
async function talkStart() {
  if (talkRec) return;
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    const ctx = new AudioContext({ sampleRate: 16000 });
    const src = ctx.createMediaStreamSource(stream);
    const node = ctx.createScriptProcessor(4096, 1, 1);
    const chunks = [];
    node.onaudioprocess = (e) => chunks.push(new Float32Array(e.inputBuffer.getChannelData(0)));
    src.connect(node); node.connect(ctx.destination);
    talkRec = { stream, ctx, node, chunks, started: Date.now() };
    $("#steward-talk").classList.add("listening");
  } catch (_) { alert("The browser couldn't use a microphone here (it needs https, and permission)."); }
}
async function talkStop() {
  const r = talkRec; talkRec = null;
  if (!r) return;
  $("#steward-talk").classList.remove("listening");
  r.node.disconnect(); r.stream.getTracks().forEach((t) => t.stop()); await r.ctx.close();
  if (Date.now() - r.started < 400) return; // a tap, not a hold
  const n = r.chunks.reduce((a, c) => a + c.length, 0);
  const buf = new ArrayBuffer(44 + n * 2), v = new DataView(buf);
  const str = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
  str(0, "RIFF"); v.setUint32(4, 36 + n * 2, true); str(8, "WAVE"); str(12, "fmt "); v.setUint32(16, 16, true);
  v.setUint16(20, 1, true); v.setUint16(22, 1, true); v.setUint32(24, 16000, true); v.setUint32(28, 32000, true);
  v.setUint16(32, 2, true); v.setUint16(34, 16, true); str(36, "data"); v.setUint32(40, n * 2, true);
  let o = 44;
  for (const c of r.chunks) for (const x of c) { v.setInt16(o, Math.max(-1, Math.min(1, x)) * 0x7fff, true); o += 2; }
  try {
    const res = await fetch("/api/voice/transcribe", { method: "POST", body: buf, credentials: "same-origin", headers: { "Content-Type": "audio/wav" } });
    const out = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(out.error || "Couldn't hear that");
    if (out.text && out.text.trim()) await stewardSay(out.text.trim());
  } catch (err) { alert(err.message); }
}
{
  const talk = $("#steward-talk");
  if (talk) {
    talk.addEventListener("pointerdown", (e) => { e.preventDefault(); talkStart(); });
    ["pointerup", "pointerleave", "pointercancel"].forEach((t) => talk.addEventListener(t, talkStop));
  }
}

// Dossier: everyone's profile (in their People note), editable by who may, and a
// printable page per person for babysitters and grandparents.
let profilesDrawn = "", profileOpen = "";
async function renderProfiles(box, force = false) {
  if (!force && box.contains(document.activeElement) && document.activeElement.matches("input, textarea")) return;
  let d;
  try { d = await api("GET", "/api/profiles"); } catch (err) { box.replaceChildren(el("p", { class: "muted" }, err.message)); return; }
  if (box.dataset.module !== "profiles") return;
  const sig = JSON.stringify([d, profileOpen]);
  if (!force && sig === profilesDrawn && box.childElementCount) return;
  profilesDrawn = sig;
  const card = (p) => {
    const editing = profileOpen === p.id;
    const head = el("div", { class: "profile-head" }, avatarOf(personById(p.id) || p), el("div", { class: "name" }, p.name));
    const print = el("button", { type: "button", class: "secondary" }, "Print");
    print.addEventListener("click", () => printProfile(p, d));
    head.append(print);
    if (p.canEdit && !editing) {
      const edit = el("button", { type: "button", class: "tonal" }, "Change");
      edit.addEventListener("click", () => { profileOpen = p.id; renderProfiles(box, true); });
      head.append(edit);
    }
    if (editing) {
      const fields = d.sections.map((sec) => {
        const f = sec === "Birthday" ? el("input", { type: "date", value: p.profile[sec] || "" })
          : el("textarea", { rows: sec === "Notes" || sec === "Emergency contacts" ? "3" : "2", placeholder: sec === "Emergency contacts" ? "- Grandma (Mom's mum): 07700 900123" : "One per line" }, p.profile[sec] || "");
        f.dataset.sec = sec;
        return el("label", {}, sec, f);
      });
      const save = el("button", { type: "submit" }, "Save");
      const cancel = el("button", { type: "button", class: "link" }, "Cancel");
      cancel.addEventListener("click", () => { profileOpen = ""; renderProfiles(box, true); });
      const form = el("form", { class: "upkeep-add" }, ...fields, el("div", { class: "row" }, save, cancel));
      form.addEventListener("submit", async (ev) => {
        ev.preventDefault();
        const profile = Object.fromEntries([...form.querySelectorAll("[data-sec]")].map((f) => [f.dataset.sec, f.value.trim()]));
        try { await api("PUT", `/api/profiles/${p.id}`, { profile }); profileOpen = ""; renderProfiles(box, true); } catch (err) { alert(err.message); }
      });
      return el("div", { class: "panel list-card" }, head, form);
    }
    const shown = d.sections.filter((sec) => p.profile[sec]);
    return el("div", { class: "panel list-card" }, head,
      shown.length ? el("dl", { class: "profile" }, ...shown.flatMap((sec) => [el("dt", {}, sec), el("dd", {}, sec === "Birthday" ? birthdayText(p.profile[sec]) : p.profile[sec])]))
        : el("p", { class: "muted small" }, p.canEdit ? "Nothing yet. Change adds allergies, medicines, emergency contacts and more." : "Nothing yet."));
  };
  box.replaceChildren(...d.people.map(card));
}

function birthdayText(v) {
  const d = new Date(v + "T12:00");
  if (isNaN(d)) return v;
  const now = new Date();
  let age = now.getFullYear() - d.getFullYear();
  if (now < new Date(now.getFullYear(), d.getMonth(), d.getDate())) age--;
  return d.toLocaleDateString([], { day: "numeric", month: "long", year: "numeric" }) + (age >= 0 && age < 130 ? ` (${age})` : "");
}

// A plain page for one person, for a babysitter: their profile and the parents' numbers.
function printProfile(p, d) {
  const esc = (t) => String(t).replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]));
  const rows = d.sections.filter((s) => p.profile[s]).map((s) => `<h2>${esc(s)}</h2><p>${esc(s === "Birthday" ? birthdayText(p.profile[s]) : p.profile[s]).replace(/\n/g, "<br>")}</p>`).join("");
  const parents = d.parents.filter((x) => x.name).map((x) => `<li>${esc(x.name)}${x.phone ? ": " + esc(x.phone) : ""}</li>`).join("");
  const w = window.open("", "_blank");
  if (!w) { alert("Allow pop-ups to print."); return; }
  w.document.write(`<!doctype html><meta charset="utf-8"><title>${esc(p.name)}</title><style>
    body{font:15px/1.45 system-ui,sans-serif;margin:32px;color:#222;max-width:640px}
    h1{font-family:"Liberation Serif",Georgia,serif;margin:0 0 4px}h2{font-size:.8rem;text-transform:uppercase;letter-spacing:.05em;color:#555;margin:18px 0 2px}
    p{margin:0}.box{border:2px solid #222;border-radius:12px;padding:10px 14px;margin-top:20px}ul{margin:4px 0;padding-left:18px}
    </style><h1>${esc(p.name)}</h1><div style="color:#555">${esc($("#family-name").textContent || "")}</div>${rows || "<p>No details yet.</p>"}
    ${parents ? `<div class="box"><h2 style="margin-top:0">Parents</h2><ul>${parents}</ul></div>` : ""}`);
  w.document.close();
  w.focus();
  setTimeout(() => w.print(), 200);
}

// Homestead: the home's profile (what it has, bin days). FamLogic keeps the upkeep
// chores and bin days in Upkeep and Datebook from it; each chore's history is the log.
let homeDrawnH = "";
const DAYS = [["", "None"], ["mon", "Monday"], ["tue", "Tuesday"], ["wed", "Wednesday"], ["thu", "Thursday"], ["fri", "Friday"], ["sat", "Saturday"], ["sun", "Sunday"]];
const EVERY_TEXT = (e) => ({ week: "every week", month: "every month", "2 weeks": "every 2 weeks", "3 months": "every 3 months", "6 months": "every 6 months", "12 months": "every year", "36 months": "every 3 years" }[e]
  || (DAYS.find(([k]) => k === e) ? "every " + DAYS.find(([k]) => k === e)[1] : e));
async function renderHomestead(box, force = false) {
  if (!force && box.contains(document.activeElement) && document.activeElement.matches("input, select")) return;
  let d;
  try { d = await api("GET", "/api/home"); } catch (err) { box.replaceChildren(el("p", { class: "muted" }, err.message)); return; }
  if (box.dataset.module !== "homestead") return;
  const sig = JSON.stringify(d);
  if (!force && sig === homeDrawnH && box.childElementCount) return;
  homeDrawnH = sig;
  const p = d.profile;
  // The upkeep it keeps: next due, last done, and Done.
  const jobs = d.jobs.slice().sort((a, b) => (a.due || "").localeCompare(b.due || ""));
  const jobRows = jobs.map((c) => {
    const done = el("button", { type: "button", class: "tonal" }, "Done");
    done.addEventListener("click", async () => { try { await api("POST", `/api/chores/${c.id}/done`, {}); renderHomestead(box, true); } catch (err) { alert(err.message); } });
    const last = c.done[0];
    return el("li", {}, el("div", { class: "grow" }, el("div", { class: "title" }, c.title),
      el("div", { class: "sub" }, [EVERY_TEXT(c.every), c.due && (c.due <= new Date().toISOString().slice(0, 10) ? "due now" : "next " + c.due), last ? "last done " + last.date : "not done yet"].filter(Boolean).join(" · "))), done);
  });
  const parts = [el("h2", {}, "Upkeep for the home"), jobRows.length ? el("ul", { class: "list" }, ...jobRows)
    : el("p", { class: "muted small" }, d.canEdit ? "Tick what your home has below, and its upkeep appears here, in Upkeep and on the calendar." : "Nothing set up yet.")];
  if (d.canEdit) {
    const tenure = el("select", {}, ...[["", "Not saying"], ["own", "We own it"], ["rent", "We rent it"]].map(([v, t]) => el("option", { value: v }, t)));
    tenure.value = p.tenure || "";
    const daySel = (v) => { const s = el("select", {}, ...DAYS.map(([k, t]) => el("option", { value: k }, t))); s.value = v || ""; return s; };
    const bins = daySel(p.bins), recycling = daySel(p.recycling);
    const recEvery = el("select", {}, el("option", { value: "week" }, "Every week"), el("option", { value: "2 weeks" }, "Every 2 weeks"));
    recEvery.value = p.recyclingEvery || "week";
    const feats = el("ul", { class: "list quiz" }, ...d.catalog.map((f) => {
      const box2 = el("input", { type: "checkbox", class: "switch", role: "switch", "data-key": f.key });
      box2.checked = p.features.includes(f.key);
      return el("li", {}, el("label", { class: "switch-label" }, box2, el("span", {}, f.name, el("span", { class: "muted small" }, " · " + f.jobs.map((j) => j.title + " " + EVERY_TEXT(j.every)).join("; ")))));
    }));
    const save = el("button", { type: "submit" }, "Save");
    const form = el("form", { class: "upkeep-add" },
      el("div", { class: "row" }, el("label", {}, "The home", tenure), el("label", {}, "Bins go out", bins), el("label", {}, "Recycling", recycling), el("label", {}, "How often", recEvery)),
      el("div", { class: "muted small" }, "What the home has (each adds its upkeep as a chore for anyone):"), feats, el("div", { class: "row" }, save));
    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const body = { tenure: tenure.value, bins: bins.value, recycling: recycling.value, recyclingEvery: recEvery.value,
        features: [...feats.querySelectorAll("input[data-key]")].filter((b) => b.checked).map((b) => b.dataset.key) };
      save.disabled = true;
      try { await api("PUT", "/api/home", body); renderHomestead(box, true); } catch (err) { alert(err.message); save.disabled = false; }
    });
    parts.push(el("h2", {}, "Your home"), form);
  }
  box.replaceChildren(...parts);
}

// Corkboard: the TV app and family displays. Parents start quiet time and send
// messages; the displays themselves are paired on the Devices page.
let corkDrawn = "";
// The TV's screen saver: the family album (drag photos, or a whole folder, onto the box) and a photo link.
function screensaverPanel(box, b) {
  const status = el("p", { class: "muted small" });
  const upload = async (files) => {
    const list = [...files].filter((f) => /^image\//.test(f.type));
    if (!list.length) { status.textContent = "Those aren't pictures."; return; }
    let done = 0, failed = 0;
    for (const f of list) {
      status.textContent = `Adding ${done + failed + 1} of ${list.length}…`;
      try {
        const res = await fetch(`/api/display/photos?name=${encodeURIComponent(f.name)}`, { method: "POST", body: f, credentials: "same-origin" });
        if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error || res.statusText);
        done++;
      } catch (err) { failed++; status.textContent = err.message; }
    }
    status.textContent = `Added ${done} ${done === 1 ? "photo" : "photos"}` + (failed ? `; ${failed} didn't go` : "") + ".";
    renderCorkboard(box, true);
  };
  const files = el("input", { type: "file", accept: "image/*", multiple: "", hidden: "" });
  const folder = el("input", { type: "file", accept: "image/*", multiple: "", webkitdirectory: "", hidden: "" });
  files.addEventListener("change", () => upload(files.files));
  folder.addEventListener("change", () => upload(folder.files));
  const pickFiles = el("button", { type: "button", class: "secondary" }, "Choose photos");
  pickFiles.addEventListener("click", () => files.click());
  const pickFolder = el("button", { type: "button", class: "secondary" }, "Choose a folder");
  pickFolder.addEventListener("click", () => folder.click());
  const drop = el("div", { class: "dropzone" }, el("div", {}, "Drop photos or a folder here"), el("div", { class: "row" }, pickFiles, pickFolder), files, folder, status);
  drop.addEventListener("dragover", (e) => { e.preventDefault(); drop.classList.add("over"); });
  drop.addEventListener("dragleave", () => drop.classList.remove("over"));
  drop.addEventListener("drop", async (e) => {
    e.preventDefault(); drop.classList.remove("over");
    // A dropped folder: read every picture inside it.
    const items = [...(e.dataTransfer.items || [])].map((i) => i.webkitGetAsEntry && i.webkitGetAsEntry()).filter(Boolean);
    if (items.some((it) => it.isDirectory)) {
      const out = [];
      const walk = (entry) => new Promise((res) => {
        if (entry.isFile) entry.file((f) => { out.push(f); res(); }, () => res());
        else { const r = entry.createReader(); const all = []; const read = () => r.readEntries(async (ents) => { if (!ents.length) { for (const x of all) await walk(x); res(); } else { all.push(...ents); read(); } }, () => res()); read(); }
      });
      for (const it of items) await walk(it);
      upload(out);
    } else upload(e.dataTransfer.files);
  });
  const photos = b.photos || [];
  const grid = el("div", { class: "photo-grid" }, ...photos.map((p) => {
    const rm = el("button", { type: "button", class: "photo-rm", "aria-label": "Remove" }, "×");
    rm.addEventListener("click", async () => { try { await api("DELETE", `/api/display/photos/${p.id}`); renderCorkboard(box, true); } catch (err) { alert(err.message); } });
    return el("div", { class: "photo" }, el("img", { src: `/api/display/photos/${p.id}`, alt: p.name || "", loading: "lazy" }), rm);
  }));
  const link = el("input", { placeholder: "https://… (an Immich shared album, a photo feed, or a picture link)" });
  const linkForm = el("form", { class: "upkeep-add" }, el("label", {}, "Photo link", link),
    el("div", { class: "row" }, el("button", { type: "submit", class: "tonal" }, "Use this link"), b.stream ? (() => { const x = el("button", { type: "button", class: "link" }, "Stop using the link"); x.addEventListener("click", async () => { await api("PUT", "/api/display/stream", { url: "" }).catch(() => {}); renderCorkboard(box, true); }); return x; })() : null));
  linkForm.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    try { await api("PUT", "/api/display/stream", { url: link.value.trim() }); renderCorkboard(box, true); } catch (err) { alert(err.message); }
  });
  return [el("h2", {}, "Screen saver"),
    el("p", { class: "muted small" }, "When the TV sits idle, it can show the family's photos, a photo link, a family clock or a calm theme. Choose which, and when, in the TV's Settings."),
    drop, photos.length ? el("p", { class: "muted small" }, `${photos.length} ${photos.length === 1 ? "photo" : "photos"} in the album.`) : null, grid,
    el("p", { class: "muted small" }, b.stream ? "A photo link is in use." : "Or show pictures from a link: an Immich shared album (share/…), an RSS or Atom feed of photos, a web page of pictures, or a single picture that changes (like a webcam)."),
    linkForm];
}

let corkTarget = "";
async function renderCorkboard(box, force = false) {
  if (!force && box.contains(document.activeElement) && document.activeElement.matches("input, textarea")) return;
  let b;
  try { b = await api("GET", "/api/display/board"); } catch (err) { box.replaceChildren(el("p", { class: "muted" }, err.message)); return; }
  if (box.dataset.module !== "display") return;
  const sig = JSON.stringify([b.quiet, b.messages, b.apps, b.photos, b.stream, b.displays]);
  if (!force && sig === corkDrawn && box.childElementCount) return;
  corkDrawn = sig;
  const act = async (method, path, body) => { try { await api(method, path, body); renderCorkboard(box, true); } catch (err) { alert(err.message); } };
  const parts = [];
  // With more than one display: which one quiet time and messages go to ("" = all of them).
  const displays = b.displays || [];
  let to = corkTarget;
  if (displays.length > 1 && b.canControl) {
    if (!displays.some((d) => d.id === to)) to = corkTarget = "";
    const pick = el("select", {}, el("option", { value: "" }, "All displays"), ...displays.map((d) => el("option", { value: d.id }, d.name)));
    pick.value = to;
    pick.addEventListener("change", () => { corkTarget = pick.value; renderCorkboard(box, true); });
    parts.push(el("label", { class: "cork-target" }, "Display", pick));
  }
  parts.push(el("h2", {}, "Quiet time"));
  if (b.quiet.on && (!b.quiet.for || !to || b.quiet.for === to)) {
    const end = el("button", { type: "button", class: "tonal" }, "End quiet time");
    end.addEventListener("click", () => act("DELETE", "/api/display/quiet"));
    parts.push(el("p", {}, b.quiet.until ? `On until ${new Date(b.quiet.until).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}` : "On until someone ends it", b.quiet.by ? `, started by ${b.quiet.by}.` : "."), b.canControl ? end : null);
  } else {
    parts.push(el("p", { class: "muted small" }, "The TV and family displays show a calm quiet-time screen (nap time)."));
    if (b.canControl) parts.push(el("div", { class: "row" }, ...[[30, "30 min"], [60, "1 hour"], [120, "2 hours"], [0, "Until I end it"]].map(([m, t]) => {
      const btn = el("button", { type: "button", class: "secondary" }, t);
      btn.addEventListener("click", () => act("POST", "/api/display/quiet", { minutes: m, to }));
      return btn;
    })));
  }
  parts.push(el("h2", {}, "Message to the TV"));
  if (b.canControl) {
    const text = el("input", { maxlength: "200", placeholder: "e.g. Inside voices please, the baby's asleep" });
    const send = el("button", { type: "submit" }, "Send to the TV");
    const form = el("form", { class: "upkeep-add" }, el("label", {}, "Message", text), el("div", { class: "row" }, send));
    form.addEventListener("submit", (ev) => { ev.preventDefault(); if (text.value.trim()) act("POST", "/api/display/messages", { text: text.value.trim(), to }); });
    parts.push(form);
  }
  if (b.messages && b.messages.length) parts.push(el("ul", { class: "list" }, ...b.messages.map((m) => el("li", {}, el("div", { class: "grow" }, el("div", { class: "title" }, m.text),
    el("div", { class: "sub" }, `${m.from} · ${new Date(m.time).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })} · showing until someone presses OK`))))));
  if (b.canControl && b.apps && b.apps.length) {
    parts.push(el("h2", {}, "Pictures for the TV's apps"),
      el("p", { class: "muted small" }, "Each app on a TV's launcher shows its own banner. Upload a picture to use instead (it fills the tile, 16:9, like 1280 × 720)."));
    parts.push(el("ul", { class: "list" }, ...b.apps.map((a) => {
      const path = `/api/display/apps/${encodeURIComponent(a.pkg)}/image`;
      const file = el("input", { type: "file", accept: "image/png,image/jpeg,image/webp,image/gif", hidden: "" });
      file.addEventListener("change", async () => {
        const f = file.files[0];
        if (!f) return;
        try {
          const res = await fetch(path, { method: "PUT", body: f, credentials: "same-origin" });
          if (!res.ok) { let msg = res.statusText; try { msg = (await res.json()).error || msg; } catch (_) {} throw new Error(msg); }
          renderCorkboard(box, true);
        } catch (err) { alert(err.message); }
      });
      const up = el("button", { type: "button", class: "secondary" }, a.image ? "Change" : "Upload picture");
      up.addEventListener("click", () => file.click());
      const rm = a.image ? el("button", { type: "button", class: "secondary" }, "Remove") : null;
      if (rm) rm.addEventListener("click", () => act("DELETE", path));
      const pic = a.image ? el("img", { class: "tv-pic", src: `${path}?v=${a.image}`, alt: "" }) : el("div", { class: "tv-pic" }, "App's own");
      return el("li", {}, pic, el("div", { class: "grow" }, el("div", { class: "title" }, a.label), el("div", { class: "sub" }, a.pkg)), file, up, rm);
    })));
  }
  if (b.canControl) parts.push(...screensaverPanel(box, b));
  parts.push(el("h2", {}, "Setting up a TV"), el("p", { class: "muted small" }, "Install famlogic-tv.apk on the TV (with Obtainium or a file manager). On the Devices page, choose Pair a family display, then type the server address and the code on the TV. To make FamLogic the TV's home screen on Google TV, see the guide in the wiki (Corkboard)."));
  box.replaceChildren(...parts.filter(Boolean));
}
