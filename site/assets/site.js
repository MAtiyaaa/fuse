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
    $$("[data-updates-link]").forEach((el) => { el.href = `#v${version}`; });

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
    themeFilter(list);
  }

  // Dark or bright, from the room colour itself, so a community theme sorts itself too.
  function isBright(t) {
    const m = /^#?([0-9a-f]{6})/i.exec((t.colors && t.colors.background) || "");
    if (!m) return false;
    const n = parseInt(m[1], 16);
    const ch = (v) => { const x = v / 255; return x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4; };
    return 0.2126 * ch((n >> 16) & 255) + 0.7152 * ch((n >> 8) & 255) + 0.0722 * ch(n & 255) > 0.4;
  }

  // All, Dark and Bright: a segmented switch whose thumb glides to the choice.
  function themeFilter(list) {
    const seg = $("[data-theme-filter]");
    const cards = $$(".theme", $("[data-themes]"));
    const bright = list.map(isBright);
    const counts = { all: list.length, dark: bright.filter((b) => !b).length, bright: bright.filter(Boolean).length };
    const total = $("[data-theme-count]");
    if (total) total.textContent = String(list.length);
    cards.forEach((c, i) => { c.dataset.tone = bright[i] ? "bright" : "dark"; });
    if (!seg) return;
    for (const [k, v] of Object.entries(counts)) { const b = $(`[data-count="${k}"]`, seg); if (b) b.textContent = String(v); }
    seg.hidden = false;
    const glide = $(".seg-glide", seg);
    const place = (animate) => {
      const on = $('.seg-btn[aria-pressed="true"]', seg);
      if (!on || !glide) return;
      glide.classList.toggle("is-moving", !!animate && !reduced);
      glide.style.setProperty("--gx", `${on.offsetLeft}px`);
      glide.style.setProperty("--gw", `${on.offsetWidth}px`);
    };
    for (const b of $$(".seg-btn", seg)) {
      b.addEventListener("click", () => {
        const f = b.dataset.filter;
        for (const o of $$(".seg-btn", seg)) o.setAttribute("aria-pressed", String(o === b));
        place(true);
        const shown = [];
        for (const c of cards) {
          c.hidden = !(f === "all" || c.dataset.tone === f);
          if (!c.hidden) shown.push(c);
        }
        if (current && current.hidden) { current.classList.remove("is-focused"); current = null; }
        revealAll(shown);
      });
    }
    place(false);
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => place(false));
    window.addEventListener("resize", () => place(false), { passive: true });
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

    const frame = document.createElement("div");
    frame.className = "frame";
    const preview = document.createElement("div");
    preview.className = "preview";
    const bg = document.createElement("div");
    bg.className = `bg bg-${t.style}`;
    if (t.style === "wave") {
      bg.innerHTML = `<svg viewBox="0 0 320 90" preserveAspectRatio="none" aria-hidden="true">` +
        [0, 1, 2].map((i) => `<path d="M0 ${45 + i * 6} C 60 ${10 + i * 8}, 110 ${80 - i * 6}, 170 ${45 + i * 4} S 280 ${15 + i * 10}, 320 ${45 + i * 6}" fill="none" stroke="${i === 1 ? (t.secondary || "#fff") : t.colors.accent}" stroke-opacity="${0.55 - i * 0.12}" stroke-width="${1.4 + i * 0.5}"/>`).join("") +
        `</svg>`;
    } else if (SCENES[t.style]) {
      bg.innerHTML = SCENES[t.style](`scene${++sceneId}`);
    }
    preview.append(bg);
    preview.insertAdjacentHTML("beforeend", `<div class="hudline"><i></i><i></i><i></i><i></i><i></i></div><div class="minis"><i></i><i></i><i></i></div>`);
    const pname = document.createElement("div");
    pname.className = "pname";
    pname.textContent = t.name;
    preview.append(pname);

    const spark = document.createElement("i");
    spark.className = "spark";
    spark.setAttribute("aria-hidden", "true");
    frame.append(preview, spark);

    const meta = document.createElement("div");
    meta.className = "meta-line";
    const text = document.createElement("div");
    text.className = "meta-text";
    const b = document.createElement("b");
    b.textContent = t.name;
    const small = document.createElement("small");
    small.textContent = t.author && !t.builtIn ? `by ${t.author}` : (t.tagline || "");
    text.append(b, small);
    const copy = document.createElement("button");
    copy.className = "copy";
    copy.type = "button";
    copy.setAttribute("aria-label", `Copy a link to ${t.name}`);
    copy.innerHTML = LINK_ICON;
    const label = document.createElement("span");
    label.textContent = "Copy link";
    copy.append(label);
    copy.addEventListener("click", (e) => { e.stopPropagation(); copyLink(t, copy); });
    meta.append(text, copy);
    card.append(frame, meta);
    card.addEventListener("keydown", (e) => { if (e.key === "Enter") copyLink(t, copy); });
    card.dataset.activate = "copy";
    return card;
  }

  // Backgrounds CSS can't draw alone, as small SVGs over the card (320 by 200, the card's shape).
  // Colours come from the card's --t-* properties, so one drawing serves every theme that uses it.
  let sceneId = 0;
  const full = (body, cls = "") => `<svg class="scene ${cls}" viewBox="0 0 320 200" preserveAspectRatio="none" aria-hidden="true">${body}</svg>`;
  const f1 = (v) => v.toFixed(1);
  const PETAL = "M-5 0C-3.2-3.6 1.2-4.4 4.4-1.7Q5.3-.8 3.7 0Q5.3.8 4.4 1.7C1.2 4.4-3.2 3.6-5 0Z";
  const SCENES = {
    petals: () => full([
      [34, 26, 7, 20], [88, 58, 6, -30], [150, 18, 8, 60], [206, 44, 9, 10], [262, 76, 8, -50], [300, 24, 6, 35],
      [118, 104, 8, -15], [232, 128, 10, 40], [176, 158, 7, -70], [284, 168, 9, 15], [58, 146, 6, 50], [198, 96, 6, 80],
    ].map(([x, y, k, a]) => `<path d="${PETAL}" transform="translate(${x} ${y}) rotate(${a}) scale(${f1(k / 7)})" style="fill:var(--t-accent)" opacity="${f1(0.4 + k / 20)}"/>`).join("")),

    horizon: (id) => {
      const hy = 128, sx = 224, sy = 116, r = 40;
      const gaps = [0, 1, 2, 3, 4].map((i) => `<rect x="${sx - r}" y="${f1(sy - 8 + i * 5.4)}" width="${2 * r}" height="${f1(0.7 + i * 0.75)}" style="fill:var(--t-bg)"/>`).join("");
      let grid = "";
      for (let i = -8; i <= 8; i++) grid += `<line x1="${sx}" y1="${hy}" x2="${sx + i * 30}" y2="200"/>`;
      for (let i = 1; i <= 8; i++) { const y = f1(hy + 72 * Math.pow(i / 8, 2.2)); grid += `<line x1="0" y1="${y}" x2="320" y2="${y}"/>`; }
      return full(
        `<defs><linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFD36E"/><stop offset="1" style="stop-color:var(--t-accent)"/></linearGradient>` +
        `<clipPath id="${id}c"><rect width="320" height="${hy}"/></clipPath></defs>` +
        `<g clip-path="url(#${id}c)"><circle cx="${sx}" cy="${sy}" r="${r}" fill="url(#${id})"/>${gaps}</g>` +
        `<polygon points="0,128 0,114 16,107 38,121 54,114 82,124 106,120 150,125 178,117 198,122 214,116 240,124 262,118 292,110 320,117 320,128" style="fill:var(--t-bg);stroke:var(--t-second)" stroke-opacity=".5" stroke-width=".8"/>` +
        `<g style="stroke:var(--t-second)" stroke-opacity=".55" stroke-width=".8">${grid}</g>` +
        `<line x1="0" y1="${hy}" x2="320" y2="${hy}" style="stroke:var(--t-accent)" stroke-width="1.4"/>`);
    },

    fireflies: () => {
      const trunks = [[20, 7, 0.3], [62, 10, 0.55], [116, 6, 0.3], [178, 12, 0.55], [228, 7, 0.3], [262, 16, 0.8], [302, 8, 0.55]]
        .map(([x, w, o]) => `<rect x="${x}" width="${w}" height="200" fill="#000" opacity="${o}"/>`).join("");
      let floor = "M0 200V184";
      for (let x = 0; x <= 320; x += 4) floor += `L${x} ${x % 8 ? 180 : 184}`;
      const flies = [[48, 120], [96, 150], [140, 96], [190, 138], [214, 84], [248, 160], [282, 108], [160, 172], [300, 150], [118, 130]]
        .map(([x, y], i) => `<circle cx="${x}" cy="${y}" r="5" style="fill:var(--t-accent)" opacity=".2"/><circle cx="${x}" cy="${y}" r="1.4" style="fill:var(--t-accent)" opacity="${i % 3 ? 1 : 0.45}"/>`).join("");
      return full(`${trunks}<path d="${floor}L320 184V200Z" fill="#000" opacity=".75"/>${flies}`);
    },

    caustics: () => {
      // A warped honeycomb, the warp a function of position so neighbouring cells still meet.
      const R = 17, w = Math.sqrt(3) * R;
      let d = "";
      for (let row = -1; row < 10; row++) for (let col = -1; col < 12; col++) {
        const cx = w * (col + (row & 1) / 2), cy = 1.5 * R * row;
        const pts = [];
        for (let k = 0; k < 6; k++) {
          const a = Math.PI / 180 * (30 + 60 * k);
          const x = cx + R * Math.cos(a), y = cy + R * Math.sin(a);
          pts.push(`${f1(x + 7 * Math.sin(y * 0.07 + x * 0.02))} ${f1(y + 7 * Math.cos(x * 0.06 - y * 0.03))}`);
        }
        d += `M${pts.join("L")}Z`;
      }
      return full(`<path d="${d}" fill="none" style="stroke:color-mix(in srgb, var(--t-accent) 50%, #fff)" stroke-opacity=".16" stroke-width="3" stroke-linejoin="round"/>` +
        `<path d="${d}" fill="none" style="stroke:color-mix(in srgb, var(--t-accent) 50%, #fff)" stroke-opacity=".42" stroke-width=".8" stroke-linejoin="round"/>`, "fade-tl");
    },

    lcd: (id) => {
      const P = 5;
      const hills = (base, a, f1x, f2x, ph) => {
        let d = "M0 200";
        for (let c = 0; c <= 64; c++) {
          const top = 200 - P * Math.max(1, Math.round(base + a * Math.sin(c * f1x + ph) + a * 0.45 * Math.sin(c * f2x + ph * 2)));
          d += `L${c * P} ${top}L${(c + 1) * P} ${top}`;
        }
        return `${d}L325 200Z`;
      };
      const cloud = ["....XXXX......", "..XXXXXXXX.XX.", ".XXXXXXXXXXXXX", "XXXXXXXXXXXXXX", ".XXXXXXXXXXXX."];
      let px = "";
      cloud.forEach((line, r) => [...line].forEach((ch, c) => { if (ch === "X") px += `<rect x="${150 + c * P}" y="${40 + r * P}" width="${P}" height="${P}"/>`; }));
      return full(
        `<defs><pattern id="${id}" width="${P}" height="${P}" patternUnits="userSpaceOnUse"><path d="M${P} 0V${P}H0" fill="none" stroke="#fff" stroke-opacity=".3" stroke-width=".7"/></pattern></defs>` +
        `<g fill="#fff" opacity=".55">${px}</g>` +
        `<path d="${hills(7, 2.5, 0.22, 0.55, 1.3)}" style="fill:color-mix(in srgb, var(--t-second) 40%, var(--t-bg))"/>` +
        `<path d="${hills(3, 2, 0.35, 0.8, 4.1)}" style="fill:color-mix(in srgb, var(--t-second) 70%, var(--t-bg))"/>` +
        `<rect width="320" height="200" fill="url(#${id})"/>`);
    },

    contours: () => {
      let thin = "", bold = "";
      for (const [cx, cy, s] of [[238, 128, 1], [110, 206, 0.7], [322, 18, 0.6]]) {
        for (let k = 1; k <= 10; k++) {
          const r = k * 10 * s;
          let d = "";
          for (let i = 0; i <= 40; i++) {
            const a = (i / 40) * Math.PI * 2;
            const rr = r * (1 + 0.14 * Math.sin(3 * a + k * 0.5) + 0.06 * Math.sin(5 * a - k));
            d += `${i ? "L" : "M"}${f1(cx + 1.35 * rr * Math.cos(a))} ${f1(cy + rr * Math.sin(a))}`;
          }
          if (k % 5 === 0) bold += `${d}Z`; else thin += `${d}Z`;
        }
      }
      return full(`<g fill="none" style="stroke:var(--t-text)"><path d="${thin}" stroke-opacity=".2" stroke-width=".7"/><path d="${bold}" stroke-opacity=".34" stroke-width="1.2"/></g>`, "fade-tl");
    },

    dunes: () => {
      // The app's dune: a long sunlit face down from each crest, a steep shaded face back up.
      const face = (u) => (u < 0.7 ? 0.5 + 0.5 * Math.cos(Math.PI * u / 0.7) : Math.pow((u - 0.7) / 0.3, 1.7));
      const layers = [[0.6, 0.035, 0.5, 15], [0.68, 0.05, 0.68, 30], [0.79, 0.07, 0.9, 50], [0.92, 0.09, 1.3, 72]];
      return full(layers.map(([base, amp, period, mix], n) => {
        let crest = "", back = "";
        for (let i = 0; i <= 64; i++) {
          const x = 320 * i / 64;
          const u = ((x / (320 * period) + n * 0.37) % 1 + 1) % 1;
          const y = 200 * (base - amp * face(u));
          const depth = u >= 0.7 ? 200 * amp * 1.1 * Math.min(1, (u - 0.7) / 0.1) : 0;
          crest += `L${f1(x)} ${f1(y)}`;
          back = `L${f1(x)} ${f1(y + depth)}` + back;
        }
        return `<path d="M0 200${crest}L320 200Z" style="fill:color-mix(in srgb, var(--t-second) ${mix}%, var(--t-bg))"/>` +
          `<path d="M${crest.slice(1)}${back}Z" style="fill:color-mix(in srgb, var(--t-second) 50%, #6b3a1c)" opacity=".2"/>` +
          `<path d="M${crest.slice(1)}" fill="none" stroke="#fff" stroke-opacity=".5" stroke-width=".7"/>`;
      }).join(""));
    },
  };

  // Lucide icons (ISC): link and check.
  const LINK_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>';
  const CHECK_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 6 9 17l-5-5"/></svg>';

  async function copyLink(t, button) {
    const link = SITE + t.path;
    const label = $("span", button);
    try {
      await navigator.clipboard.writeText(link);
      button.classList.add("is-done");
      $("svg", button).outerHTML = CHECK_ICON;
      if (label) label.textContent = "Copied";
      toast(`Copied. In Fuse: Settings, Appearance, Theme, Add a theme`);
      setTimeout(() => {
        button.classList.remove("is-done");
        $("svg", button).outerHTML = LINK_ICON;
        if (label) label.textContent = "Copy link";
      }, 2200);
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

  const visible = (t) => !t.hidden && !t.closest("[hidden]") && t.offsetParent !== null;
  function tiles() { return $$("[data-row] .tile").filter(visible); }

  function choose(tile, scroll = true) {
    if (!tile) return;
    if (current) current.classList.remove("is-focused");
    current = tile;
    tile.classList.add("is-focused");
    tile.focus({ preventScroll: true });
    if (scroll) tile.scrollIntoView({ behavior: reduced ? "auto" : "smooth", block: "center", inline: "nearest" });
  }

  // Moves like the app's spatial selection: sideways along the tiles that share a line with this
  // one, up and down to the nearest line, landing on the tile nearest the one you're on. Works the
  // same for a scrolling shelf and for a grid that wraps.
  function move(dx, dy) {
    const all = tiles();
    if (!all.length) return;
    if (!current || !document.body.contains(current) || !visible(current)) { choose(all[0]); return; }
    const r = current.getBoundingClientRect();
    const cx = r.left + r.width / 2;
    const cy = r.top + r.height / 2;
    let best = null;
    let bestScore = Infinity;
    if (dx) {
      for (const t of all) {
        if (t === current) continue;
        const b = t.getBoundingClientRect();
        const ddx = b.left + b.width / 2 - cx;
        const overlap = Math.min(b.bottom, r.bottom) - Math.max(b.top, r.top);
        if (Math.sign(ddx) !== dx || overlap < Math.min(b.height, r.height) * 0.4) continue;
        if (Math.abs(ddx) < bestScore) { best = t; bestScore = Math.abs(ddx); }
      }
    } else {
      // The nearest line in that direction first, then the tile in it nearest this one.
      let line = Infinity;
      for (const t of all) {
        if (t === current) continue;
        const b = t.getBoundingClientRect();
        const ddy = dy > 0 ? b.top - (r.bottom - 4) : (r.top + 4) - b.bottom;
        if (ddy >= 0) line = Math.min(line, ddy);
      }
      if (line === Infinity) return;
      for (const t of all) {
        if (t === current) continue;
        const b = t.getBoundingClientRect();
        const ddy = dy > 0 ? b.top - (r.bottom - 4) : (r.top + 4) - b.bottom;
        if (ddy < 0 || ddy > line + Math.min(b.height, r.height) * 0.5) continue;
        const d = Math.abs(b.left + b.width / 2 - cx);
        if (d < bestScore) { best = t; bestScore = d; }
      }
    }
    if (best) choose(best);
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
    const glide = $(".tab-glide");
    const sections = tabs.map((t) => document.getElementById(t.dataset.tab)).filter(Boolean);
    let lastLeft = null;
    const place = (animate) => {
      const on = tabs.find((t) => t.classList.contains("is-active"));
      if (!glide) return;
      glide.classList.toggle("is-on", !!on);
      if (!on) return;
      const nav = glide.parentElement;
      const bar = 18;
      const center = on.offsetLeft + on.offsetWidth / 2;
      const left = Math.round(center - bar / 2);
      const right = Math.round(nav.scrollWidth - (center + bar / 2));
      glide.classList.toggle("to-right", !!animate && lastLeft != null && left > lastLeft);
      glide.classList.toggle("to-left", !!animate && lastLeft != null && left < lastLeft);
      lastLeft = left;
      glide.style.setProperty("--gl", `${left}px`);
      glide.style.setProperty("--gr", `${right}px`);
    };
    const io = new IntersectionObserver((entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue;
        tabs.forEach((t) => t.classList.toggle("is-active", t.dataset.tab === e.target.id));
        place(true);
      }
    }, { rootMargin: "-40% 0px -55% 0px" });
    sections.forEach((s) => io.observe(s));
    window.addEventListener("resize", () => place(false), { passive: true });
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => place(false));
  }

  // ------------------------------------------------------------------ entry reveal

  // Shelves rise into place as they come into view: the label, then the first tiles a beat apart
  // (25 ms, at most eight deep), once. Nothing is hidden before it, so a page that never scrolls,
  // prints or runs without observers still shows everything.
  function revealAll(nodes) {
    const list = nodes.filter((n) => n && !n.hidden);
    list.forEach((n) => n.classList.remove("rv"));
    void document.body.offsetWidth;
    list.forEach((n, i) => {
      n.style.setProperty("--i", String(Math.min(i, 8)));
      n.classList.add("rv");
      n.addEventListener("animationend", function done(e) {
        if (e.target !== n || e.animationName !== "reveal") return;
        n.removeEventListener("animationend", done);
        n.classList.remove("rv");
      });
    });
  }

  function revealShelves() {
    if (!("IntersectionObserver" in window)) return;
    const io = new IntersectionObserver((entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue;
        io.unobserve(e.target);
        const shelf = e.target;
        const parts = $$(":scope > .label, :scope > .lede, :scope > .seg, :scope > .also, :scope > .release-line", shelf);
        const row = $("[data-row]", shelf);
        const first = row ? $$(".tile", row).filter((t) => !t.hidden).slice(0, 8) : [];
        revealAll(parts.concat(first));
      }
    }, { rootMargin: "0px 0px 12% 0px" });
    $$(".shelf").forEach((s) => io.observe(s));
    const show = $(".showcase");
    if (show) {
      const io2 = new IntersectionObserver((entries) => {
        if (entries.some((e) => e.isIntersecting)) { io2.disconnect(); revealAll([show]); }
      }, { rootMargin: "0px 0px 12% 0px" });
      io2.observe(show);
    }
  }

  // ------------------------------------------------------------------ updates

  // Every release's notes (releases.json, made from docs/releases with the site): a rail of
  // versions, newest first, and the chosen one's notes in its sections, which a switch filters.
  // #v0.3.5 opens a version straight away.
  const KINDS = {
    New: '<path d="M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z"/>',
    Changed: '<path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/>',
    Fixed: '<path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.106-3.105c.32-.322.863-.22.983.218a6 6 0 0 1-8.259 7.057l-7.91 7.91a1 1 0 0 1-2.999-3l7.91-7.91a6 6 0 0 1 7.057-8.259c.438.12.54.662.219.984z"/>',
  };
  let releaseNotes = [];
  let shownVersion = null;
  let noteKind = "all";

  async function loadUpdates() {
    const viewer = $("[data-viewer]");
    if (!viewer) return;
    try {
      const r = await fetch("releases.json");
      if (r.ok) releaseNotes = await r.json();
    } catch (e) { /* the links to the changelog stay */ }
    if (!releaseNotes.length) return;
    viewer.hidden = false;
    const off = $("[data-viewer-off]");
    if (off) off.hidden = true;
    const total = $("[data-update-count]");
    if (total) total.textContent = String(releaseNotes.length);

    const rail = $("[data-rail]");
    releaseNotes.forEach((rel, i) => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "tile release";
      b.setAttribute("role", "option");
      b.dataset.version = rel.version;
      const ver = document.createElement("span");
      ver.className = "r-ver";
      ver.textContent = rel.version;
      const when = document.createElement("span");
      when.className = i === 0 ? "r-tag" : "r-date";
      when.textContent = i === 0 ? "Latest" : shortDate(rel.date);
      const name = document.createElement("span");
      name.className = "r-name";
      name.textContent = rel.name.replace(/^The /, "").replace(/ Update$/, "");
      b.title = rel.name;
      b.append(ver, when, name);
      b.addEventListener("click", () => showRelease(rel.version, { remember: true }));
      rail.append(b);
    });

    const seg = $("[data-n-filter]");
    for (const btn of $$(".seg-btn", seg)) {
      btn.addEventListener("click", () => {
        if (btn.disabled) return;
        noteKind = btn.dataset.kind;
        const rel = releaseNotes.find((r) => r.version === shownVersion);
        if (rel) { markKinds(rel, true); renderNotes(rel); }
      });
    }
    window.addEventListener("resize", () => placeKindGlide(false), { passive: true });

    const asked = versionInHash();
    showRelease(asked || releaseNotes[0].version, { scroll: !!asked });
    window.addEventListener("hashchange", () => {
      const v = versionInHash();
      if (v) showRelease(v, { scroll: true });
    });
  }

  function versionInHash() {
    const m = /^#v(\d+(?:\.\d+)+)$/.exec(location.hash);
    return m && releaseNotes.some((r) => r.version === m[1]) ? m[1] : null;
  }

  const day = (iso) => (iso ? new Date(`${iso}T12:00:00`) : null);
  function shortDate(iso) {
    const d = day(iso);
    return d ? new Intl.DateTimeFormat(undefined, { month: "short", year: "numeric" }).format(d) : "";
  }

  function showRelease(version, { remember = false, scroll = false } = {}) {
    const rel = releaseNotes.find((r) => r.version === version);
    if (!rel) return;
    const changed = shownVersion !== version;
    shownVersion = version;
    const rail = $("[data-rail]");
    for (const b of $$(".release", rail)) {
      const on = b.dataset.version === version;
      b.setAttribute("aria-selected", String(on));
      if (on && changed) {
        // Keep the chosen one in view inside the rail, without moving the page.
        const r = b.getBoundingClientRect();
        const box = rail.getBoundingClientRect();
        if (rail.scrollHeight > rail.clientHeight && (r.top < box.top || r.bottom > box.bottom)) rail.scrollTop += r.top - box.top - box.height / 2 + r.height / 2;
        if (rail.scrollWidth > rail.clientWidth && (r.left < box.left || r.right > box.right)) rail.scrollLeft += r.left - box.left - box.width / 2 + r.width / 2;
      }
    }
    if (remember) history.replaceState(null, "", `#v${version}`);

    const d = day(rel.date);
    $("[data-n-version]").textContent = rel.version === releaseNotes[0].version ? `${rel.version} · Latest` : rel.version;
    $("[data-n-date]").textContent = d ? new Intl.DateTimeFormat(undefined, { day: "numeric", month: "long", year: "numeric" }).format(d) : "";
    $("[data-n-name]").textContent = rel.name;
    $("[data-n-link]").href = `https://github.com/${REPO}/releases/tag/v${rel.version}`;
    // The notes are the repository's own, made safe when the site was built.
    $("[data-n-intro]").innerHTML = rel.intro.map((p) => `<p>${p}</p>`).join("");
    markKinds(rel, false);
    renderNotes(rel);
    if (scroll) $("#updates").scrollIntoView({ behavior: reduced ? "auto" : "smooth", block: "start" });
  }

  function markKinds(rel, animate) {
    const seg = $("[data-n-filter]");
    const counts = { all: 0 };
    for (const sec of rel.sections) {
      const n = sec.items.length + sec.lead.length;
      counts[sec.title] = n;
      counts.all += n;
    }
    if (noteKind !== "all" && !counts[noteKind]) noteKind = "all";
    for (const btn of $$(".seg-btn", seg)) {
      const k = btn.dataset.kind;
      btn.disabled = !counts[k];
      btn.setAttribute("aria-pressed", String(k === noteKind));
      const b = $(`[data-kcount="${k}"]`, seg);
      if (b) b.textContent = String(counts[k] || 0);
    }
    placeKindGlide(animate);
  }

  function placeKindGlide(animate) {
    const seg = $("[data-n-filter]");
    const glide = seg && $(".seg-glide", seg);
    const on = seg && $('.seg-btn[aria-pressed="true"]', seg);
    if (!glide || !on) return;
    glide.classList.toggle("is-moving", !!animate && !reduced);
    glide.style.setProperty("--gx", `${on.offsetLeft}px`);
    glide.style.setProperty("--gw", `${on.offsetWidth}px`);
  }

  function renderNotes(rel) {
    const body = $("[data-n-body]");
    body.textContent = "";
    for (const sec of rel.sections) {
      if (noteKind !== "all" && sec.title !== noteKind) continue;
      if (!sec.items.length && !sec.lead.length) continue;
      const el = document.createElement("section");
      el.className = `kind kind-${sec.title.toLowerCase()}`;
      const n = sec.items.length + sec.lead.length;
      el.innerHTML = `<h4><span class="k-icon"><svg viewBox="0 0 24 24" aria-hidden="true">${KINDS[sec.title] || KINDS.New}</svg></span>${sec.title}<b>${n}</b></h4>` +
        sec.lead.map((p) => `<p class="k-lead">${p}</p>`).join("");
      const titled = sec.items.filter((it) => it.title);
      const plain = sec.items.filter((it) => !it.title);
      if (titled.length) {
        const cards = document.createElement("div");
        cards.className = "cards";
        cards.innerHTML = titled.map((it) =>
          `<div class="note"><b>${it.title}</b>${it.text ? `<p>${it.text}</p>` : ""}${it.sub.length ? `<ul>${it.sub.map((x) => `<li>${x}</li>`).join("")}</ul>` : ""}</div>`).join("");
        el.append(cards);
      }
      if (plain.length) {
        const list = document.createElement("ul");
        list.className = "plain";
        list.innerHTML = plain.map((it) => `<li>${it.text}${it.sub.length ? `<ul>${it.sub.map((x) => `<li>${x}</li>`).join("")}</ul>` : ""}</li>`).join("");
        el.append(list);
      }
      body.append(el);
    }
    revealAll($$(".kind", body));
  }

  // ------------------------------------------------------------------ start

  clock();
  setInterval(clock, 15000);
  followSections();
  revealAll($$(".stage > *"));
  revealShelves();
  const mine = placeMine();
  if (mine) choose(mine, false);
  loadRelease().then(applyRelease);
  loadThemes();
  loadUpdates();
})();
