// Fuse's website: the latest release from GitHub, your platform first, and the same moves as the
// app: arrow keys or a controller pick tiles, A or Enter opens one.
(() => {
  "use strict";

  const REPO = "MAtiyaaa/fuse";
  const API = `https://api.github.com/repos/${REPO}/releases/latest`;
  const RELEASES = `https://github.com/${REPO}/releases/latest`;
  const SITE = "https://matiyaaa.github.io/fuse/";
  const CACHE_KEY = "fuse.release";
  const CACHE_MS = 10 * 60 * 1000;
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;

  const $ = (s, root = document) => root.querySelector(s);
  const $$ = (s, root = document) => Array.from(root.querySelectorAll(s));

  // Downloads, by the end of the file's name (see .github/workflows/release.yml).
  const ASSETS = {
    android: { suffix: "-android.apk", detail: "APK" },
    windows: { suffix: "-windows-x64.msi", detail: "Installer" },
    macos: { suffix: "-macos-arm64.dmg", detail: "Apple silicon" },
    linux: { suffix: "-x86_64.AppImage", detail: "AppImage" },
    "macos-intel": { suffix: "-macos-x64.dmg" },
    "windows-zip": { suffix: "-windows-x64.zip" },
    sums: { suffix: "SHA256SUMS.txt" },
  };

  // ------------------------------------------------------------------ release

  async function loadRelease() {
    try {
      const cached = JSON.parse(sessionStorage.getItem(CACHE_KEY) || "null");
      if (cached && Date.now() - cached.at < CACHE_MS) return cached.data;
    } catch (e) { /* storage may be off */ }
    try {
      const r = await fetch(API, { headers: { Accept: "application/vnd.github+json" } });
      if (r.ok) {
        const data = await r.json();
        try { sessionStorage.setItem(CACHE_KEY, JSON.stringify({ at: Date.now(), data })); } catch (e) { /* ignore */ }
        return data;
      }
    } catch (e) { /* offline or rate limited: the copy made with the site */ }
    try {
      const r = await fetch("release.json", { cache: "no-store" });
      if (r.ok) return await r.json();
    } catch (e) { /* the page keeps what it was built with */ }
    return null;
  }

  function ago(iso) {
    const then = Date.parse(iso);
    if (!then) return "";
    const s = (then - Date.now()) / 1000;
    const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
    const units = [["year", 31536000], ["month", 2592000], ["week", 604800], ["day", 86400], ["hour", 3600], ["minute", 60]];
    for (const [unit, size] of units) if (Math.abs(s) >= size) return rtf.format(Math.round(s / size), unit);
    return rtf.format(0, "minute");
  }

  function applyRelease(rel) {
    if (!rel || !rel.tag_name) return;
    const version = rel.tag_name.replace(/^v/, "");
    const name = (rel.name || "").split(" - ").slice(1).join(" - ").trim();
    const url = rel.html_url || RELEASES;
    const assets = rel.assets || [];
    const find = (suffix) => assets.find((a) => a.name.endsWith(suffix));

    $$("[data-version-chip]").forEach((el) => { el.textContent = version; });
    const eyebrow = $("[data-eyebrow]");
    if (eyebrow) eyebrow.textContent = name ? `Fuse ${version} · ${name}` : `Fuse ${version}`;
    const released = $("[data-released]");
    if (released) released.textContent = rel.published_at ? `Fuse ${version}, released ${ago(rel.published_at)}` : `Fuse ${version}`;
    $$("[data-notes]").forEach((el) => { el.href = url; });

    for (const tile of $$(".platform")) {
      const key = tile.dataset.platform;
      const asset = find(ASSETS[key].suffix);
      const detail = $("[data-detail]", tile);
      if (asset) {
        tile.href = asset.browser_download_url;
        tile.setAttribute("download", "");
        if (detail) { detail.textContent = `${ASSETS[key].detail} · ${size(asset.size)}`; detail.classList.remove("is-waiting"); }
      } else {
        // Desktop builds attach a little after the APK.
        tile.href = url;
        tile.removeAttribute("download");
        if (detail) { detail.textContent = "Still building, back soon"; detail.classList.add("is-waiting"); }
      }
    }
    for (const link of $$("[data-asset]")) {
      const asset = find(ASSETS[link.dataset.asset].suffix);
      link.href = asset ? asset.browser_download_url : url;
    }
    highlights(rel.body || "", version);
  }

  function size(bytes) {
    if (!bytes) return "";
    const mb = bytes / (1024 * 1024);
    return mb >= 100 ? `${Math.round(mb)} MB` : `${mb.toFixed(1)} MB`;
  }

  // The release's headlines: its top-level bullets that start with a bold title, each with the
  // words that follow it (continuation lines included, nested bullets left out).
  function highlights(body, version) {
    const items = [];
    let open = null;
    for (const line of body.split(/\r?\n/)) {
      const m = /^- \*\*(.+?)\*\*\s*(.*)$/.exec(line);
      if (m) {
        if (items.length === 8) break;
        open = { title: m[1].replace(/[.:]$/, ""), text: m[2] };
        items.push(open);
      } else if (open && /^ {2}\S/.test(line) && !/^ {2}-/.test(line)) {
        open.text += " " + line.trim();
      } else {
        open = null;
      }
    }
    const row = $("[data-highlights]");
    if (!row || items.length === 0) return;
    row.textContent = "";
    const plain = (s) => s.replace(/\[([^\]]+)\]\([^)]+\)/g, "$1").replace(/[*_`]/g, "").trim();
    for (const item of items) {
      const card = document.createElement("a");
      card.className = "tile highlight";
      card.href = `https://github.com/${REPO}/releases/tag/v${version}`;
      const b = document.createElement("b");
      b.textContent = item.title;
      card.append(b);
      const text = plain(item.text).replace(/:$/, ".");
      if (text) {
        const span = document.createElement("span");
        span.textContent = text;
        card.append(span);
      }
      row.append(card);
    }
    const title = $("[data-new-title]");
    if (title) title.textContent = `New in ${version}`;
    $("#new").hidden = false;
  }

  // ------------------------------------------------------------------ platform

  function detectPlatform() {
    const ua = navigator.userAgent || "";
    const hint = (navigator.userAgentData && navigator.userAgentData.platform) || "";
    if (/android/i.test(ua) || /android/i.test(hint)) return "android";
    if (/iPhone|iPad|iPod/.test(ua)) return null;
    if (/win/i.test(hint) || /Windows/.test(ua)) return "windows";
    if (/mac/i.test(hint) || /Macintosh|Mac OS X/.test(ua)) return "macos";
    if (/linux|chrome os/i.test(hint) || /Linux|X11|CrOS/.test(ua)) return "linux";
    return null;
  }

  function placeMine() {
    const mine = detectPlatform();
    if (!mine) return null;
    const tile = $(`.platform[data-platform="${mine}"]`);
    if (!tile) return null;
    tile.classList.add("is-here");
    tile.parentElement.prepend(tile);
    return tile;
  }

  // ------------------------------------------------------------------ themes

  async function loadThemes() {
    const row = $("[data-themes]");
    if (!row) return;
    let list = [];
    try {
      const r = await fetch("themes/index.json");
      if (r.ok) list = await r.json();
    } catch (e) { /* the section stays empty */ }
    if (!list.length) { $("#themes").hidden = true; return; }
    for (const t of list) row.append(themeCard(t));
  }

  function themeCard(t) {
    const card = document.createElement("div");
    card.className = "tile theme";
    card.tabIndex = 0;
    const s = card.style;
    s.setProperty("--t-bg", t.colors.background);
    s.setProperty("--t-surface", t.colors.surface);
    s.setProperty("--t-raised", t.colors.surfaceRaised);
    s.setProperty("--t-accent", t.colors.accent);
    s.setProperty("--t-second", t.secondary || t.colors.accent);
    s.setProperty("--t-text", t.colors.text);
    s.setProperty("--t-corner", { soft: "28%", round: "34%", sharp: "10%", pill: "50%" }[t.corners] || "28%");
    s.setProperty("--glow", t.colors.accent);

    const preview = document.createElement("div");
    preview.className = "preview";
    const bg = document.createElement("div");
    bg.className = `bg bg-${t.style}`;
    if (t.style === "wave") {
      bg.innerHTML = `<svg viewBox="0 0 320 90" preserveAspectRatio="none" aria-hidden="true">` +
        [0, 1, 2].map((i) => `<path d="M0 ${45 + i * 6} C 60 ${10 + i * 8}, 110 ${80 - i * 6}, 170 ${45 + i * 4} S 280 ${15 + i * 10}, 320 ${45 + i * 6}" fill="none" stroke="${i === 1 ? (t.secondary || "#fff") : t.colors.accent}" stroke-opacity="${0.55 - i * 0.12}" stroke-width="${1.4 + i * 0.5}"/>`).join("") +
        `</svg>`;
    }
    preview.append(bg);
    preview.insertAdjacentHTML("beforeend", `<div class="hudline"><i></i><i></i><i></i><i></i><i></i></div><div class="minis"><i></i><i></i><i></i></div>`);
    const pname = document.createElement("div");
    pname.className = "pname";
    pname.textContent = t.name;
    preview.append(pname);

    const meta = document.createElement("div");
    meta.className = "meta-line";
    const text = document.createElement("div");
    const b = document.createElement("b");
    b.textContent = t.name;
    const small = document.createElement("small");
    small.textContent = t.author && !t.builtIn ? `by ${t.author}` : (t.tagline || "");
    text.append(b, small);
    const copy = document.createElement("button");
    copy.className = "copy";
    copy.type = "button";
    copy.textContent = "Copy link";
    copy.addEventListener("click", (e) => { e.stopPropagation(); copyLink(t, copy); });
    meta.append(text, copy);
    card.append(preview, meta);
    card.addEventListener("keydown", (e) => { if (e.key === "Enter") copyLink(t, copy); });
    card.dataset.activate = "copy";
    return card;
  }

  async function copyLink(t, button) {
    const link = SITE + t.path;
    try {
      await navigator.clipboard.writeText(link);
      button.textContent = "Copied";
      toast(`Copied. In Fuse: Settings, Appearance, Theme, Add a theme`);
      setTimeout(() => { button.textContent = "Copy link"; }, 2200);
    } catch (e) {
      window.prompt("Copy this link", link);
    }
  }

  let toastTimer = 0;
  function toast(message) {
    const el = $("[data-toast]");
    if (!el) return;
    el.textContent = message;
    el.classList.add("is-shown");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove("is-shown"), 3200);
  }

  // ------------------------------------------------------------------ moving around like the app

  let current = null;

  function tiles() { return $$("[data-row]").filter((r) => !r.closest("[hidden]")).map((r) => $$(".tile", r)).filter((t) => t.length); }

  function choose(tile, scroll = true) {
    if (!tile) return;
    if (current) current.classList.remove("is-focused");
    current = tile;
    tile.classList.add("is-focused");
    tile.focus({ preventScroll: true });
    if (scroll) tile.scrollIntoView({ behavior: reduced ? "auto" : "smooth", block: "center", inline: "nearest" });
  }

  function move(dx, dy) {
    const rows = tiles();
    if (!rows.length) return;
    if (!current || !document.body.contains(current)) { choose(rows[0][0]); return; }
    const r = rows.findIndex((row) => row.includes(current));
    if (r < 0) { choose(rows[0][0]); return; }
    if (dx) {
      const i = rows[r].indexOf(current) + dx;
      if (i >= 0 && i < rows[r].length) choose(rows[r][i]);
      return;
    }
    const next = rows[r + dy];
    if (!next) return;
    // The tile in the next row nearest the one you're on, as the app's spatial moves do.
    const x = current.getBoundingClientRect();
    const cx = x.left + x.width / 2;
    let best = next[0];
    let bestD = Infinity;
    for (const t of next) {
      const b = t.getBoundingClientRect();
      const d = Math.abs(b.left + b.width / 2 - cx);
      if (d < bestD) { best = t; bestD = d; }
    }
    choose(best);
  }

  function activate() {
    if (!current) return;
    if (current.dataset.activate === "copy") { const b = $(".copy", current); if (b) b.click(); return; }
    current.click();
  }

  function usePad(on) { document.body.classList.toggle("uses-pad", on); }

  document.addEventListener("keydown", (e) => {
    if (e.target && /INPUT|TEXTAREA|SELECT/.test(e.target.tagName)) return;
    const keys = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
    if (keys[e.key]) {
      e.preventDefault();
      usePad(true);
      move(...keys[e.key]);
    } else if (e.key === "Enter" && current && document.activeElement === current && current.dataset.activate !== "copy" && current.tagName !== "A") {
      activate();
    }
  });
  document.addEventListener("pointermove", () => usePad(false), { passive: true });
  document.addEventListener("pointerover", (e) => {
    const tile = e.target.closest && e.target.closest(".tile");
    if (tile && tile !== current && e.pointerType === "mouse") choose(tile, false);
  });
  document.addEventListener("focusin", (e) => {
    const tile = e.target.closest && e.target.closest(".tile");
    if (tile && tile !== current) choose(tile, false);
  });

  // A controller: the D-pad or left stick moves, A opens. Held directions repeat like the app's.
  let polling = false;
  const held = {};
  function pad() {
    const gp = Array.from(navigator.getGamepads ? navigator.getGamepads() : []).find(Boolean);
    if (!gp) { polling = false; return; }
    const now = performance.now();
    const b = (i) => gp.buttons[i] && gp.buttons[i].pressed;
    const ax = gp.axes[0] || 0;
    const ay = gp.axes[1] || 0;
    const dirs = { left: b(14) || ax < -0.5, right: b(15) || ax > 0.5, up: b(12) || ay < -0.5, down: b(13) || ay > 0.5, a: b(0) };
    for (const [k, on] of Object.entries(dirs)) {
      if (!on) { delete held[k]; continue; }
      const first = !(k in held);
      if (first || now >= held[k]) {
        held[k] = now + (first ? 380 : 110);
        usePad(true);
        if (k === "a") { if (first) activate(); continue; }
        move(k === "left" ? -1 : k === "right" ? 1 : 0, k === "up" ? -1 : k === "down" ? 1 : 0);
      }
    }
    requestAnimationFrame(pad);
  }
  window.addEventListener("gamepadconnected", () => { if (!polling) { polling = true; usePad(true); requestAnimationFrame(pad); } });

  // ------------------------------------------------------------------ the top line

  function clock() {
    const el = $("[data-clock]");
    if (el) el.textContent = new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" }).format(new Date());
  }

  function followSections() {
    const tabs = $$(".tab[data-tab]");
    const sections = tabs.map((t) => document.getElementById(t.dataset.tab)).filter(Boolean);
    const io = new IntersectionObserver((entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue;
        tabs.forEach((t) => t.classList.toggle("is-active", t.dataset.tab === e.target.id));
      }
    }, { rootMargin: "-40% 0px -55% 0px" });
    sections.forEach((s) => io.observe(s));
  }

  // ------------------------------------------------------------------ start

  clock();
  setInterval(clock, 15000);
  followSections();
  const mine = placeMine();
  if (mine) choose(mine, false);
  loadRelease().then(applyRelease);
  loadThemes();
})();
