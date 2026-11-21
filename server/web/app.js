// Thereabouts web dashboard. Plain JS, no build step.
"use strict";

const $ = (sel) => document.querySelector(sel);
const REFRESH_MS = 10000;
const STALE_MS = 30 * 60 * 1000; // a location older than this is shown faded

let map, refreshTimer, fittedOnce = false;
let circles = [];       // all circles on the server
let lastMembers = [];   // everyone, from the last refresh
let places = [];        // all places
const placeLayers = new Map(); // place id -> Leaflet layer group
let trailLayer = null;  // the clicked person's trail, if they keep one
let serverFeatures = []; // what the server can do, e.g. "geocode"
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
      (m.loc ? ` · last seen ${ago(m.loc.time)}, click to show` : " · no location yet"))));
}

function describe(m) {
  if (m.place && !m.sosSince && !m.paused && m.loc) {
    return `At ${m.place} · ${ago(m.loc.time)}` + (m.loc.battery >= 0 ? ` · ${m.loc.battery}%${m.loc.charging ? " ⚡" : ""}` : "");
  }
  if (m.sosSince) return `SOS since ${new Date(m.sosSince).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}` + (m.loc ? ` · ${ago(m.loc.time)}` : "");
  if (m.paused) return "Sharing paused";
  if (!m.loc) return "No location yet";
  const parts = [ago(m.loc.time)];
  if (m.loc.battery >= 0) parts.push(`${m.loc.battery}%${m.loc.charging ? " ⚡" : m.loc.saving ? " (saving battery)" : ""}`);
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
const darkQuery = matchMedia("(prefers-color-scheme: dark)");

function applyTiles() {
  if (!map || !serverTiles) return;
  const st = MAP_STYLES.find((s) => s.id === mapStyle) || MAP_STYLES[0];
  const dark = darkQuery.matches;
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
darkQuery.addEventListener("change", applyTiles);
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
    const avatar = el("div", { class: "avatar" + (stale ? " stale" : "") + (m.sosSince ? " sos" : "") + (m.emoji ? " emoji" : ""), style: `background:${m.color}` }, m.emoji || initial(m.name));
    const li = el("li", { class: m.loc ? "clickable" : "" },
      avatar,
      el("div", { class: "grow" },
        el("div", { class: "title" }, m.name),
        el("div", { class: "sub" }, describe(m)),
        el("div", { class: "sub" }, account(m)),
        health(m),
        circles.length > 1 ? el("div", { class: "chips" }, ...(m.circles || []).map((id) =>
          el("span", {
            class: "chip" + ((m.hiddenFrom || []).includes(id) ? " hidden-from" : ""),
            title: (m.hiddenFrom || []).includes(id) ? `Not sharing their location with ${circleName(id)}` : "",
          }, circleName(id)))) : null,
      ),
      moreButton(`Options for ${m.name}`, [
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
      html: `<div class="avatar${stale ? " stale" : ""}${m.sosSince ? " sos" : ""}${m.emoji ? " emoji" : ""}" style="background:${m.color}"></div>`,
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
    const [data, invites] = await Promise.all([api("GET", "/api/admin/members"), api("GET", "/api/admin/invites")]);
    $("#family-name").textContent = data.server.name;
    $("#server-version").textContent = `Thereabouts server ${data.server.version}`;
    serverFeatures = data.server.features || [];
    const sos = data.members.filter((m) => m.sosSince);
    document.title = (sos.length ? "🆘 SOS · " : "") + `${data.server.name} · Thereabouts`;
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
      moreButton(`Options for ${c.name}`, [
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
    const { points } = await api("GET", `/api/admin/members/${encodeURIComponent(m.id)}/trail`);
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
    moreButton(`Options for ${p.name}`, [
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
    const { trips } = await api("GET", `/api/admin/members/${encodeURIComponent(m.id)}/trips`);
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
