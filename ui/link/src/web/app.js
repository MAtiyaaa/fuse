/*
 * Fuse Phone Link: the phone web app. Plain ES2020, no dependencies, no build step.
 * API contract: docs/PHONE_LINK.md.
 *
 * Safety rules kept throughout this file:
 *  - Strings from the server are only ever set as text (textContent or text nodes), never as HTML.
 *  - Requests are same-origin with the session cookie; nothing secret is stored in the browser.
 *  - Image URLs are only used when they are https:// or same-origin (/api/img/<token>).
 */

// ---- Constants

const PAGE = 60;
const TABS = ['now', 'library', 'captures', 'tools'];
const SORTS = [
  { id: 'title', label: 'Title', sub: 'A to Z' },
  { id: 'recent', label: 'Recently played', sub: 'Last played first' },
  { id: 'added', label: 'Recently added', sub: 'Newest first' },
];
const ART_KINDS = [
  { kind: 'square', label: 'Box art', noun: 'box art', article: '', min: '72px', ar: '1', artNoun: 'box art' },
  { kind: 'icon', label: 'Icon', noun: 'icon', article: 'an', min: '72px', ar: '1' },
  { kind: 'cover', label: 'Cover', noun: 'cover', article: 'a', min: '96px', ar: '0.72' },
  { kind: 'banner', label: 'Banner', noun: 'banner', article: 'a', min: '150px', ar: '460 / 215' },
  { kind: 'background', label: 'Background', noun: 'background', article: 'a', min: '150px', ar: '16 / 9' },
  { kind: 'logo', label: 'Logo', noun: 'logo', article: 'a', min: '150px', ar: '16 / 9', contain: true },
  { kind: 'screenshot', label: 'Screenshots', noun: 'screenshot', article: 'a', min: '150px', ar: '16 / 9' },
];
const QUEUE_STATES = {
  downloading: { label: 'Downloading', icon: 'download', cls: 'state-downloading', bar: '' },
  queued: { label: 'Waiting', icon: 'hourglass', cls: 'state-queued', bar: 'is-muted' },
  paused: { label: 'Paused', icon: 'pause', cls: 'state-paused', bar: 'is-warning' },
  failed: { label: 'Failed', icon: 'circle-x', cls: 'state-failed', bar: 'is-danger' },
};
// Original, desaturated hues used when a system has no accent of its own.
const FALLBACK_ACCENTS = ['#7D8BA8', '#5E9C8F', '#8C84B8', '#B25E5E', '#7867B5', '#5B6FB5', '#C9A45C', '#5A8FBF'];
const NET_ERROR = "Can't reach Fuse. Check that the device is on and on the same Wi-Fi as this phone.";
const SVGNS = 'http://www.w3.org/2000/svg';

const nf = new Intl.NumberFormat('en');
const media = (q) => (window.matchMedia ? window.matchMedia(q) : { matches: false });
const reducedMotion = media('(prefers-reduced-motion: reduce)');
const coarse = media('(pointer: coarse)');
const wideScreen = media('(min-width: 900px)');
// Entry reveals: about 25 ms between neighbours, at most 8 deep (Motion.kt, STAGGER and STAGGER_MAX).
const STAGGER_MAX = 8;
// The near-black generated art sinks into (GeneratedArt.kt, Night).
const NIGHT = [7, 8, 12];

// ---- State

const S = {
  session: {},
  signedIn: false,
  skew: 0,
  tab: 'now',
  conn: 'connecting',
  systems: [],
  sysById: new Map(),
  systemsReady: Promise.resolve(),
  now: null,
  nowLoaded: false,
  nowError: null,
  fill: null,
  fillDismissed: false,
  fillPendingUntil: 0,
  toolsSystem: '',
  scroll: {},
};

const L = {
  started: false,
  query: '',
  system: '',
  sort: 'title',
  items: [],
  total: null,
  loading: false,
  done: false,
  error: null,
  gen: 0,
  ctrl: null,
  cache: new Map(),
  skel: [],
};

const E = {};
let nowUI = null;
let tools = null;
let G = null;
let uidN = 0;

// ---- DOM helpers

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

/**
 * Builds an element. `sel` is a tag with optional classes ('span.row-title'); `props` is optional.
 * Children are Nodes or strings; strings always become text nodes, never HTML.
 */
function h(sel, props, ...kids) {
  const [tag, ...cls] = sel.split('.');
  const el = document.createElement(tag);
  if (cls.length) el.className = cls.join(' ');
  if (props && typeof props === 'object' && !Array.isArray(props) && !(props instanceof Node)) {
    for (const k of Object.keys(props)) {
      const v = props[k];
      if (v == null || v === false) continue;
      if (k === 'class') el.className = el.className ? `${el.className} ${v}` : v;
      else if (k === 'style') for (const p of Object.keys(v)) el.style.setProperty(p, v[p]);
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else if (v === true) el.setAttribute(k, '');
      else el.setAttribute(k, String(v));
    }
  } else {
    kids.unshift(props);
  }
  add(el, kids);
  return el;
}

/** Runs `fn` and ignores errors (history calls can throw in sandboxed or unusual contexts). */
function quiet(fn) {
  try {
    return fn();
  } catch (_) {
    return undefined;
  }
}

function add(el, kids) {
  for (const k of kids.flat(Infinity)) {
    if (k == null || k === false || k === '') continue;
    el.append(k instanceof Node ? k : String(k));
  }
  return el;
}

function icon(name, cls) {
  const svg = document.createElementNS(SVGNS, 'svg');
  svg.setAttribute('class', 'i' + (cls ? ' ' + cls : ''));
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('focusable', 'false');
  const use = document.createElementNS(SVGNS, 'use');
  use.setAttribute('href', '#i-' + name);
  svg.append(use);
  return svg;
}

function svgEl(tag, attrs) {
  const el = document.createElementNS(SVGNS, tag);
  for (const k of Object.keys(attrs || {})) el.setAttribute(k, String(attrs[k]));
  return el;
}

/** A round-capped arc turning on a faint ring, like Fuse's Spinner. */
function spinner(cls) {
  const s = h('span', { class: 'spinner' + (cls ? ' ' + cls : ''), 'aria-hidden': 'true' });
  const svg = svgEl('svg', { viewBox: '0 0 24 24', focusable: 'false' });
  svg.append(svgEl('circle', { class: 'sp-track', cx: 12, cy: 12, r: 9 }), svgEl('circle', { class: 'sp-arc', cx: 12, cy: 12, r: 9 }));
  s.append(svg);
  return s;
}

/**
 * Content arriving: each element rises a little and fades in, its neighbours a beat after it
 * (effects/Reveal.kt). Once per arrival; refreshes and re-renders never replay it. Reduced motion
 * leaves a short fade (the CSS sets the rise and stagger to nothing).
 */
function reveal(nodes, start = 0) {
  const list = Array.from(nodes || []).filter((n) => n instanceof Element && !n.hidden);
  if (!list.length) return;
  for (const n of list) n.classList.remove('rv');
  void document.body.offsetWidth;
  list.forEach((n, i) => {
    n.style.setProperty('--i', String(Math.min(start + i, STAGGER_MAX)));
    n.classList.add('rv');
    const done = (e) => {
      if (e.target !== n || e.animationName !== 'reveal') return;
      n.removeEventListener('animationend', done);
      n.classList.remove('rv');
    };
    n.addEventListener('animationend', done);
  });
}

/**
 * Moves a gliding marker (the chosen chip's pill, a segmented switch's thumb) onto [target]. It
 * glides when [animate] and motion allows, and only when it actually moves.
 */
function placeGlide(glider, target, animate) {
  if (!glider || !target) return;
  const x = target.offsetLeft;
  const w = target.offsetWidth;
  if (!w) return;
  if (glider._x === x && glider._w === w) return;
  const first = glider._x == null;
  glider._x = x;
  glider._w = w;
  if (animate && !first) {
    glider.classList.add('is-moving');
    clearTimeout(glider._t);
    glider._t = setTimeout(() => glider.classList.remove('is-moving'), 480);
  } else {
    glider.classList.remove('is-moving');
  }
  glider.style.setProperty('--gx', `${x}px`);
  glider.style.setProperty('--gw', `${w}px`);
}

/** Counts that change in place roll to their new value instead of jumping (Motion.value). */
function rollNumber(el, value, render) {
  const from = el._v;
  el._v = value;
  cancelAnimationFrame(el._raf || 0);
  if (from == null || from === value || reducedMotion.matches || document.hidden) {
    render(value);
    return;
  }
  const t0 = performance.now();
  const step = (t) => {
    const k = Math.min(1, (t - t0) / 420);
    const eased = 1 - (1 - k) ** 3;
    render(Math.round(from + (value - from) * eased));
    if (k < 1) el._raf = requestAnimationFrame(step);
  };
  el._raf = requestAnimationFrame(step);
}

function btn(label, opts = {}) {
  const { icon: ic, kind = 'secondary', size, block, onClick, aria } = opts;
  const cls = ['btn', 'btn-' + kind, size && 'btn-' + size, block && 'btn-block'].filter(Boolean).join(' ');
  const b = h('button', { class: cls, type: 'button', 'aria-label': aria }, ic ? icon(ic) : null, h('span.btn-label', label));
  if (onClick) b.addEventListener('click', () => onClick(b));
  return b;
}

function setBusy(b, busy) {
  if (!b) return;
  if (busy) {
    if (b.classList.contains('is-busy')) return;
    b.classList.add('is-busy');
    b.disabled = true;
    b.setAttribute('aria-busy', 'true');
    b.prepend(spinner());
  } else {
    b.classList.remove('is-busy');
    b.disabled = false;
    b.removeAttribute('aria-busy');
    const sp = b.querySelector(':scope > .spinner');
    if (sp) sp.remove();
  }
}

function keyed(slot, key, build) {
  if (slot._key === key) return false;
  slot._key = key;
  slot.replaceChildren(...[].concat(build()).filter(Boolean));
  return true;
}

function uid(prefix) {
  uidN += 1;
  return `${prefix}-${uidN}`;
}

function sectionHead(title, aside) {
  return h('div.section-head', h('h2.section-title', title), aside || null);
}

function cardHead(ic, title, text, aside, iconCls) {
  return h('div.card-head',
    h('div', { class: 'card-icon' + (iconCls ? ' ' + iconCls : '') }, icon(ic)),
    h('div.card-head-main', h('p.card-title', title), text ? h('p.card-text', text) : null),
    aside ? h('div.card-head-aside', aside) : null);
}

/** Empty, no-result and error states, as Fuse's EmptyState: a lit disc, what happened, what to do. */
function emptyState(ic, title, text, action, opts = {}) {
  return h('div', { class: 'empty' + (opts.compact ? ' empty-compact' : '') },
    h('div', { class: 'empty-icon' + (opts.danger ? ' is-danger' : ''), 'aria-hidden': 'true' }, icon(ic)),
    h('p.empty-title', { role: 'heading', 'aria-level': '2' }, title),
    text ? h('p.empty-text', text) : null,
    action || null);
}

function errorState(title, err, retry) {
  const offline = err && err.status === 0;
  return emptyState(offline ? 'wifi-off' : 'circle-alert', title, (err && err.message) || 'Something went wrong.',
    retry ? btn('Try again', { icon: 'refresh-cw', kind: 'primary', onClick: retry }) : null, { danger: true });
}

// ---- Formatting

const num = (v) => {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
};
const clamp01 = (v) => Math.min(1, Math.max(0, v));
const nowMs = () => Date.now() + S.skew;
const quote = (s) => `\u201C${s}\u201D`;

function plural(n, one, many) {
  return `${nf.format(n)} ${n === 1 ? one : many}`;
}

function fmtBytes(v) {
  let n = Number(v);
  if (!Number.isFinite(n) || n < 0) return '';
  if (n < 1024) return `${Math.round(n)} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let i = -1;
  do {
    n /= 1024;
    i += 1;
  } while (n >= 1024 && i < units.length - 1);
  const s = n >= 100 || i === 0 ? String(Math.round(n)) : n.toFixed(1).replace(/\.0$/, '');
  return `${s} ${units[i]}`;
}

function fmtMinutes(v) {
  const m = Math.round(num(v));
  if (m <= 0) return 'None yet';
  if (m < 60) return `${m} min`;
  const hh = Math.floor(m / 60);
  const mm = m % 60;
  return mm ? `${nf.format(hh)} h ${mm} min` : `${nf.format(hh)} h`;
}

function fmtClock(sec) {
  const s = Math.max(0, Math.floor(sec));
  const hh = Math.floor(s / 3600);
  const mm = Math.floor((s % 3600) / 60);
  const ss = s % 60;
  const p = (x) => String(x).padStart(2, '0');
  return hh ? `${hh}:${p(mm)}:${p(ss)}` : `${mm}:${p(ss)}`;
}

function fmtAgo(ms) {
  const t = Number(ms);
  if (!t) return '';
  const min = Math.floor(Math.max(0, nowMs() - t) / 60000);
  if (min < 1) return 'Just now';
  if (min < 60) return `${min} min ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr} h ago`;
  const days = Math.floor(hr / 24);
  if (days === 1) return 'Yesterday';
  if (days < 7) return `${days} days ago`;
  const d = new Date(t);
  const sameYear = d.getFullYear() === new Date().getFullYear();
  return d.toLocaleDateString('en', { month: 'short', day: 'numeric', year: sameYear ? undefined : 'numeric' });
}

function fmtPlayers(p) {
  if (p == null) return '';
  const s = String(p).trim();
  if (!s || s === '0') return '';
  if (/player/i.test(s)) return s;
  return s === '1' ? '1 player' : `${s} players`;
}

function fmtRating(r) {
  if (r == null || r === '') return '';
  const n = Number(r);
  if (!Number.isFinite(n) || n <= 0) return '';
  return `${Math.round(n)}/100`;
}

function confPct(c) {
  if (c == null || c === '') return null;
  const n = Number(c);
  if (!Number.isFinite(n)) return null;
  return Math.round(Math.min(100, Math.max(0, n <= 1 ? n * 100 : n)));
}

const STOP = new Set(['the', 'a', 'an', 'of', 'and', 'to', 'in', 'on', 'for']);
function initialsOf(title) {
  const t = String(title || '').trim();
  if (!t) return '';
  const words = t.replace(/\(.*?\)|\[.*?\]/g, ' ').split(/[\s\-:_.]+/).filter((w) => w && !STOP.has(w.toLowerCase()));
  const first = (w) => Array.from(w)[0] || '';
  if (!words.length) return Array.from(t).slice(0, 2).join('').toUpperCase();
  if (words.length === 1) return Array.from(words[0]).slice(0, 2).join('').toUpperCase();
  return (first(words[0]) + first(words[1])).toUpperCase();
}

function hashStr(s) {
  let x = 0;
  const str = String(s || '');
  for (let i = 0; i < str.length; i += 1) x = (x * 31 + str.charCodeAt(i)) | 0;
  return Math.abs(x);
}

// ---- Colours, systems and images

function parseHex(v) {
  const m = /^#?([0-9a-f]{6})$/i.exec(String(v || '').trim());
  if (!m) return null;
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
const rgb = (c) => `rgb(${c[0]}, ${c[1]}, ${c[2]})`;
const rgba = (c, a) => `rgba(${c[0]}, ${c[1]}, ${c[2]}, ${a})`;

function accentFor(systemId) {
  const s = S.sysById.get(systemId);
  return parseHex(s && s.accent) || parseHex(FALLBACK_ACCENTS[hashStr(systemId) % FALLBACK_ACCENTS.length]);
}

function sysName(id, fallback) {
  const s = S.sysById.get(id);
  return (s && s.name) || fallback || (id ? String(id).toUpperCase() : '');
}

function sysShort(id, fallback) {
  const s = S.sysById.get(id);
  return (s && (s.shortName || s.name)) || fallback || (id ? String(id).toUpperCase() : '');
}

function deviceName() {
  const d = S.session && S.session.device;
  return typeof d === 'string' && d.trim() ? d.trim() : 'the device';
}

/** Only https:// and same-origin URLs (such as /api/img/<token>) are ever loaded. */
function safeUrl(u) {
  if (typeof u !== 'string' || !u.trim()) return null;
  try {
    const url = new URL(u, location.href);
    if (url.protocol === 'https:' || url.origin === location.origin) return url.href;
  } catch (_) {
    /* not a URL */
  }
  return null;
}

// Generated art, the same composition Fuse draws on the device (media/GeneratedArt.kt): the same
// seed, the same random sequence and the same lights, so a game without art looks alike on both.

/** FNV-1a over the lower-cased title, as artSeed. */
function artSeed(title) {
  const s = String(title || '').toLowerCase();
  let x = 0x811C9DC5 | 0;
  for (let i = 0; i < s.length; i += 1) {
    x ^= s.charCodeAt(i);
    x = Math.imul(x, 0x01000193);
  }
  return x;
}

/** The app's small 64-bit generator (Rng), in BigInt so it repeats exactly. */
class ArtRng {
  constructor(seed) {
    this.s = BigInt.asIntN(64, BigInt(seed) * -7046029254386353131n + 0x632BE59BD9B4E019n);
  }

  next() {
    this.s = BigInt.asIntN(64, this.s * 6364136223846793005n + 1442695040888963407n);
    return Number((BigInt.asUintN(64, this.s) >> 40n) & 0xFFFFFFn) / 16777216;
  }

  pick(n) {
    return Math.min(n - 1, Math.max(0, Math.floor(this.next() * n)));
  }
}

function toHsv(c) {
  const [r, g, b] = c.map((v) => v / 255);
  const max = Math.max(r, g, b);
  const d = max - Math.min(r, g, b);
  let hue = 0;
  if (d !== 0) {
    if (max === r) hue = 60 * (((g - b) / d) % 6);
    else if (max === g) hue = 60 * ((b - r) / d + 2);
    else hue = 60 * ((r - g) / d + 4);
  }
  if (hue < 0) hue += 360;
  return [hue, max === 0 ? 0 : d / max, max];
}

function fromHsv(hue, s, v) {
  const f = (n) => {
    const k = (n + hue / 60) % 6;
    return v - v * s * Math.max(0, Math.min(k, 4 - k, 1));
  };
  return [f(5), f(3), f(1)].map((x) => Math.round(x * 255));
}

/** A brighter take on a colour for the lights, so muted system colours still glow. */
function vivid(c) {
  const [hue, s, v] = toHsv(c);
  return fromHsv(hue, Math.min(s * 1.18 + 0.06, 0.82), Math.max(v, 0.8));
}

/** The bounce light: the hue turned away from yellow and green, so nothing turns muddy. */
function bounceOf(c) {
  const [hue, s, v] = toHsv(c);
  const turned = (((hue + (hue >= 90 && hue <= 300 ? 28 : -28)) % 360) + 360) % 360;
  return fromHsv(turned, s, v);
}

const f1 = (v) => Math.round(v * 10) / 10;

/** Generated art as an SVG of [ar] (width / height), drawn in the system's accent. */
function generatedArt(title, acc, ar) {
  const W = 120;
  const H = W / ar;
  const short = Math.min(W, H);
  const long = Math.max(W, H);
  const rng = new ArtRng(artSeed(title));
  const leanRight = rng.next() < 0.5;
  const key = vivid(acc);
  const bounce = vivid(bounceOf(acc));
  rng.next();
  const id = uid('ga');
  const kx = W * (0.16 + 0.68 * rng.next());
  const ky = H * (-0.1 + 0.4 * rng.next());
  const keyR = long * (0.72 + 0.25 * rng.next());
  const svg = svgEl('svg', { viewBox: `0 0 ${f1(W)} ${f1(H)}`, preserveAspectRatio: 'xMidYMid slice', focusable: 'false' });
  const defs = svgEl('defs');
  const stop = (offset, color, opacity = 1) => svgEl('stop', { offset, 'stop-color': rgb(color), 'stop-opacity': opacity });
  const base = svgEl('linearGradient', { id: `${id}b`, gradientUnits: 'userSpaceOnUse', x1: leanRight ? 0 : W, y1: 0, x2: f1(leanRight ? W * 0.65 : W * 0.35), y2: f1(H) });
  base.append(stop(0, mix(NIGHT, acc, 0.5)), stop(0.55, mix(NIGHT, acc, 0.26)), stop(1, mix(NIGHT, acc, 0.08)));
  const keyG = svgEl('radialGradient', { id: `${id}k`, gradientUnits: 'userSpaceOnUse', cx: f1(kx), cy: f1(ky), r: f1(keyR) });
  keyG.append(stop(0, key, 0.6), stop(0.32, key, 0.24), stop(1, key, 0));
  const bx = kx > W / 2 ? W * 0.08 : W * 0.92;
  const bounceG = svgEl('radialGradient', { id: `${id}o`, gradientUnits: 'userSpaceOnUse', cx: f1(bx), cy: f1(H * 1.08), r: f1(long * 0.78) });
  bounceG.append(stop(0, bounce, 0.28), stop(1, bounce, 0));
  const vig = svgEl('radialGradient', { id: `${id}v`, gradientUnits: 'userSpaceOnUse', cx: f1(W / 2), cy: f1(H * 0.42), r: f1(Math.hypot(W, H) * 0.62) });
  vig.append(stop(0.5, [0, 0, 0], 0), stop(1, [0, 0, 0], 0.42));
  defs.append(base, keyG, bounceG, vig);
  const rect = (fill) => svgEl('rect', { width: f1(W), height: f1(H), fill });
  svg.append(defs, rect(`url(#${id}b)`), rect(`url(#${id}k)`), rect(`url(#${id}o)`));
  const line = Math.min(2.5, Math.max(1, short / 140));
  const pattern = rng.pick(4);
  if (pattern === 0) {
    // Rings spreading from the key light.
    const step = short * 0.24;
    for (let i = 1; i <= 8; i += 1) {
      svg.append(svgEl('circle', { cx: f1(kx), cy: f1(ky), r: f1(step * i), fill: 'none', stroke: '#fff', 'stroke-width': line, 'stroke-opacity': Math.round(85 * (1 - i / 10)) / 1000 }));
    }
  } else if (pattern === 1) {
    // Waving contour lines, like a map of a hill.
    const amp = short * (0.05 + 0.04 * rng.next());
    const phase = rng.next() * 2 * Math.PI;
    const tilt = (leanRight ? -1 : 1) * H * 0.25;
    let d = '';
    for (let i = 0; i <= 9; i += 1) {
      const y = (H * (i + 0.5)) / 9;
      d += `M${f1(-W * 0.05)} ${f1(y - tilt * 0.5)}`;
      for (let s = 1; s <= 24; s += 1) {
        const t = s / 24;
        const wave = Math.sin(phase + t * 2.4 * Math.PI + i * 0.55) * amp;
        d += `L${f1(-W * 0.05 + W * 1.1 * t)} ${f1(y + tilt * (t - 0.5) + wave)}`;
      }
    }
    svg.append(svgEl('path', { d, fill: 'none', stroke: '#fff', 'stroke-width': line, 'stroke-opacity': 0.055 }));
  } else if (pattern === 2) {
    // A halftone of dots gathering where the key light falls.
    const step = Math.max(short / 10, 6);
    let d = '';
    for (let y = step / 2, row = 0; y < H + step; y += step * 0.87, row += 1) {
      for (let x = row % 2 === 0 ? step / 2 : step; x < W + step; x += step) {
        const r = step * 0.17 * (1 - (Math.hypot(x - kx, y - ky) / long) * 1.7);
        if (r > step * 0.035) d += `M${f1(x - r)} ${f1(y)}a${f1(r)} ${f1(r)} 0 1 0 ${f1(2 * r)} 0a${f1(r)} ${f1(r)} 0 1 0 ${f1(-2 * r)} 0`;
      }
    }
    if (d) svg.append(svgEl('path', { d, fill: '#fff', 'fill-opacity': 0.065 }));
  } else {
    // Large folded planes, as if the light fell across creased paper.
    const a = rng.next();
    const b = rng.next();
    const pts = (list) => list.map(([x, y]) => `${f1(x)},${f1(y)}`).join(' ');
    const light = leanRight
      ? [[W * (0.25 + 0.3 * a), 0], [W, 0], [W, H * (0.55 + 0.3 * b)]]
      : [[0, 0], [W * (0.45 + 0.3 * a), 0], [0, H * (0.55 + 0.3 * b)]];
    const shade = leanRight
      ? [[0, H * (0.35 + 0.3 * b)], [W * (0.55 + 0.3 * a), H], [0, H]]
      : [[W, H * (0.35 + 0.3 * b)], [W * (0.45 - 0.3 * a), H], [W, H]];
    svg.append(
      svgEl('polygon', { points: pts(light), fill: '#fff', 'fill-opacity': 0.055 }),
      svgEl('polygon', { points: pts(shade), fill: '#000', 'fill-opacity': 0.16 }),
      svgEl('line', { x1: f1(W * (leanRight ? 0.25 + 0.3 * a : 0.45 + 0.3 * a)), y1: 0, x2: f1(leanRight ? W : 0), y2: f1(H * (0.55 + 0.3 * b)), stroke: '#fff', 'stroke-opacity': 0.08, 'stroke-width': 1 }));
  }
  svg.append(rect(`url(#${id}v)`));
  return svg;
}

/**
 * Generated art for a title with no picture: the lit composition, the title's initials in the
 * display face (sized from the frame's height, as the app does) and, on larger frames, the
 * system's short name as a small tag.
 */
function placeholder(title, system, withText, opts = {}) {
  const ar = opts.ar > 0 ? opts.ar : 1;
  const ph = h('span.ph', { 'aria-hidden': 'true' });
  ph.append(generatedArt(title, accentFor(system), ar));
  if (withText) {
    const ini = initialsOf(title);
    if (ini) {
      const size = (ini.length > 2 ? 0.24 : 0.31) / ar;
      const span = h('span.ph-ini', ini);
      span.style.setProperty('--ph-fs', `${f1(size * 100)}cqi`);
      ph.append(span);
    }
    const tag = opts.tag ? String(opts.tag) : '';
    if (tag && ar <= 1.05) {
      ph.classList.add('is-tagged');
      ph.append(h('span.ph-tag', tag));
    }
  }
  return ph;
}

/** Parses an aspect ratio given as a number or as "460 / 215". */
function ratioOf(v) {
  if (typeof v === 'number') return v;
  const m = /^\s*([\d.]+)\s*(?:\/\s*([\d.]+))?\s*$/.exec(String(v || ''));
  if (!m) return 0;
  const r = Number(m[1]) / (m[2] ? Number(m[2]) : 1);
  return Number.isFinite(r) ? r : 0;
}

/**
 * An image box that fades the picture in once loaded and falls back to generated art when there is
 * no picture or it fails. With [fit], a picture clearly wider than its frame (square box art in a
 * portrait frame) is drawn whole over a soft copy of itself instead of being cropped.
 */
function art(url, opts = {}) {
  const { title = '', system = '', text = true, blur = false, contain = false, eager = false, flat = false, alt = '', alsoTry = null, fit = false, tag = '' } = opts;
  const ar = ratioOf(opts.ar);
  const box = h('span', { class: 'art' + (blur ? ' is-blur' : '') + (contain ? ' is-contain' : '') + (flat ? ' is-flat' : '') });
  const src = safeUrl(url);
  const fallback = () => {
    box.classList.remove('is-loading');
    box.classList.add('is-missing');
    box.prepend(placeholder(title, system, text && !blur, { ar, tag }));
  };
  if (!src) {
    fallback();
    return box;
  }
  const img = document.createElement('img');
  const loaded = () => {
    box.classList.remove('is-loading');
    box.classList.add('is-loaded');
    if (!fit || !ar || contain || box.classList.contains('is-fitted')) return;
    const r = img.naturalWidth / img.naturalHeight;
    if (Number.isFinite(r) && r > ar * 1.18) {
      const soft = img.cloneNode();
      soft.className = 'art-soft';
      soft.alt = '';
      soft.setAttribute('aria-hidden', 'true');
      box.insertBefore(soft, img);
      box.classList.add('is-fitted');
    }
  };
  img.alt = alt;
  img.decoding = 'async';
  img.loading = eager ? 'eager' : 'lazy';
  img.referrerPolicy = 'no-referrer';
  img.draggable = false;
  img.addEventListener('load', loaded, { once: true });
  const second = safeUrl(alsoTry);
  img.addEventListener('error', function onError() {
    if (second && img.src !== second && !img.dataset.retried) {
      img.dataset.retried = '1';
      img.src = second;
      return;
    }
    img.removeEventListener('error', onError);
    img.remove();
    fallback();
  });
  box.classList.add('is-loading');
  img.src = src;
  box.append(img);
  if (img.complete && img.naturalWidth) {
    img.removeEventListener('load', loaded);
    loaded();
  }
  return box;
}

/** A system's colour as the lit gradient generated art uses, for small marks and badges. */
function systemFill(acc) {
  const key = vivid(acc);
  return `radial-gradient(120% 90% at 28% 0%, ${rgba(key, 0.42)}, transparent 70%), linear-gradient(160deg, ${rgb(mix(NIGHT, acc, 0.5))}, ${rgb(mix(NIGHT, acc, 0.22))})`;
}

function platBadge(platform, small) {
  const label = sysShort(platform).slice(0, 6);
  const el = h('span', { class: 'plat' + (small ? ' plat-sm' : '') + (label.length > 4 ? ' is-long' : ''), 'aria-hidden': 'true' }, label);
  el.style.setProperty('--plat', systemFill(accentFor(platform)));
  return el;
}

function sysLogoBox(sys) {
  const box = h('span.menu-logo', { 'aria-hidden': 'true' });
  if (!sys) {
    box.append(icon('layers'));
    return box;
  }
  const logo = safeUrl(sys.logo);
  const fallbackBadge = () => {
    box.style.setProperty('background', systemFill(accentFor(sys.id)));
    const label = String(sys.shortName || sys.name || sys.id || '').slice(0, 6);
    box.replaceChildren(h('span', { class: 'plat-text' + (label.length > 4 ? ' is-long' : '') }, label));
    box.classList.add('is-badge');
  };
  if (logo) {
    const img = h('img', { alt: '', decoding: 'async', draggable: 'false' });
    img.referrerPolicy = 'no-referrer';
    img.addEventListener('error', fallbackBadge, { once: true });
    img.src = logo;
    box.append(img);
  } else {
    fallbackBadge();
  }
  return box;
}

// ---- API

class ApiError extends Error {
  constructor(message, status, data) {
    super(message);
    this.status = status;
    this.data = data;
  }
}

function errorText(data, status) {
  if (data && typeof data.error === 'string' && data.error.trim()) return data.error.trim();
  if (status === 429) return 'Too many tries. Wait a moment and try again.';
  if (status === 404) return 'That is no longer on the device.';
  if (status >= 500) return 'Fuse ran into a problem. Try again in a moment.';
  return 'Something went wrong. Try again.';
}

function noteServerTime(res) {
  const d = res.headers.get('Date');
  if (!d) return;
  const t = Date.parse(d);
  if (!Number.isFinite(t)) return;
  const skew = t - Date.now();
  S.skew = Math.abs(skew) > 5000 ? skew : 0;
}

async function api(path, opts = {}) {
  const { method = 'GET', body, signal, auth = true } = opts;
  const headers = { Accept: 'application/json' };
  const init = { method, headers, credentials: 'same-origin', cache: 'no-store', signal };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(path, init);
  } catch (e) {
    if (e && e.name === 'AbortError') throw e;
    throw new ApiError(NET_ERROR, 0);
  }
  noteServerTime(res);
  let data = null;
  try {
    const txt = await res.text();
    if (txt) data = JSON.parse(txt);
  } catch (e) {
    if (e && e.name === 'AbortError') throw e;
    data = null;
  }
  if (res.status === 401 && auth) {
    handleUnauthorized();
    throw new ApiError(errorText(data, 401), 401, data);
  }
  if (!res.ok) {
    const err = new ApiError(errorText(data, res.status), res.status, data);
    err.retryAfter = Number(res.headers.get('Retry-After')) || 0;
    throw err;
  }
  if (data && typeof data === 'object' && !Array.isArray(data) && typeof data.error === 'string' && data.error) {
    throw new ApiError(data.error, res.status, data);
  }
  return data;
}

const gamePath = (id, rest = '') => `/api/games/${encodeURIComponent(id)}${rest}`;

function handleUnauthorized() {
  if (S.signedIn) signedOut('Your session ended. Sign in again to continue.');
}

// ---- Layers: the game page, bottom sheets and dialogs. Each one is a history entry, so the phone's
// back gesture closes the top one.

const layers = [];
let popWaiters = [];

function syncLayers() {
  document.documentElement.classList.toggle('is-locked', layers.length > 0);
  if (E.shell) E.shell.inert = layers.length > 0;
  layers.forEach((l, i) => {
    l.el.inert = i < layers.length - 1;
  });
}

function pushLayer(layer) {
  layer.returnFocus = document.activeElement;
  layers.push(layer);
  quiet(() => history.pushState({ depth: layers.length }, ''));
  syncLayers();
  requestAnimationFrame(() => {
    const f = layer.focus || layer.el.querySelector('button, input');
    if (f && layer.el.isConnected) f.focus({ preventScroll: true });
  });
}

function dropLayer() {
  const l = layers.pop();
  if (!l) return;
  l.dismiss();
  const rf = l.returnFocus;
  if (rf && rf.isConnected && typeof rf.focus === 'function') rf.focus({ preventScroll: true });
}

function popLayers(n) {
  const count = Math.min(n, layers.length);
  if (count <= 0) return Promise.resolve();
  const target = layers.length - count;
  return new Promise((resolve) => {
    let settled = false;
    const done = () => {
      if (!settled) {
        settled = true;
        resolve();
      }
    };
    popWaiters.push(done);
    history.go(-count);
    setTimeout(() => {
      if (settled) return;
      while (layers.length > target) dropLayer();
      syncLayers();
      done();
    }, 700);
  });
}

function closeLayer(layer) {
  const i = layers.indexOf(layer);
  if (i < 0) return Promise.resolve();
  return popLayers(layers.length - i);
}

function dismissAllLayers() {
  const n = layers.length;
  while (layers.length) dropLayer();
  syncLayers();
  if (n) {
    quiet(() => history.go(-n));
  }
}

window.addEventListener('popstate', (e) => {
  const depth = e.state && typeof e.state.depth === 'number' ? e.state.depth : 0;
  while (layers.length > depth) dropLayer();
  if (depth > layers.length) {
    quiet(() => history.replaceState({ depth: layers.length }, ''));
  }
  syncLayers();
  const w = popWaiters;
  popWaiters = [];
  w.forEach((f) => f());
});

function removeLater(el) {
  setTimeout(() => el.remove(), 260);
}

function openModal({ title, subtitle, body, footer, dialog = false, onDismiss }) {
  const titleId = uid('mt');
  const closeBtn = dialog ? null : h('button.icon-btn', { type: 'button', 'aria-label': 'Close' }, icon('x'));
  const bodyWrap = body ? h('div.panel-body', body) : null;
  const panel = h('div', {
    class: 'panel' + (dialog ? ' is-dialog' : ''),
    role: dialog ? 'alertdialog' : 'dialog',
    'aria-modal': 'true',
    'aria-labelledby': titleId,
  },
  dialog ? null : h('div.panel-grab', { 'aria-hidden': 'true' }),
  h('div.panel-head',
    h('div.panel-head-main',
      h('h2.panel-title', { id: titleId }, title),
      subtitle ? h('p.panel-sub', subtitle) : null),
    closeBtn),
  bodyWrap,
  footer ? h('div.panel-foot', footer) : null);
  const scrim = h('div.scrim');
  const el = h('div.modal', scrim, panel);
  const layer = {
    el,
    open: true,
    focus: closeBtn,
    body: bodyWrap,
    dismiss() {
      layer.open = false;
      el.classList.add('is-leaving');
      removeLater(el);
      if (onDismiss) onDismiss();
    },
  };
  scrim.addEventListener('click', () => closeLayer(layer));
  if (closeBtn) closeBtn.addEventListener('click', () => closeLayer(layer));
  E.layers.append(el);
  pushLayer(layer);
  return layer;
}

function confirmDialog({ title, text, confirm = 'Continue', cancel = 'Cancel', danger = false }) {
  return new Promise((resolve) => {
    let result = false;
    let layer = null;
    const yes = btn(confirm, { kind: danger ? 'danger' : 'primary', onClick: () => { result = true; closeLayer(layer); } });
    const no = btn(cancel, { kind: 'secondary', onClick: () => closeLayer(layer) });
    layer = openModal({ title, subtitle: text, dialog: true, footer: h('div.dialog-actions', no, yes), onDismiss: () => resolve(result) });
    layer.focus = yes;
  });
}

function openMenu({ title, subtitle, items, onPick }) {
  let layer = null;
  const list = h('div.menu', { role: 'radiogroup', 'aria-label': title });
  for (const it of items) {
    const b = h('button.menu-item', { type: 'button', role: 'radio', 'aria-checked': String(!!it.checked) },
      it.lead || null,
      h('span.menu-item-main',
        h('span.menu-item-title', it.title),
        it.sub ? h('span.menu-item-sub', it.sub) : null),
      h('span.check-disc', { 'aria-hidden': 'true' }, icon('check')));
    b.addEventListener('click', async () => {
      await closeLayer(layer);
      onPick(it.id);
    });
    list.append(b);
  }
  layer = openModal({ title, subtitle, body: list });
  layer.focus = list.querySelector('[aria-checked="true"]') || list.firstElementChild;
  reveal(list.children, 1);
  return layer;
}

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && layers.length) {
    e.preventDefault();
    closeLayer(layers[layers.length - 1]);
  }
});

// ---- Toasts

function toast(message, type = 'info', ms = 3400) {
  const ic = type === 'success' ? 'circle-check' : type === 'error' ? 'circle-alert' : 'info';
  const t = h('div', { class: `toast toast-${type}`, role: type === 'error' ? 'alert' : null }, icon(ic), h('span', message));
  E.toasts.append(t);
  while (E.toasts.children.length > 3) E.toasts.firstElementChild.remove();
  setTimeout(() => {
    t.classList.add('is-leaving');
    setTimeout(() => t.remove(), 240);
  }, ms);
}

// ---- Boot and sign-in

function show(which) {
  E.boot.hidden = which !== 'boot';
  E.signin.hidden = which !== 'signin';
  E.shell.hidden = which !== 'shell';
}

async function checkSession() {
  show('boot');
  E.boot.classList.remove('is-error');
  E.bootMsg.hidden = true;
  try {
    const s = await api('/api/session', { auth: false });
    S.session = s && typeof s === 'object' ? s : {};
    applySessionInfo();
    if (S.session.signedIn) enterApp();
    else showSignin();
  } catch (e) {
    E.boot.classList.add('is-error');
    // The title already says Fuse can't be reached: the line under it says what to check.
    E.bootText.textContent = e.status === 0
      ? 'Check that Fuse is running with Phone Link on, and that this phone is on the same Wi-Fi.'
      : e.message || NET_ERROR;
    E.bootMsg.hidden = false;
    if (!coarse.matches) E.bootRetry.focus();
  }
}

function applySessionInfo() {
  applyTheme(S.session.theme);
  const dev = S.session.device;
  if (typeof dev === 'string' && dev.trim()) {
    E.signinSub.replaceChildren('Sign in to manage the Fuse library on ', h('span.nowrap', dev.trim()), '.');
  } else {
    E.signinSub.textContent = 'Sign in to manage your Fuse library.';
  }
  E.signinFoot.textContent = S.session.version ? `Fuse ${S.session.version}` : '';
  // Captures exist where the device takes them (Android); elsewhere the tab isn't there.
  E.capTabBtn.hidden = !capturesOn();
  if (!capturesOn() && S.tab === 'captures' && S.signedIn) selectTab('now');
  document.title = typeof dev === 'string' && dev.trim() ? `Fuse on ${dev.trim()}` : 'Fuse Phone Link';
  setConn(S.conn);
}

const lock = { until: 0, timer: 0 };

function showSignin(note) {
  const wasShown = !E.signin.hidden;
  show('signin');
  window.scrollTo(0, 0);
  if (!wasShown) reveal($$('.signin-inner > *', E.signin));
  hideSigninError();
  if (note) {
    E.noteText.textContent = note;
    E.note.hidden = false;
  } else {
    E.note.hidden = true;
  }
  if (!coarse.matches) {
    requestAnimationFrame(() => (E.user.value ? E.pass : E.user).focus());
  }
}

function showSigninError(message) {
  E.errText.textContent = message;
  E.err.hidden = false;
  E.note.hidden = true;
  E.err.classList.remove('is-shaking');
  void E.err.offsetWidth;
  E.err.classList.add('is-shaking');
}

function hideSigninError() {
  if (lock.until) return;
  E.err.hidden = true;
  E.countdown.hidden = true;
}

function startLockout(seconds, message) {
  clearInterval(lock.timer);
  const secs = Math.max(1, Math.ceil(num(seconds) || 60));
  lock.until = Date.now() + secs * 1000;
  showSigninError(message || 'Too many tries. Sign-in is locked for a moment.');
  E.countdown.hidden = false;
  const step = () => {
    const left = Math.ceil((lock.until - Date.now()) / 1000);
    if (left <= 0) {
      clearInterval(lock.timer);
      lock.timer = 0;
      lock.until = 0;
      E.submit.disabled = false;
      hideSigninError();
      E.noteText.textContent = 'You can try signing in again.';
      E.note.hidden = false;
      return;
    }
    E.countdown.textContent = `You can try again in ${fmtClock(left)}.`;
    E.submit.disabled = true;
  };
  step();
  lock.timer = setInterval(step, 250);
}

async function onSignin(e) {
  e.preventDefault();
  if (lock.until || E.submit.classList.contains('is-busy')) return;
  const username = E.user.value.trim();
  const password = E.pass.value;
  if (!username || !password) {
    showSigninError(!username ? 'Enter your username.' : 'Enter your password.');
    (!username ? E.user : E.pass).focus();
    return;
  }
  hideSigninError();
  E.note.hidden = true;
  setBusy(E.submit, true);
  E.submitLabel.textContent = 'Signing in';
  try {
    await api('/api/login', { method: 'POST', body: { username, password }, auth: false });
    E.pass.value = '';
    S.session.signedIn = true;
    setBusy(E.submit, false);
    E.submitLabel.textContent = 'Sign in';
    enterApp();
  } catch (err) {
    setBusy(E.submit, false);
    E.submitLabel.textContent = 'Sign in';
    if (err.status === 429) {
      startLockout((err.data && err.data.retryAfterSeconds) || err.retryAfter, err.message);
    } else {
      showSigninError(err.status === 401 && (!err.data || !err.data.error) ? 'That username and password do not match.' : err.message);
      if (err.status === 401) {
        E.pass.focus();
        E.pass.select();
      }
    }
  }
}

function enterApp() {
  S.signedIn = true;
  show('shell');
  E.shell.classList.add('is-entering');
  setTimeout(() => E.shell.classList.remove('is-entering'), 400);
  applySessionInfo();
  setConn('connecting');
  S.systemsReady = loadSystems();
  renderNow();
  buildTools();
  selectTab(S.tab, { initial: true });
  refreshNow();
  startLive();
}

// ---- Theme: the device's own theme when it sends one, else the Fuse theme (or Daylight on a phone
// set to light) from the stylesheet. The roles are derived the way FuseColors.from derives them.

function parseColor(v) {
  const m = /^#?([0-9a-f]{6})([0-9a-f]{2})?$/i.exec(String(v || '').trim());
  if (!m) return null;
  const n = parseInt(m[1], 16);
  return { c: [(n >> 16) & 255, (n >> 8) & 255, n & 255], a: m[2] ? parseInt(m[2], 16) / 255 : 1 };
}

function luminance(c) {
  const ch = (v) => {
    const x = v / 255;
    return x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * ch(c[0]) + 0.7152 * ch(c[1]) + 0.0722 * ch(c[2]);
}

function contrast(a, b) {
  const la = luminance(a);
  const lb = luminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

let themeKey = '';
function applyTheme(t) {
  const root = document.documentElement;
  const p = t && typeof t === 'object' ? t : null;
  const get = (k) => p && parseColor(p[k]);
  const need = ['background', 'surface', 'surfaceRaised', 'accent', 'text', 'textMuted'];
  const key = p ? JSON.stringify(p) : '';
  if (key === themeKey) return;
  themeKey = key;
  if (!p || !need.every((k) => get(k))) {
    root.removeAttribute('data-theme');
    root.removeAttribute('data-light');
    root.removeAttribute('style');
    syncThemeColor();
    return;
  }
  const dark = p.dark !== false;
  const ink = get('background').c;
  const surface = get('surface').c;
  const raised = get('surfaceRaised').c;
  const text = get('text').c;
  const muted = get('textMuted').c;
  const accent = get('accent').c;
  const soft = get('accentSoft');
  const on = get('onAccent');
  const role = (k, fallback) => (get(k) ? get(k).c : fallback);
  // Tertiary text: muted faded as far as it can go while still reading at 4.5:1 everywhere.
  let faint = 0.62;
  while (faint < 0.92 && ![ink, surface, raised].every((bg) => contrast(mix(bg, muted, faint), bg) >= 4.5)) faint += 0.02;
  const success = role('success', [61, 214, 140]);
  const warning = role('warning', [255, 181, 71]);
  const danger = role('danger', [255, 93, 108]);
  const shadow = dark ? [0, 0, 0] : mix(text, [0, 0, 0], 0.4);
  const v = {
    '--ink': rgb(ink),
    '--dim': rgb(dark ? mix(ink, surface, 0.5) : mix(ink, text, 0.04)),
    '--surface': rgb(surface),
    '--raised': rgb(raised),
    '--overlay': rgb(dark ? mix(raised, text, 0.035) : surface),
    '--text': rgb(text),
    '--muted': rgb(muted),
    '--faint': rgba(muted, Math.min(0.92, faint).toFixed(2)),
    '--hairline': rgba(text, dark ? 0.09 : 0.14),
    '--hairline-strong': rgba(text, dark ? 0.16 : 0.24),
    '--hover': rgba(text, dark ? 0.06 : 0.05),
    '--pressed': rgba(text, dark ? 0.1 : 0.08),
    '--skeleton': rgba(text, 0.07),
    '--shimmer': rgba([255, 255, 255], dark ? 0.07 : 0.55),
    '--accent': rgb(accent),
    '--accent-soft': soft ? rgba(soft.c, soft.a.toFixed(3)) : rgba(accent, 0.2),
    '--accent-line': rgba(accent, 0.6),
    '--on-accent': rgb(on ? on.c : luminance(accent) > 0.4 ? [12, 12, 14] : [255, 255, 255]),
    '--focus': rgb(role('focus', dark ? [255, 255, 255] : text)),
    '--success': rgb(success),
    '--warning': rgb(warning),
    '--danger': rgb(danger),
    '--success-soft': rgba(success, dark ? 0.13 : 0.11),
    '--warning-soft': rgba(warning, dark ? 0.13 : 0.11),
    '--danger-soft': rgba(danger, dark ? 0.13 : 0.1),
    '--scrim': rgba(dark ? ink : text, dark ? 0.72 : 0.42),
    '--glass': rgba(surface, dark ? 0.82 : 0.84),
    '--shadow': shadow.join(', '),
    '--edge-tile': dark ? '.14' : '.55',
    '--edge-panel': dark ? '.12' : '.5',
    '--edge-raised': dark ? '.16' : '.6',
    '--edge-overlay': dark ? '.18' : '.7',
  };
  root.setAttribute('style', '');
  for (const k of Object.keys(v)) root.style.setProperty(k, v[k]);
  if (!dark) {
    root.style.setProperty('--sh-panel', '0 1px 2px rgba(var(--shadow), .08), 0 6px 18px -6px rgba(var(--shadow), .16)');
    root.style.setProperty('--sh-raised', '0 2px 4px rgba(var(--shadow), .08), 0 10px 26px -8px rgba(var(--shadow), .2)');
    root.style.setProperty('--sh-overlay', '0 8px 20px rgba(var(--shadow), .12), 0 24px 64px -12px rgba(var(--shadow), .3)');
  }
  root.setAttribute('data-theme', 'device');
  root.toggleAttribute('data-light', !dark);
  root.style.setProperty('color-scheme', dark ? 'dark' : 'light');
  syncThemeColor();
}

/** The browser's own bars take the room's colour: the device theme's, or the page's own per scheme. */
function syncThemeColor() {
  const device = document.documentElement.getAttribute('data-theme') === 'device';
  const ink = device ? document.documentElement.style.getPropertyValue('--ink').trim() : '';
  for (const m of $$('meta[name="theme-color"]')) {
    if (!m.dataset.ink) m.dataset.ink = m.getAttribute('content') || '';
    m.setAttribute('content', ink || m.dataset.ink);
  }
}

function signedOut(note) {
  S.signedIn = false;
  stopLive();
  dismissAllLayers();
  resetData();
  showSignin(note);
}

function resetData() {
  S.now = null;
  S.nowLoaded = false;
  S.nowError = null;
  S.fill = null;
  S.fillDismissed = false;
  S.fillPendingUntil = 0;
  S.systems = [];
  S.sysById = new Map();
  S.toolsSystem = '';
  S.scroll = {};
  L.gen += 1;
  if (L.ctrl) L.ctrl.abort();
  Object.assign(L, { started: false, query: '', system: '', sort: 'title', items: [], total: null, loading: false, done: false, error: null, ctrl: null, skel: [] });
  L.cache.clear();
  nowUI = null;
  tools = null;
  G = null;
  E.nowBody.replaceChildren();
  E.toolsBody.replaceChildren();
  E.grid.replaceChildren();
  E.libStatus.replaceChildren();
  E.libChips.replaceChildren();
  E.libQ.value = '';
  E.libQClear.hidden = true;
  E.libCount.textContent = '';
  E.libSortLabel.textContent = SORTS[0].label;
  resetCaptures();
}

async function signOut(b) {
  setBusy(b, true);
  try {
    await api('/api/logout', { method: 'POST', auth: false });
  } catch (e) {
    if (e.status === 0) {
      setBusy(b, false);
      toast("Couldn't sign out. Check the connection and try again.", 'error');
      return;
    }
  }
  signedOut('You are signed out.');
}

// ---- Tabs and the connection indicator

function selectTab(tab, opts = {}) {
  if (!TABS.includes(tab) || (tab === 'captures' && !capturesOn())) tab = 'now';
  if (!opts.initial && tab === S.tab) {
    window.scrollTo({ top: 0, behavior: reducedMotion.matches ? 'auto' : 'smooth' });
    return;
  }
  if (!opts.initial) S.scroll[S.tab] = window.scrollY;
  S.tab = tab;
  let shown = null;
  for (const sec of $$('.tab', E.shell)) {
    const on = sec.dataset.tab === tab;
    sec.hidden = !on;
    if (on) shown = sec;
  }
  for (const b of $$('.tab-btn', E.shell)) {
    if (b.dataset.tab === tab) b.setAttribute('aria-current', 'page');
    else b.removeAttribute('aria-current');
  }
  placeTabGlide(!opts.initial);
  quiet(() => history.replaceState(history.state, '', `#${tab}`));
  window.scrollTo(0, S.scroll[tab] || 0);
  if (tab === 'library') {
    ensureLibrary();
    checkSentinel();
  }
  if (tab === 'tools') updateTools();
  if (tab === 'captures') ensureCaptures();
  if (shown) {
    // Entering a section: its title, then its blocks in reading order, rise into place.
    reveal($$(':scope > .topbar, :scope > .lib-bar, :scope > .lib-meta, :scope > .grid, :scope > .lib-status, :scope > .cap-bar, :scope > .cap-meta, :scope > .tab-body > *, :scope > .cap-body > *', shown));
    S.enteredAt = performance.now();
    relayoutGlides();
  }
}

/**
 * The spark in the tab bar: a short accent bar under (on a wide screen, below) the active section.
 * It glides to the next tab with its leading edge first and the trailing one catching up.
 */
function placeTabGlide(animate) {
  const g = E.tabGlide;
  const b = E.shell && $(`.tab-btn[data-tab="${S.tab}"]`, E.shell);
  if (!g || !b || !b.offsetWidth) return;
  const width = g.parentElement.clientWidth;
  const bar = wideScreen.matches ? 18 : 28;
  const center = b.offsetLeft + b.offsetWidth / 2;
  const left = Math.round(center - bar / 2);
  const right = Math.round(width - (center + bar / 2));
  const prev = g._left;
  g._left = left;
  if (animate && prev != null && prev !== left) {
    g.classList.toggle('to-right', left > prev);
    g.classList.toggle('to-left', left < prev);
    g.classList.add('is-moving');
    clearTimeout(g._t);
    g._t = setTimeout(() => g.classList.remove('is-moving'), 500);
  } else if (!animate) {
    g.classList.remove('is-moving');
  }
  g.style.setProperty('--gl', `${left}px`);
  g.style.setProperty('--gr', `${right}px`);
}

/** Puts every gliding marker back in place after a resize, a font arriving or a tab showing. */
function relayoutGlides() {
  placeTabGlide(false);
  if (E.libChips && E.libChips._glide) placeGlide(E.libChips._glide, $('.chip[aria-pressed="true"]', E.libChips), false);
  if (E.capFilters && E.capFilters._seg) placeGlide(E.capFilters._seg.glide, $('.seg-btn[aria-pressed="true"]', E.capFilters), false);
}

function setConn(state) {
  S.conn = state;
  const label = state === 'offline' ? 'Offline' : S.session.device || 'Fuse';
  const detail = {
    live: 'Live updates are on',
    polling: 'Updating every few seconds',
    connecting: 'Connecting',
    offline: "Can't reach the device right now",
  }[state] || '';
  const offline = state === 'offline' && S.signedIn;
  E.offline.hidden = !offline;
  document.body.classList.toggle('is-offline', offline);
  if (offline) E.offlineText.textContent = `Can't reach ${deviceName()}. Trying again.`;
  for (const el of $$('[data-conn]')) {
    el.dataset.state = state;
    el.title = detail;
    let lab = el.querySelector('.conn-label');
    lab.textContent = label;
    let sr = el.querySelector('.visually-hidden');
    if (!sr) {
      sr = h('span.visually-hidden');
      el.append(sr);
    }
    sr.textContent = `. ${detail}`;
  }
}

// ---- Live updates: Server-Sent Events with reconnect and backoff; polling /api/now while it is down.

const live = { es: null, retry: 0, timer: 0, poll: 0, hiddenTimer: 0 };

function parseJSON(s) {
  try {
    return JSON.parse(s);
  } catch (_) {
    return undefined;
  }
}

function startLive() {
  stopLive();
  if (!S.signedIn) return;
  if (typeof EventSource !== 'function') {
    startPolling();
    return;
  }
  let es;
  try {
    es = new EventSource('/api/events', { withCredentials: true });
  } catch (_) {
    startPolling();
    scheduleReconnect();
    return;
  }
  live.es = es;
  es.addEventListener('open', () => {
    if (live.es !== es) return;
    live.retry = 0;
    stopPolling();
    setConn('live');
    refreshNow();
  });
  es.addEventListener('now', (e) => {
    const d = parseJSON(e.data);
    if (d && typeof d === 'object') applyNow(d);
  });
  es.addEventListener('fill', (e) => {
    const d = parseJSON(e.data);
    if (d !== undefined) setFill(d);
  });
  es.addEventListener('library', onLibraryChanged);
  es.addEventListener('captures', onCapturesChanged);
  es.addEventListener('error', () => {
    if (live.es !== es) return;
    es.close();
    live.es = null;
    if (S.conn === 'live' || S.conn === 'connecting') setConn('polling');
    startPolling();
    scheduleReconnect();
  });
}

function scheduleReconnect() {
  clearTimeout(live.timer);
  const base = Math.min(30000, 1000 * 2 ** Math.min(live.retry, 5));
  live.retry += 1;
  live.timer = setTimeout(startLive, base * (0.75 + Math.random() * 0.5));
}

function stopLive() {
  if (live.es) {
    live.es.close();
    live.es = null;
  }
  clearTimeout(live.timer);
  live.timer = 0;
  stopPolling();
}

function startPolling() {
  if (live.poll) return;
  refreshNow();
  live.poll = setInterval(refreshNow, 5000);
}

function stopPolling() {
  clearInterval(live.poll);
  live.poll = 0;
}

let nowInFlight = false;
async function refreshNow() {
  if (!S.signedIn || nowInFlight) return;
  nowInFlight = true;
  try {
    const d = await api('/api/now');
    if (!S.signedIn) return;
    applyNow(d || {});
    const wasOffline = S.conn === 'offline';
    if (!live.es || live.es.readyState !== 1) setConn('polling');
    else setConn('live');
    if (wasOffline && !live.es) {
      // The device is back: try the event stream again now instead of waiting for the backoff.
      live.retry = 0;
      startLive();
    }
  } catch (e) {
    if (e.status === 401 || !S.signedIn) return;
    if (!S.nowLoaded) {
      S.nowError = e;
      renderNow();
    }
    if (e.status === 0) setConn('offline');
  } finally {
    nowInFlight = false;
  }
}

function applyNow(d) {
  S.now = d;
  S.nowLoaded = true;
  S.nowError = null;
  if ('fill' in d) setFill(d.fill);
  renderNow();
}

function setFill(f) {
  const next = f && typeof f === 'object' ? f : null;
  if (!next && S.fillPendingUntil > Date.now()) return;
  if (next) S.fillPendingUntil = 0;
  const prev = S.fill;
  S.fill = next;
  if (next && !next.finished) S.fillDismissed = false;
  if (prev && !prev.finished && !prev.pending && next && next.finished) {
    toast(`Art fill finished. ${plural(num(next.added), 'image', 'images')} added.`, 'success');
  }
  updateFillViews();
  updateTools();
}

let libTimer = 0;
let libLast = 0;
function onLibraryChanged() {
  // Throttled: during a bulk fill the library can change many times a second.
  if (libTimer) return;
  const wait = Math.max(300, 2500 - (Date.now() - libLast));
  libTimer = setTimeout(() => {
    libTimer = 0;
    libLast = Date.now();
    if (!S.signedIn) return;
    S.systemsReady = loadSystems();
    if (L.started) reloadLibrary({ keep: true });
    if (G) loadGame({ quiet: true });
  }, wait);
}

document.addEventListener('visibilitychange', () => {
  if (!S.signedIn) return;
  if (document.hidden) {
    clearTimeout(live.hiddenTimer);
    live.hiddenTimer = setTimeout(stopLive, 20000);
  } else {
    clearTimeout(live.hiddenTimer);
    if (!live.es) {
      live.retry = 0;
      startLive();
    }
    refreshNow();
    tick(true);
    if (C.started) refreshCaptures(true);
  }
});

window.addEventListener('online', () => {
  if (S.signedIn && !live.es) {
    live.retry = 0;
    startLive();
  }
});

let tickN = 0;
function tick(force) {
  if (document.hidden || !S.signedIn) return;
  tickN += 1;
  const t = nowMs();
  for (const el of $$('[data-since]')) {
    const s = Number(el.dataset.since);
    if (s) el.textContent = fmtClock((t - s) / 1000);
  }
  if (force === true || tickN % 20 === 0) {
    for (const el of $$('[data-ago]')) el.textContent = fmtAgo(el.dataset.ago);
  }
}

// ---- Systems

async function loadSystems() {
  try {
    const r = await api('/api/systems');
    if (!S.signedIn) return;
    const list = (Array.isArray(r) ? r : []).filter((s) => s && s.id != null && (s.gameCount == null || num(s.gameCount) > 0));
    S.systems = list;
    S.sysById = new Map(list.map((s) => [s.id, s]));
    if (L.system && !S.sysById.has(L.system)) {
      L.system = '';
      if (L.started) reloadLibrary();
    }
    if (L.started) renderChips();
    if (nowUI) renderNow();
    updateTools();
  } catch (_) {
    /* the library still works without systems; chips just stay empty */
    if (L.started && !S.systems.length) renderChips();
  }
}

// ---- Now

function nowSkeleton() {
  const skRow = () => h('div.row',
    h('div.sk', { style: { width: '36px', height: '36px', 'border-radius': '10px', flex: 'none' } }),
    h('div.row-main', h('div.sk.sk-line', { style: { width: '64%' } }), h('div.sk.sk-line', { style: { width: '36%' } })));
  return h('div.now-skel', { 'aria-busy': 'true', 'aria-label': 'Loading' },
    h('div.section', h('div.sk.sk-block', { style: { height: '240px' } })),
    h('div.section',
      h('div.sk.sk-title', { style: { width: '36%', 'margin-bottom': '12px' } }),
      h('div.card', skRow(), skRow(), skRow())));
}

function renderNow() {
  if (!S.signedIn) return;
  if (!S.nowLoaded) {
    nowUI = null;
    if (S.nowError) {
      E.nowBody.replaceChildren(h('div.card', errorState("Couldn't load what's happening", S.nowError, () => {
        S.nowError = null;
        renderNow();
        refreshNow();
      })));
    } else if (!E.nowBody.querySelector('.now-skel')) {
      E.nowBody.replaceChildren(nowSkeleton());
    }
    return;
  }
  let arriving = false;
  if (!nowUI) {
    nowUI = {
      playing: h('section.section', { 'aria-label': 'Playing now' }),
      cart: h('section.section', { 'aria-label': 'Cartridge downloads' }),
      fill: h('section.section', { 'aria-label': 'Art fill' }),
      fillCard: createFillCard(),
    };
    nowUI.fill.append(sectionHead('Art fill'), nowUI.fillCard.el);
    E.nowBody.replaceChildren(nowUI.playing, nowUI.cart, nowUI.fill);
    arriving = true;
  }
  updatePlaying();
  updateCartridge();
  updateFillViews();
  // What replaces the placeholders rises in, card by card; later updates change in place.
  if (arriving && S.tab === 'now') reveal($$(':scope > .section > *', E.nowBody));
}

function updatePlaying() {
  const n = S.now || {};
  const g = n.playing && typeof n.playing === 'object' ? n.playing : null;
  const key = g
    ? JSON.stringify([g.id, g.title, g.cover, g.hero, g.system, g.systemName, g.year, n.playingSince, S.systems.length])
    : `none|${deviceName()}`;
  keyed(nowUI.playing, key, () => (g ? playingCard(g, n.playingSince) : playingEmpty()));
}

function playingCard(g, since) {
  const acc = accentFor(g.system);
  const title = g.title || 'Untitled';
  const meta = [sysName(g.system, g.systemName), g.year].filter(Boolean).join(' \u00B7 ');
  const card = h('button.playing', { type: 'button', 'aria-label': `Playing now: ${title}. Open the game page.` },
    h('span.playing-bg',
      art(g.hero || g.cover, { title, system: g.system, blur: !g.hero, text: false, eager: true, flat: true, ar: 1.6 })),
    icon('chevron-right', 'playing-chevron'),
    h('span.playing-row',
      h('span.playing-cover', art(g.cover, { title, system: g.system, eager: true, fit: true, ar: 0.72, tag: sysShort(g.system) })),
      h('span.playing-main',
        h('span.live-label', h('span.live-dot'), 'Playing now'),
        h('span.playing-title', title),
        meta ? h('span.playing-meta', meta) : null,
        since ? h('span.elapsed',
          icon('clock'),
          h('span', { 'data-since': String(since) }, fmtClock((nowMs() - Number(since)) / 1000)),
          h('span.elapsed-label', 'this session')) : null)));
  card.style.setProperty('--glow', rgba(acc, 0.45));
  card.addEventListener('click', () => openGame(g.id, g));
  return card;
}

function playingEmpty() {
  return h('div.card.playing-empty',
    h('div.card-icon.is-muted', icon('gamepad-2')),
    h('div.card-head-main',
      h('p.card-title', 'Nothing is playing'),
      h('p.card-text', `Start a game on ${deviceName()} and it shows up here.`)));
}

function setBar(bar, frac) {
  const fill = bar.firstElementChild;
  if (frac == null || !Number.isFinite(frac)) {
    bar.classList.add('is-indeterminate');
    bar.removeAttribute('aria-valuenow');
    return;
  }
  bar.classList.remove('is-indeterminate');
  const p = clamp01(frac) * 100;
  fill.style.setProperty('width', `${p.toFixed(1)}%`);
  bar.setAttribute('aria-valuenow', String(Math.round(p)));
}

function progressBar(label, extra) {
  return h('div', { class: 'bar' + (extra ? ' ' + extra : ''), role: 'progressbar', 'aria-label': label, 'aria-valuemin': '0', 'aria-valuemax': '100' }, h('i'));
}

function updateCartridge() {
  const slot = nowUI.cart;
  const c = S.now && S.now.cartridge && typeof S.now.cartridge === 'object' ? S.now.cartridge : null;
  if (!c || c.enabled === false) {
    slot.hidden = true;
    slot._key = null;
    slot.replaceChildren();
    return;
  }
  slot.hidden = false;
  const queue = (Array.isArray(c.queue) ? c.queue : []).filter((q) => q && typeof q === 'object');
  const cur = c.current && typeof c.current === 'object' ? c.current : null;
  const curQ = cur ? queue.find((q) => q.state === 'downloading' && q.title === cur.title) || null : null;
  const upNext = queue.filter((q) => q !== curQ);
  const recent = (Array.isArray(c.recent) ? c.recent : []).filter((r) => r && typeof r === 'object');
  const queued = num(c.queued);
  const key = JSON.stringify([
    c.installed, c.connected, cur && [cur.title, cur.platform], !!curQ,
    upNext.map((q) => [q.romId, q.title, q.platform, q.state, num(q.total) > 0]),
    queued, recent.map((r) => [r.romId, r.title, r.platform, r.gameId, r.finishedAt]),
    S.systems.length, deviceName(),
  ]);
  keyed(slot, key, () => cartridgeSection(slot, c, cur, upNext, recent, queued));
  const refs = slot._refs || {};
  if (refs.cur && cur) {
    let frac = cur.progress != null && cur.progress !== '' ? num(cur.progress) : null;
    if (frac != null && frac > 1) frac /= 100;
    if (frac == null && curQ && num(curQ.total) > 0) frac = num(curQ.received) / num(curQ.total);
    setBar(refs.cur.bar, frac);
    const pctEl = refs.cur.pct;
    if (frac == null) {
      pctEl._v = null;
      pctEl.replaceChildren();
    } else {
      rollNumber(pctEl, Math.floor(clamp01(frac) * 100), (n) => pctEl.replaceChildren(String(n), h('small', '%')));
    }
    const size = curQ && num(curQ.total) > 0 ? `${fmtBytes(curQ.received)} of ${fmtBytes(curQ.total)}` : '';
    refs.cur.sub.textContent = [sysShort(cur.platform), size].filter(Boolean).join(' \u00B7 ');
  }
  (refs.queue || []).forEach((r, i) => updateQueueRow(r, upNext[i]));
}

function cartridgeSection(slot, c, cur, upNext, recent, queued) {
  const refs = { cur: null, queue: [] };
  slot._refs = refs;
  let status;
  if (c.installed === false) status = h('span.pill', 'Not installed');
  else if (c.connected === false) status = h('span.pill.pill-warning', h('span.pill-dot'), 'Not connected');
  else status = h('span.pill.pill-success', h('span.pill-dot'), 'Connected');
  const out = [sectionHead('Cartridge', status)];

  if (c.installed === false) {
    out.push(h('div.card', emptyState('cloud-download', 'Cartridge is not installed',
      `Install Cartridge on ${deviceName()} to download games into your library.`, null, { compact: true })));
    return out;
  }
  if (c.connected === false) {
    out.push(h('div.alert.alert-warning', { style: { 'margin-bottom': '12px' } },
      icon('wifi-off'), h('div.alert-body', "Fuse can't reach Cartridge right now, so this may be out of date.")));
  }

  if (cur) {
    const bar = progressBar(`Downloading ${cur.title || 'a game'}`);
    const pct = h('span.dl-pct');
    const sub = h('p.dl-sub');
    refs.cur = { bar, pct, sub };
    out.push(h('div.card.dl-current',
      h('div.dl-row',
        platBadge(cur.platform),
        h('div.dl-main',
          h('p.overline.state-downloading', 'Downloading'),
          h('p.dl-title', cur.title || 'Unknown game'),
          sub),
        pct),
      bar));
  }

  if (upNext.length || queued > 0) {
    const rows = upNext.map((q, i) => {
      const r = queueRow(q);
      refs.queue[i] = r;
      return r.el;
    });
    out.push(h('div.card',
      h('div.list-head',
        h('p.overline', 'Up next'),
        queued > 0 ? h('span.list-head-meta', `${nf.format(queued)} queued`) : null),
      rows.length ? h('div.list', rows)
        : h('p.card-text', { style: { 'margin-top': '6px' } }, `${plural(queued, 'game is', 'games are')} waiting to download.`)));
  }

  if (recent.length) {
    out.push(h('div.card',
      h('div.list-head', h('p.overline', 'Recently downloaded')),
      h('div.list', recent.map(recentRow))));
  }

  if (!cur && !upNext.length && !recent.length && !(queued > 0)) {
    out.push(h('div.card', emptyState('cloud-download', 'Nothing downloading',
      'Games you download with Cartridge show up here while they download.', null, { compact: true })));
  }
  return out;
}

function queueRow(q) {
  const st = QUEUE_STATES[q.state] || QUEUE_STATES.queued;
  const sub = h('span.row-sub');
  const hasSize = num(q.total) > 0;
  const bar = hasSize ? progressBar(`${q.title || 'Game'} progress`, 'bar-thin ' + st.bar) : null;
  const el = h('div.row',
    platBadge(q.platform, true),
    h('div.row-main', h('span.row-title', q.title || 'Unknown game'), sub, bar));
  return { el, sub, bar, st };
}

function updateQueueRow(r, q) {
  if (!r || !q) return;
  const total = num(q.total);
  const received = num(q.received);
  const size = total > 0 ? `${fmtBytes(received)} of ${fmtBytes(total)}` : '';
  r.sub.replaceChildren(
    h('span', { class: r.st.cls }, icon(r.st.icon), r.st.label),
    sysShort(q.platform) ? ` \u00B7 ${sysShort(q.platform)}` : '',
    size ? ` \u00B7 ${size}` : '');
  if (r.bar) setBar(r.bar, total > 0 ? received / total : null);
}

function recentRow(r) {
  const inLib = r.gameId != null && r.gameId !== '';
  const sub = h('span.row-sub',
    inLib ? h('span.state-in-lib', icon('circle-check'), 'In your library') : sysShort(r.platform),
    r.finishedAt ? ' \u00B7 ' : '',
    r.finishedAt ? h('span', { 'data-ago': String(r.finishedAt) }, fmtAgo(r.finishedAt)) : null);
  const kids = [
    platBadge(r.platform, true),
    h('span.row-main', h('span.row-title', r.title || 'Unknown game'), sub),
  ];
  if (!inLib) return h('div.row', kids);
  const b = h('button.row', { type: 'button', 'aria-label': `${r.title || 'Game'}, in your library. Open the game page.` },
    kids, h('span.row-aside', icon('chevron-right', 'i-sm')));
  b.addEventListener('click', () => openGame(r.gameId, null));
  return b;
}

// Fill progress card, shown on Now and on Tools.
function createFillCard() {
  const iconBox = h('div.card-icon', icon('wand-sparkles'));
  const titleText = h('span');
  const cancel = btn('Cancel', { size: 'sm', onClick: cancelFill });
  const dismiss = h('button.icon-btn', { type: 'button', 'aria-label': 'Dismiss' }, icon('x'));
  dismiss.addEventListener('click', () => {
    S.fillDismissed = true;
    updateFillViews();
  });
  // The action sits on the title's line, so the lines under it keep the card's full width.
  const title = h('p.card-title', titleText, cancel, dismiss);
  const text = h('p.card-text');
  const bar = progressBar('Art fill progress');
  const current = h('span.fill-current');
  const count = h('span.fill-count');
  const note = h('p.fill-note', { hidden: true });
  const el = h('div.card.fill-card',
    h('div.card-head', iconBox, h('div.card-head-main', title, text)),
    bar,
    h('div.fill-stats', current, count),
    note);
  let finishedShown = null;
  function update(f) {
    const fin = !!f.finished;
    const total = num(f.total);
    const done = num(f.done);
    const added = num(f.added);
    if (finishedShown !== fin) {
      finishedShown = fin;
      iconBox.className = 'card-icon' + (fin ? ' is-success' : '');
      iconBox.replaceChildren(icon(fin ? 'circle-check' : 'wand-sparkles'));
      bar.classList.toggle('is-success', fin);
    }
    cancel.hidden = fin;
    dismiss.hidden = !fin;
    let paused = [];
    if (fin) {
      titleText.textContent = f.automatic ? 'Art search finished' : 'Art fill finished';
      text.textContent = total > 0 ? `Checked ${plural(total, 'game', 'games')}.` : 'Nothing needed filling.';
      setBar(bar, 1);
      current.textContent = '';
    } else {
      titleText.textContent = f.pending ? 'Starting' : f.automatic ? 'Finding art for new games' : 'Filling art';
      paused = Array.isArray(f.paused) ? f.paused.filter((n) => typeof n === 'string') : [];
      text.textContent = total > 0 ? `${nf.format(Math.min(done, total))} of ${plural(total, 'game', 'games')} checked` : 'Getting the list of games ready';
      setBar(bar, total > 0 ? done / total : null);
      current.replaceChildren(f.current ? 'Working on ' : '', f.current ? h('b', String(f.current)) : '');
    }
    note.hidden = !paused.length;
    note.replaceChildren(...(paused.length ? [icon('hourglass'), h('span', `${paused.join(' and ')} ${paused.length === 1 ? 'is' : 'are'} out of requests for now, using the others.`)] : []));
    rollNumber(count, added, (n) => { count.textContent = `${plural(n, 'image', 'images')} added`; });
  }
  return { el, update };
}

function updateFillViews() {
  const f = S.fill;
  const visible = !!f && !(f.finished && S.fillDismissed);
  if (nowUI) {
    nowUI.fill.hidden = !visible;
    if (visible) nowUI.fillCard.update(f);
  }
  if (tools) {
    tools.fillCard.el.hidden = !visible;
    if (visible) tools.fillCard.update(f);
  }
}

async function cancelFill(b) {
  setBusy(b, true);
  try {
    await api('/api/fill/cancel', { method: 'POST' });
    S.fillPendingUntil = 0;
    toast('Stopping the art fill', 'info');
  } catch (e) {
    if (e.status !== 401) toast(e.message, 'error');
  } finally {
    setBusy(b, false);
  }
}

// ---- Library

function ensureLibrary() {
  if (L.started) return;
  L.started = true;
  renderChipSkeleton();
  E.grid.replaceChildren(...skeletonTiles(12));
  S.systemsReady.then(() => {
    if (!L.started) return;
    renderChips();
    reloadLibrary();
  });
}

function renderChipSkeleton() {
  E.libChips.classList.remove('has-glide');
  E.libChips._glide = null;
  E.libChips.replaceChildren(...[64, 88, 72, 96, 70].map((w) => h('span.chip.sk', { style: { width: `${w}px` }, 'aria-hidden': 'true' })));
}

function renderChips() {
  const chips = [chip('', 'All', null)].concat(S.systems.map((s) => chip(s.id, s.name || s.shortName || String(s.id), s)));
  // The chosen chip's pill is one element that glides from chip to chip.
  const glide = h('span.chip-glide', { 'aria-hidden': 'true' });
  E.libChips._glide = glide;
  E.libChips.classList.add('has-glide');
  E.libChips.replaceChildren(glide, ...chips);
  placeGlide(glide, $('.chip[aria-pressed="true"]', E.libChips), false);
}

function chip(id, label, sys) {
  const pressed = L.system === id;
  const count = sys && sys.gameCount != null ? `, ${plural(num(sys.gameCount), 'game', 'games')}` : '';
  const b = h('button.chip', { type: 'button', 'aria-pressed': String(pressed), 'aria-label': sys ? `${label}${count}` : 'All systems', title: sys ? label : 'All systems' });
  b.dataset.id = id;
  const logo = sys && safeUrl(sys.logo);
  if (logo) {
    const img = h('img', { alt: '', decoding: 'async', draggable: 'false' });
    img.referrerPolicy = 'no-referrer';
    img.addEventListener('error', () => img.replaceWith(document.createTextNode(label)), { once: true });
    img.src = logo;
    b.append(img);
  } else {
    b.append(label);
  }
  b.addEventListener('click', () => setSystem(id, b));
  return b;
}

function setSystem(id, el) {
  if (L.system === id) return;
  L.system = id;
  for (const c of $$('.chip', E.libChips)) c.setAttribute('aria-pressed', String(c.dataset.id === id));
  placeGlide(E.libChips._glide, el, true);
  if (el && el.scrollIntoView) el.scrollIntoView({ inline: 'nearest', block: 'nearest', behavior: reducedMotion.matches ? 'auto' : 'smooth' });
  reloadLibrary();
}

function setSort(id) {
  if (L.sort === id) return;
  L.sort = id;
  const s = SORTS.find((x) => x.id === id) || SORTS[0];
  E.libSortLabel.textContent = s.label;
  reloadLibrary();
}

function skeletonTiles(n) {
  const out = [];
  for (let i = 0; i < n; i += 1) {
    out.push(h('div.tile.is-skeleton', { 'aria-hidden': 'true' },
      h('div.sk.sk-art'),
      h('div.sk.sk-line', { style: { width: `${70 + ((i * 37) % 25)}%` } }),
      h('div.sk.sk-line', { style: { width: '42%' } })));
  }
  return out;
}

function scrollLibraryToTop() {
  const bar = E.libBar;
  const top = bar.getBoundingClientRect().top + window.scrollY;
  const stuck = window.scrollY > top - 1;
  if (stuck) window.scrollTo(0, top);
}

function reloadLibrary(opts = {}) {
  if (!L.started) return;
  const keep = !!opts.keep;
  L.gen += 1;
  if (L.ctrl) L.ctrl.abort();
  const limit = keep ? Math.min(Math.max(PAGE, L.items.length), 600) : PAGE;
  L.done = false;
  L.error = null;
  if (!keep) {
    L.items = [];
    L.total = null;
    scrollLibraryToTop();
    E.grid.replaceChildren(...skeletonTiles(12));
    E.libStatus.replaceChildren();
    updateLibCount();
  }
  fetchPage(0, limit, keep);
}

function loadMore() {
  if (!L.started || L.loading || L.done || L.error || S.tab !== 'library' || layers.length) return;
  fetchPage(L.items.length, PAGE, false);
}

function checkSentinel() {
  requestAnimationFrame(() => {
    if (S.tab !== 'library' || !L.started) return;
    if (E.sentinel.getBoundingClientRect().top < window.innerHeight + 900) loadMore();
  });
}

async function fetchPage(offset, limit, keep) {
  const gen = L.gen;
  const ctrl = new AbortController();
  L.ctrl = ctrl;
  L.loading = true;
  if (offset > 0) {
    L.skel = skeletonTiles(6);
    E.grid.append(...L.skel);
  }
  const qs = new URLSearchParams({ query: L.query, system: L.system, sort: L.sort, offset: String(offset), limit: String(limit) });
  try {
    const r = await api(`/api/games?${qs}`, { signal: ctrl.signal });
    if (gen !== L.gen) return;
    const items = (Array.isArray(r && r.items) ? r.items : []).filter((g) => g && g.id != null);
    L.total = r && Number.isFinite(Number(r.total)) ? Number(r.total) : null;
    removeSkel();
    if (offset === 0) {
      L.items = items;
      const els = items.map((g) => tileFor(g));
      E.grid.replaceChildren(...els);
      // A fresh list rises in, its first tiles a beat apart; a quiet refresh just shows.
      if (!keep) reveal(els.slice(0, 24));
    } else {
      L.items = L.items.concat(items);
      E.grid.append(...items.map((g) => tileFor(g)));
    }
    // Trust `total` when the server sends it: a server may cap `limit` and return fewer items.
    L.done = L.total != null ? L.items.length >= L.total || items.length === 0 : items.length < limit;
    L.error = null;
  } catch (e) {
    if ((e && e.name === 'AbortError') || gen !== L.gen) return;
    removeSkel();
    if (e.status === 401) return;
    if (offset === 0 && !keep) E.grid.replaceChildren();
    if (keep) {
      L.error = null;
    } else {
      L.error = e;
    }
  } finally {
    if (gen === L.gen) {
      L.loading = false;
      L.ctrl = null;
      updateLibCount();
      updateLibStatus();
      checkSentinel();
    }
  }
}

function removeSkel() {
  for (const s of L.skel) s.remove();
  L.skel = [];
}

function tileFor(g) {
  const sig = JSON.stringify([g.title, g.cover, g.system, g.systemName, g.year, g.favorite, L.system === '', S.systems.length]);
  const hit = L.cache.get(g.id);
  if (hit && hit.sig === sig) {
    hit.el._game = g;
    return hit.el;
  }
  const el = tile(g, L.system === '');
  L.cache.set(g.id, { sig, el });
  if (L.cache.size > 1500) L.cache.delete(L.cache.keys().next().value);
  return el;
}

function tile(g, showSystem) {
  const title = g.title || 'Untitled';
  const meta = [showSystem ? sysShort(g.system, g.systemName) : null, g.year].filter(Boolean).join(' \u00B7 ');
  const b = h('button.tile', { type: 'button', 'aria-label': [title, sysName(g.system, g.systemName), g.year, g.favorite ? 'favorite' : null].filter(Boolean).join(', ') },
    h('span.tile-art',
      art(g.cover, { title, system: g.system, fit: true, ar: 0.72, tag: sysShort(g.system, g.systemName) }),
      g.favorite ? h('span.fav', { 'aria-hidden': 'true' }, icon('heart')) : null),
    h('span.tile-title', { 'aria-hidden': 'true' }, title),
    meta ? h('span.tile-meta', { 'aria-hidden': 'true' }, meta) : null);
  b._game = g;
  b.addEventListener('click', () => openGame(b._game.id, b._game));
  return b;
}

function updateLibCount() {
  if (L.total == null) {
    E.libCount.textContent = L.loading ? 'Loading games' : '';
    return;
  }
  const n = L.total;
  const sys = L.system ? S.sysById.get(L.system) : null;
  let text;
  // Nothing found: the empty state below says so, and what to do.
  if (n === 0) {
    E.libCount.textContent = '';
    return;
  }
  if (L.query) text = `${plural(n, 'game', 'games')} for ${quote(L.query)}`;
  else if (sys) text = `${nf.format(n)} ${sys.name} ${n === 1 ? 'game' : 'games'}`;
  else text = plural(n, 'game', 'games');
  E.libCount.textContent = text;
}

function updateLibStatus() {
  if (L.error) {
    E.libStatus.replaceChildren(errorState(L.items.length ? "Couldn't load more games" : "Couldn't load your library", L.error, () => {
      L.error = null;
      if (L.items.length) {
        E.libStatus.replaceChildren();
        loadMore();
      } else reloadLibrary();
    }));
    return;
  }
  if (!L.loading && L.items.length === 0 && L.total != null) {
    const sys = L.system ? S.sysById.get(L.system) : null;
    let node;
    if (L.query) {
      node = emptyState('search-x', `No games match ${quote(L.query)}`,
        sys ? `Nothing on ${sys.name} matches. Try another name or system.` : 'Try a shorter or different name.',
        btn('Clear search', { icon: 'x', onClick: () => {
          E.libQ.value = '';
          E.libQClear.hidden = true;
          L.query = '';
          reloadLibrary();
        } }));
    } else if (sys) {
      node = emptyState('library', `No ${sys.name} games yet`, `Games you add on ${deviceName()} show up here.`);
    } else {
      node = emptyState('library', 'Your library is empty', `Add games on ${deviceName()} and they show up here.`);
    }
    E.libStatus.replaceChildren(node);
    return;
  }
  E.libStatus.replaceChildren();
}

let searchTimer = 0;
function onSearchInput() {
  E.libQClear.hidden = !E.libQ.value;
  clearTimeout(searchTimer);
  searchTimer = setTimeout(applySearch, 280);
}

function applySearch() {
  clearTimeout(searchTimer);
  const q = E.libQ.value.trim();
  if (q === L.query) return;
  L.query = q;
  reloadLibrary();
}

// ---- Game page

function openGame(id, summary) {
  if (id == null || id === '') return;
  if (G && G.id === id) return;
  const back = h('button.icon-btn', { type: 'button', 'aria-label': 'Back' }, icon('chevron-left'));
  const barTitle = h('p.sheet-bar-title', { 'aria-hidden': 'true' });
  const bar = h('div.sheet-bar', back, barTitle);
  const hero = h('div.hero');
  const glow = h('div.hero-glow', { 'aria-hidden': 'true' });
  const cover = h('div.game-cover');
  const headline = h('div.game-headline');
  const head = h('div.game-head', cover, headline);
  const body = h('div.game-body');
  const el = h('div.sheet', { role: 'dialog', 'aria-modal': 'true', 'aria-label': (summary && summary.title) || 'Game' },
    bar, hero, glow, h('div.sheet-inner', head, body));
  const g = { id, data: null, el, bar, barTitle, hero, glow, cover, headline, body, gen: 0, editing: false, pending: false };
  const layer = {
    el,
    focus: back,
    dismiss() {
      if (G === g) G = null;
      el.classList.add('is-leaving');
      removeLater(el);
    },
  };
  g.layer = layer;
  back.addEventListener('click', () => closeLayer(layer));
  el.addEventListener('scroll', () => {
    bar.classList.toggle('is-solid', el.scrollTop > hero.offsetHeight - 110);
  }, { passive: true });
  G = g;
  E.layers.append(el);
  pushLayer(layer);
  renderGameHead(summary || { id, title: '' });
  body.replaceChildren(gameSkeleton());
  loadGame();
}

async function loadGame(opts = {}) {
  const g = G;
  if (!g) return;
  if (opts.quiet && g.editing) {
    g.pending = true;
    return;
  }
  g.gen += 1;
  const gen = g.gen;
  try {
    const d = await api(gamePath(g.id));
    if (G !== g || gen !== g.gen) return;
    if (!d || typeof d !== 'object') throw new ApiError('The device sent an empty answer.', 200);
    if (d.id == null) d.id = g.id;
    if (opts.quiet && g.editing) {
      g.pending = true;
      return;
    }
    g.data = d;
    renderGame();
  } catch (e) {
    if (G !== g || gen !== g.gen || e.status === 401) return;
    if (opts.quiet && g.data) return;
    g.body.replaceChildren(h('div.g-section', h('div.card', errorState("Couldn't load this game", e, () => {
      g.body.replaceChildren(gameSkeleton());
      loadGame();
    }))));
  }
}

function gameSkeleton() {
  return h('div', { 'aria-busy': 'true', 'aria-label': 'Loading' },
    h('div.meta-chips', [72, 96, 64].map((w) => h('span.sk', { style: { width: `${w}px`, height: '30px', 'border-radius': '999px' } }))),
    h('div.stats', h('div.sk', { style: { height: '76px', 'border-radius': '18px' } }), h('div.sk', { style: { height: '76px', 'border-radius': '18px' } })),
    h('div.g-section',
      h('div.sk.sk-title', { style: { width: '30%', 'margin-bottom': '12px' } }),
      h('div.card', ['96%', '88%', '92%', '60%'].map((w) => h('div.sk.sk-line', { style: { width: w } })))));
}

function renderGameHead(d) {
  const g = G;
  if (!g) return;
  const title = d.title || '';
  const media = d.media && typeof d.media === 'object' ? d.media : {};
  const shots = Array.isArray(d.screenshots) ? d.screenshots : [];
  const heroUrl = safeUrl(d.hero) || safeUrl(media.background && media.background.url) || safeUrl(shots[0]);
  const acc = accentFor(d.system);
  keyed(g.hero, JSON.stringify([heroUrl, d.cover, title, d.system, S.systems.length]), () =>
    heroUrl
      ? art(heroUrl, { title, system: d.system, text: false, eager: true, flat: true })
      : art(d.cover, { title, system: d.system, text: false, blur: true, eager: true, flat: true, ar: 1.6 }));
  g.glow.style.setProperty('--glow', rgba(acc, 0.3));
  keyed(g.cover, JSON.stringify([d.cover, title, d.system, S.systems.length]), () => art(d.cover, { title, system: d.system, eager: true, fit: true, ar: 0.72, tag: sysShort(d.system, d.systemName) }));
  const dot = h('span.sys-dot');
  dot.style.setProperty('--sys', rgb(acc));
  const sysLine = [sysName(d.system, d.systemName), d.year].filter(Boolean).join(' \u00B7 ');
  g.headline.replaceChildren(...[
    title ? h('h2.game-title', title) : h('div.sk.sk-title', { style: { width: '80%', height: '24px' } }),
    sysLine ? h('p.game-sys', dot, h('span', sysLine)) : null].filter(Boolean));
  g.barTitle.textContent = title;
  g.el.setAttribute('aria-label', title || 'Game');
}

function renderGame() {
  const g = G;
  if (!g || !g.data) return;
  const d = g.data;
  const y = g.el.scrollTop;
  const arriving = !g.shown;
  g.shown = true;
  renderGameHead(d);
  const sections = gameSections(d);
  g.body.replaceChildren(...sections);
  g.el.scrollTop = y;
  if (arriving) reveal(sections);
  requestAnimationFrame(() => {
    const desc = g.body.querySelector('.desc.is-clamped');
    const more = g.body.querySelector('.desc-more');
    if (desc && more) more.hidden = desc.scrollHeight <= desc.clientHeight + 2;
  });
}

function metaPill(ic, label, cls) {
  return h('span', { class: 'pill' + (cls ? ' ' + cls : '') }, icon(ic), label);
}

function stat(ic, label, value) {
  return h('div.stat', h('p.stat-label', icon(ic), label), h('p.stat-value', value));
}

function kvList(pairs) {
  const rows = pairs.filter((p) => p[1] != null && p[1] !== '').map(([k, v, mono]) =>
    h('div.kv-row', h('span.kv-key', k), h('span', { class: 'kv-val' + (mono ? ' is-mono' : '') }, String(v))));
  return rows.length ? h('div.kv', rows) : null;
}

function gameSections(d) {
  const out = [];
  const players = fmtPlayers(d.players);
  const rating = fmtRating(d.rating);
  const genres = (Array.isArray(d.genres) ? d.genres : []).filter((x) => x != null && x !== '').map(String);

  const chips = [];
  if (d.favorite) chips.push(metaPill('heart', 'Favorite', 'is-fav'));
  if (genres.length) chips.push(metaPill('tag', genres[0]));
  if (players) chips.push(metaPill('users', players));
  if (rating) chips.push(metaPill('star', rating, 'is-rating'));
  if (chips.length) out.push(h('div.meta-chips', chips));

  out.push(h('div.stats',
    stat('clock', 'Play time', fmtMinutes(d.playMinutes)),
    stat('history', 'Last played', d.lastPlayedAt ? fmtAgo(d.lastPlayedAt) : 'Never')));

  // About
  const aboutKids = [];
  const description = typeof d.description === 'string' ? d.description.trim() : '';
  if (description) {
    const desc = h('p.desc.is-clamped', description);
    const more = h('button.link-btn.desc-more', { type: 'button' }, 'Read more');
    more.addEventListener('click', () => {
      const open = desc.classList.toggle('is-clamped');
      more.textContent = open ? 'Read more' : 'Show less';
    });
    aboutKids.push(h('div.about', desc, more));
  }
  const kv = kvList([
    ['Developer', d.developer],
    ['Publisher', d.publisher],
    ['Genres', genres.join(', ')],
    ['Series', d.series],
    ['Players', players],
    ['Rating', rating],
    ['Released', d.year],
  ]);
  if (kv) aboutKids.push(kv);
  out.push(h('section.g-section', sectionHead('About'),
    h('div.card', aboutKids.length ? aboutKids
      : h('p.desc-empty', 'No details yet. Identify the game or fill what is missing to add them.'))));

  // File
  const fileKv = kvList([['File name', d.fileName, true], ['Size', fmtBytes(d.sizeBytes)]]);
  if (fileKv) out.push(h('section.g-section', sectionHead('File'), h('div.card', fileKv)));

  // Fix this game
  out.push(h('section.g-section',
    sectionHead('Fix this game'),
    h('p.fix-intro', 'Wrong name or art? Fix it here. Changes are saved on the device.'),
    searchAsCard(d),
    identifyCard(d),
    artCard(d),
    fillGameBlock(d)));
  return out;
}

function searchAsCard(d) {
  const card = h('div.card');
  const g = G;
  const getSa = () => {
    const sa = d.searchAs && typeof d.searchAs === 'object' ? d.searchAs : {};
    const def = sa.default || d.title || '';
    return { current: sa.current || def, custom: !!sa.custom, def };
  };

  async function save(value, b) {
    const name = String(value || '').trim();
    setBusy(b, true);
    try {
      const r = await api(gamePath(d.id, '/search-as'), { method: 'PUT', body: { name } });
      const sa = getSa();
      d.searchAs = r && r.searchAs && typeof r.searchAs === 'object'
        ? r.searchAs
        : { current: name || sa.def, custom: !!name && name !== sa.def, default: sa.def };
      toast(name ? `Fuse will search for ${quote(getSa().current)}` : 'Search name reset to the title', 'success');
      render(false);
    } catch (e) {
      setBusy(b, false);
      if (e.status !== 401) toast(e.message, 'error');
    }
  }

  function render(editing) {
    const sa = getSa();
    const head = cardHead('text-cursor-input', ['Search as', sa.custom ? h('span.pill.pill-accent', 'Custom') : null], 'The name Fuse uses to look up art and details.');
    if (g) g.editing = editing;
    if (!editing) {
      const box = h('button.value-box', { type: 'button', 'aria-label': `Search as ${sa.current}${sa.custom ? ', custom' : ', the title'}. Edit` },
        h('span.value-box-text', sa.current),
        h('span.icon-btn', { 'aria-hidden': 'true' }, icon('pencil')));
      box.addEventListener('click', () => render(true));
      let hint = null;
      if (sa.custom) {
        const reset = h('button.link-btn', { type: 'button' }, icon('rotate-ccw'), 'Use the title');
        reset.addEventListener('click', () => save('', reset));
        hint = h('div.value-hint', h('span', `The title is ${quote(sa.def)}.`), reset);
      }
      card.replaceChildren(...[head, box, hint].filter(Boolean));
      if (g && g.pending) {
        g.pending = false;
        setTimeout(() => loadGame({ quiet: true }), 0);
      }
      return;
    }
    const input = h('input', { type: 'text', 'aria-label': 'Search as', autocomplete: 'off', spellcheck: 'false', enterkeyhint: 'done', maxlength: '200', placeholder: sa.def });
    input.value = sa.current;
    const saveBtn = btn('Save', { kind: 'primary', size: 'sm', icon: 'check', onClick: (b) => save(input.value, b) });
    const cancelBtn = btn('Cancel', { kind: 'ghost', size: 'sm', onClick: () => render(false) });
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        save(input.value, saveBtn);
      } else if (e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        render(false);
      }
    });
    card.replaceChildren(head,
      h('div.edit-row', h('div.input', input)),
      h('p.value-hint', 'Leave it empty to go back to the title.'),
      h('div.edit-actions', h('span.spacer'), cancelBtn, saveBtn));
    input.focus();
    input.select();
  }

  render(false);
  return card;
}

function identifyCard(d) {
  return h('div.card',
    cardHead('scan-search', 'Identify game', 'Pick the right match to fix the name, details and art in one go.'),
    h('div.card-foot', btn('Find matches', { icon: 'search', block: true, onClick: () => openIdentify(d) })));
}

function artCard(d) {
  const media = d.media && typeof d.media === 'object' ? d.media : {};
  const shots = (Array.isArray(d.screenshots) ? d.screenshots : []).filter((u) => safeUrl(u));
  const rows = ART_KINDS.map((k) => {
    let url = null;
    let caption;
    let missing = false;
    if (k.kind === 'screenshot') {
      url = shots[0] || null;
      missing = !shots.length;
      caption = missing ? 'None yet' : plural(shots.length, 'screenshot', 'screenshots');
    } else {
      const m = media[k.kind];
      url = m && safeUrl(m.url) ? m.url : null;
      missing = !url;
      caption = missing ? 'Missing' : m.source ? `From ${m.source}` : 'Set';
    }
    let thumb;
    if (missing) {
      thumb = h('span', { class: `art art-empty k-${k.kind}` }, icon('image'));
    } else {
      thumb = art(url, { title: d.title, system: d.system, contain: k.contain, text: k.kind === 'square' || k.kind === 'icon' || k.kind === 'cover', fit: k.kind === 'cover', ar: k.ar });
      thumb.classList.add(`k-${k.kind}`);
      if (k.contain) thumb.classList.add('logo-bg');
      if (k.kind === 'screenshot' && shots.length > 1) thumb.append(h('span.art-count', `+${shots.length - 1}`));
    }
    const row = h('button.art-row', { type: 'button', 'aria-label': `${k.label}: ${caption}. Choose different art.` },
      h('span.art-thumb', thumb),
      h('span.row-main',
        h('span.row-title', k.label),
        h('span', { class: 'row-sub' + (missing ? ' src-missing' : '') }, caption)),
      h('span.row-aside', icon('chevron-right')));
    row.addEventListener('click', () => openArtPicker(d, k));
    return row;
  });
  return h('div.card',
    cardHead('image', 'Art', 'Tap a slot to pick different art.'),
    h('div.art-list', rows));
}

function fillGameBlock(d) {
  const b = btn('Fill missing art and details', { icon: 'wand-sparkles', block: true, kind: 'secondary', onClick: async (el) => {
    setBusy(el, true);
    try {
      await api(gamePath(d.id, '/fill'), { method: 'POST' });
      toast('Filling what is missing. This page updates when it is done.', 'success');
    } catch (e) {
      if (e.status !== 401) toast(e.message, 'error');
    } finally {
      setBusy(el, false);
    }
  } });
  return h('div.fill-game', b, h('p.fill-game-note', 'Looks up anything this game is still missing, without touching what it has.'));
}

// Candidate rows are shared by Identify and by the art picker when it needs a match first.
function candidateRow(c, systemId, onPick) {
  const conf = confPct(c.confidence);
  const sub = [c.platformName, c.year].filter((v) => v != null && v !== '').join(' \u00B7 ');
  const title = c.title || 'Untitled';
  const row = h('button.cand', { type: 'button', 'aria-label': [title, sub, c.provider ? `from ${c.provider}` : null, conf != null ? `${conf} percent match` : null].filter(Boolean).join(', ') },
    art(c.preview, { title, system: systemId, fit: true, ar: 48 / 66 }),
    h('span.cand-main', { 'aria-hidden': 'true' },
      c.provider ? h('span.cand-prov.overline', c.provider) : null,
      h('span.cand-title', title),
      sub ? h('span.cand-sub', sub) : null),
    conf != null ? confMeter(conf) : null);
  row.addEventListener('click', () => onPick(row));
  return row;
}

/** How sure the source is: the value over a short meter (Trailing.Level). */
function confMeter(conf) {
  const fill = h('i');
  fill.style.setProperty('width', `${conf}%`);
  return h('span', { class: 'conf ' + (conf >= 80 ? 'is-high' : conf >= 50 ? 'is-mid' : 'is-low'), 'aria-hidden': 'true' },
    h('span.conf-val', `${conf}%`),
    h('span.conf-meter', fill),
    h('span.conf-label', 'match'));
}

function candSkeleton() {
  const items = [];
  for (let i = 0; i < 4; i += 1) {
    items.push(h('div.cand-skel', { 'aria-hidden': 'true' },
      h('div.sk.sk-art'),
      h('div.sk-lines', h('div.sk.sk-line', { style: { width: `${76 - i * 9}%` } }), h('div.sk.sk-line', { style: { width: '48%' } }))));
  }
  return h('div.cands', { 'aria-busy': 'true', 'aria-label': 'Looking for matches' }, items);
}

function markRowBusy(row, list, busy) {
  row.classList.toggle('is-busy', busy);
  list.classList.toggle('is-locked', busy);
  const sp = row.querySelector(':scope > .spinner');
  if (busy && !sp) row.append(spinner());
  if (!busy && sp) sp.remove();
}

function openIdentify(d) {
  const body = h('div');
  const layer = openModal({
    title: 'Identify game',
    subtitle: 'Pick the match that fits. Fuse renames the game and fills in its details and art.',
    body,
  });
  let busy = false;
  const load = async () => {
    body.replaceChildren(candSkeleton());
    try {
      const r = await api(gamePath(d.id, '/identify'));
      if (!layer.open) return;
      const cands = (Array.isArray(r && r.candidates) ? r.candidates : []).filter((c) => c && typeof c === 'object');
      const q = r && typeof r.query === 'string' ? r.query : '';
      if (!cands.length) {
        body.replaceChildren(emptyState('scan-search', 'No matches found',
          q ? `Nothing came up for ${quote(q)}. Try a different Search as name, then look again.` : 'Try a different Search as name, then look again.',
          btn('Look again', { icon: 'refresh-cw', onClick: load })));
        return;
      }
      const list = h('div.cands');
      for (const c of cands) {
        list.append(candidateRow(c, d.system, async (row) => {
          if (busy) return;
          const ok = await confirmDialog({
            title: 'Use this match?',
            text: `Fuse will name this game ${quote(c.title || 'Untitled')} and fill in its details and art${c.provider ? ` from ${c.provider}` : ''}.`,
            confirm: 'Use match',
          });
          if (!ok || !layer.open || busy) return;
          busy = true;
          markRowBusy(row, list, true);
          try {
            await api(gamePath(d.id, '/identify'), { method: 'POST', body: c });
            toast('Game identified. Details and art are on the way.', 'success');
            await closeLayer(layer);
            if (G && G.id === d.id) loadGame({ quiet: true });
          } catch (e) {
            busy = false;
            markRowBusy(row, list, false);
            if (e.status !== 401) toast(e.message, 'error');
          }
        }));
      }
      body.replaceChildren(...[q ? h('p.cand-query', 'Results for ', h('b', quote(q))) : null, list].filter(Boolean));
      reveal(list.children);
    } catch (e) {
      if (e.status === 401 || !layer.open) return;
      body.replaceChildren(errorState("Couldn't look for matches", e, load));
    }
  };
  load();
}

function optSkeleton(k) {
  const grid = h('div.opt-grid', { 'aria-busy': 'true', 'aria-label': 'Loading art' });
  grid.style.setProperty('--opt-min', k.min);
  grid.style.setProperty('--opt-ar', k.ar);
  const n = k.kind === 'icon' ? 8 : k.kind === 'cover' ? 6 : 4;
  for (let i = 0; i < n; i += 1) grid.append(h('div', { 'aria-hidden': 'true' }, h('div.sk.sk-art'), h('div.sk.sk-line')));
  return grid;
}

function openArtPicker(d, k) {
  const body = h('div');
  const layer = openModal({
    title: k.kind === 'screenshot' ? 'Add a screenshot' : `Choose ${k.article ? `${k.article} ` : ''}${k.noun}`,
    subtitle: d.title || '',
    body,
  });
  const media = d.media && typeof d.media === 'object' ? d.media : {};
  const currentUrl = k.kind === 'screenshot' ? null : media[k.kind] && media[k.kind].url;
  const shots = new Set(Array.isArray(d.screenshots) ? d.screenshots : []);
  let busy = false;

  const renderOptions = (opts) => {
    if (!opts.length) {
      body.replaceChildren(emptyState('image', `No ${k.artNoun || `${k.noun} art`} found`,
        'Try Identify game, or change Search as, then look again.',
        btn('Look again', { icon: 'refresh-cw', onClick: load })));
      return;
    }
    const grid = h('div', { class: 'opt-grid' + (k.contain ? ' is-logo' : '') });
    grid.style.setProperty('--opt-min', k.min);
    grid.style.setProperty('--opt-ar', k.ar);
    for (const o of opts) {
      const inUse = currentUrl ? o.url === currentUrl : k.kind === 'screenshot' && shots.has(o.url);
      const a = art(o.thumb || o.url, { title: d.title, system: d.system, contain: k.contain, text: false, alsoTry: o.thumb ? o.url : null, fit: true, ar: k.ar });
      if (k.contain) a.classList.add('logo-bg');
      if (inUse) a.append(h('span.opt-badge', { 'aria-hidden': 'true' }, icon('check'), 'In use'));
      const size = num(o.width) > 0 && num(o.height) > 0 ? `${num(o.width)} \u00D7 ${num(o.height)}` : '';
      const sub = [size, o.style].filter(Boolean).join(' \u00B7 ');
      const label = [o.provider || 'Option', size, o.style, o.author ? `by ${o.author}` : '', inUse ? 'in use' : ''].filter(Boolean).join(', ');
      const el = h('button', { class: 'opt' + (inUse ? ' is-current' : ''), type: 'button', 'aria-label': label, title: o.author ? `by ${o.author}` : null },
        a,
        h('span.opt-cap', { 'aria-hidden': 'true' }, o.provider || 'Art'),
        sub ? h('span.opt-sub', { 'aria-hidden': 'true' }, sub) : null);
      el.addEventListener('click', () => choose(o, el, a, grid));
      grid.append(el);
    }
    body.replaceChildren(grid);
    reveal(grid.children);
  };

  const choose = async (o, el, a, grid) => {
    if (busy) return;
    busy = true;
    el.classList.add('is-busy');
    grid.classList.add('is-locked');
    const ov = h('span.opt-busy', spinner('spinner-lg'));
    a.append(ov);
    try {
      await api(gamePath(d.id, `/art/${k.kind}`), { method: 'POST', body: o });
      toast(k.kind === 'screenshot' ? 'Screenshot added' : `${k.label} updated`, 'success');
      await closeLayer(layer);
      if (G && G.id === d.id) loadGame({ quiet: true });
    } catch (e) {
      busy = false;
      el.classList.remove('is-busy');
      grid.classList.remove('is-locked');
      ov.remove();
      if (e.status !== 401) toast(e.message, 'error');
    }
  };

  const renderMatch = (cands) => {
    const note = h('div.alert.alert-info.picker-note', icon('info'),
      h('div.alert-body', 'Fuse needs to know which game this is before it can find art. Pick the right match; this also fixes its name and details.'));
    if (!cands.length) {
      body.replaceChildren(note, emptyState('scan-search', 'No matches found', 'Try a different Search as name, then look again.', null, { compact: true }));
      return;
    }
    const list = h('div.cands');
    for (const c of cands) {
      list.append(candidateRow(c, d.system, async (row) => {
        if (busy) return;
        busy = true;
        markRowBusy(row, list, true);
        try {
          await api(gamePath(d.id, '/identify'), { method: 'POST', body: c });
          busy = false;
          if (!layer.open) return;
          toast('Game matched. Looking for art.', 'success');
          if (G && G.id === d.id) loadGame({ quiet: true });
          load();
        } catch (e) {
          busy = false;
          markRowBusy(row, list, false);
          if (e.status !== 401) toast(e.message, 'error');
        }
      }));
    }
    body.replaceChildren(note, list);
  };

  const load = async () => {
    body.replaceChildren(optSkeleton(k));
    try {
      const r = await api(gamePath(d.id, `/art/${k.kind}`));
      if (!layer.open) return;
      if (r && Array.isArray(r.needsMatch)) {
        renderMatch(r.needsMatch.filter((c) => c && typeof c === 'object'));
        return;
      }
      renderOptions((Array.isArray(r && r.options) ? r.options : []).filter((o) => o && typeof o === 'object' && (o.url || o.thumb)));
    } catch (e) {
      if (e.status === 401 || !layer.open) return;
      body.replaceChildren(errorState(`Couldn't load ${k.artNoun || `${k.noun} art`}`, e, load));
    }
  };
  load();
}

// ---- Tools

function buildTools() {
  const sysSelect = h('button.select', { type: 'button', 'aria-haspopup': 'dialog' });
  sysSelect.addEventListener('click', pickToolsSystem);
  const fillMissing = btn('Fill missing art', { icon: 'wand-sparkles', kind: 'primary', onClick: (b) => startFill('missing', b) });
  const fillAll = btn('Fill everything', { icon: 'sparkles', kind: 'secondary', onClick: (b) => startFill('everything', b) });
  const hint = h('p.tools-hint');
  const fillCard = createFillCard();
  fillCard.el.classList.add('tools-fill');
  fillCard.el.hidden = true;
  const sysArt = btn('Download system art', { icon: 'download', block: true, onClick: downloadSystemArt });
  const signOutBtn = btn('Sign out', { icon: 'log-out', kind: 'danger', block: true, onClick: signOut });
  const devTitle = h('p.card-title');
  const devText = h('p.card-text');

  E.toolsBody.replaceChildren(
    h('section.section',
      sectionHead('Art and details'),
      h('div.card',
        cardHead('wand-sparkles', 'Fill your library', 'Find covers, logos, screenshots and details. It runs on the device, so you can close this page.'),
        sysSelect,
        h('div.tools-actions', fillMissing, fillAll),
        hint),
      fillCard.el),
    h('section.section',
      sectionHead('Systems'),
      h('div.card',
        cardHead('layers', 'System art', 'Download logos and artwork for the systems in your library.'),
        h('div.card-foot', sysArt))),
    h('section.section',
      sectionHead('This phone'),
      h('div.card.device-card',
        h('div.card-head',
          h('div.card-icon.is-muted', icon('gamepad-2')),
          h('div.card-head-main', devTitle, devText)),
        h('div.card-foot', signOutBtn))),
    h('div.note', icon('shield-check'),
      h('p', 'Deleting games or captures, managing API keys and changing Phone Link settings can only be done on the device.')));

  tools = { sysSelect, fillMissing, fillAll, hint, fillCard, devTitle, devText };
  updateTools();
  updateFillViews();
}

function updateTools() {
  if (!tools) return;
  if (S.toolsSystem && S.systems.length && !S.sysById.has(S.toolsSystem)) S.toolsSystem = '';
  const sys = S.toolsSystem ? S.sysById.get(S.toolsSystem) : null;
  const key = JSON.stringify([S.toolsSystem, sys && sys.logo, sys && sys.name, S.systems.length]);
  keyed(tools.sysSelect, key, () => [
    sysLogoBox(sys),
    h('span.select-main',
      h('span.select-label', 'Systems'),
      h('span.select-value', sys ? sys.name : 'All systems')),
    icon('chevron-down'),
  ]);
  tools.sysSelect.setAttribute('aria-label', `Systems: ${sys ? sys.name : 'All systems'}. Change`);
  const running = !!(S.fill && !S.fill.finished);
  for (const b of [tools.fillMissing, tools.fillAll]) {
    if (!b.classList.contains('is-busy')) b.disabled = running;
  }
  tools.hint.textContent = running
    ? 'A fill is running. Cancel it to start a different one.'
    : 'Fill missing art only looks at games that are missing something. Fill everything looks up every game again and takes longer.';
  const dev = S.session.device;
  tools.devTitle.textContent = dev ? `Signed in to ${dev}` : 'Signed in';
  tools.devText.textContent = `${S.session.version ? `Fuse ${S.session.version}. ` : ''}This phone stays signed in until you sign out here or on the device.`;
}

function pickToolsSystem() {
  const total = S.systems.reduce((a, s) => a + num(s.gameCount), 0);
  const items = [{ id: '', title: 'All systems', sub: total ? plural(total, 'game', 'games') : '', lead: sysLogoBox(null), checked: !S.toolsSystem }]
    .concat(S.systems.map((s) => ({
      id: s.id,
      title: s.name || String(s.id),
      sub: s.gameCount != null ? plural(num(s.gameCount), 'game', 'games') : '',
      lead: sysLogoBox(s),
      checked: S.toolsSystem === s.id,
    })));
  openMenu({
    title: 'Choose systems',
    subtitle: 'Fill art for the whole library, or for one system.',
    items,
    onPick: (id) => {
      S.toolsSystem = id;
      updateTools();
    },
  });
}

async function startFill(mode, b) {
  const sys = S.toolsSystem ? S.sysById.get(S.toolsSystem) : null;
  if (mode === 'everything') {
    const count = sys ? num(sys.gameCount) : S.systems.reduce((a, s) => a + num(s.gameCount), 0);
    const scope = sys ? `every ${sys.name} game` : 'every game in your library';
    const ok = await confirmDialog({
      title: 'Fill everything?',
      text: `Fuse will look up art and details again for ${scope}${count ? ` (${plural(count, 'game', 'games')})` : ''}. This can take a while.`,
      confirm: 'Fill everything',
    });
    if (!ok || !S.signedIn) return;
  }
  setBusy(b, true);
  try {
    await api('/api/fill', { method: 'POST', body: { mode, system: S.toolsSystem || null } });
    if (!S.fill || S.fill.finished) {
      S.fill = { done: 0, total: 0, current: null, added: 0, finished: false, pending: true };
      S.fillPendingUntil = Date.now() + 5000;
      S.fillDismissed = false;
      updateFillViews();
    }
    toast(mode === 'missing' ? 'Filling missing art' : 'Filling everything', 'success');
  } catch (e) {
    if (e.status !== 401) toast(e.message, 'error');
  } finally {
    setBusy(b, false);
    updateTools();
  }
}

async function downloadSystemArt(b) {
  setBusy(b, true);
  try {
    await api('/api/system-art', { method: 'POST' });
    toast(`Downloading system art on ${deviceName()}`, 'success');
  } catch (e) {
    if (e.status !== 401) toast(e.message, 'error');
  } finally {
    setBusy(b, false);
  }
}

// ---- Start

// ---- Captures: the device's screenshots and recordings, to look at and download (never to delete)

const CAP_FILTERS = [
  { id: 'all', label: 'All' },
  { id: 'pictures', label: 'Screenshots' },
  { id: 'videos', label: 'Recordings' },
];

const C = {
  started: false,
  loaded: false,
  loading: false,
  error: null,
  gen: 0,
  items: [],
  filter: 'all',
  selecting: false,
  selected: new Set(),
  tiles: new Map(),
};
let V = null;
let capTimer = 0;

function capturesOn() {
  return !!(S.session && S.session.captures);
}

function ensureCaptures() {
  if (C.started) {
    if (C.loaded && !C.loading) refreshCaptures(true);
    return;
  }
  C.started = true;
  renderCapFilters();
  E.capTotal.textContent = 'Loading captures';
  E.capBody.replaceChildren(capSkeleton());
  refreshCaptures(false);
}

function resetCaptures() {
  C.gen += 1;
  Object.assign(C, { started: false, loaded: false, loading: false, error: null, items: [], filter: 'all', selecting: false });
  C.selected.clear();
  C.tiles.clear();
  clearTimeout(capTimer);
  capTimer = 0;
  V = null;
  if (!E.capBody) return;
  E.capBody.replaceChildren();
  E.capFilters.replaceChildren();
  E.capFilters._seg = null;
  E.capTotal.textContent = '';
  E.capSelect.hidden = true;
  setSelecting(false);
}

async function refreshCaptures(quietly) {
  C.gen += 1;
  const gen = C.gen;
  C.loading = true;
  try {
    const r = await api('/api/captures');
    if (gen !== C.gen) return;
    C.items = (Array.isArray(r && r.items) ? r.items : []).filter((c) => c && typeof c.id === 'string');
    C.loaded = true;
    C.error = null;
    // A selection keeps only what is still on the device.
    const ids = new Set(C.items.map((c) => c.id));
    for (const id of Array.from(C.selected)) if (!ids.has(id)) C.selected.delete(id);
    for (const id of Array.from(C.tiles.keys())) if (!ids.has(id)) C.tiles.delete(id);
  } catch (e) {
    if (gen !== C.gen || e.status === 401) return;
    // A background refresh that fails keeps what is shown.
    if (quietly && C.loaded) return;
    C.error = e;
  } finally {
    if (gen === C.gen) C.loading = false;
  }
  renderCaptures();
}

function onCapturesChanged() {
  if (!C.started || capTimer) return;
  capTimer = setTimeout(() => {
    capTimer = 0;
    if (S.signedIn && C.started) refreshCaptures(true);
  }, 700);
}

function shownCaptures() {
  if (C.filter === 'pictures') return C.items.filter((c) => !c.video);
  if (C.filter === 'videos') return C.items.filter((c) => c.video);
  return C.items;
}

/** All, Screenshots and Recordings as one segmented switch whose thumb glides to the choice. */
function renderCapFilters(animate = false) {
  const pictures = C.items.filter((c) => !c.video).length;
  const counts = { all: C.items.length, pictures, videos: C.items.length - pictures };
  if (!E.capFilters._seg) {
    const glide = h('span.seg-glide', { 'aria-hidden': 'true' });
    const btns = CAP_FILTERS.map((f) => {
      const count = h('span.chip-count');
      const b = h('button.seg-btn', { type: 'button', 'aria-pressed': 'false' }, h('span', f.label), count);
      b.dataset.id = f.id;
      b._count = count;
      b.addEventListener('click', () => {
        if (C.filter === f.id) return;
        C.filter = f.id;
        C.revealNext = true;
        renderCapFilters(true);
        renderCaptures();
        window.scrollTo(0, 0);
      });
      return b;
    });
    E.capFilters.replaceChildren(glide, ...btns);
    E.capFilters._seg = { glide, btns };
  }
  let current = null;
  for (const b of E.capFilters._seg.btns) {
    const on = b.dataset.id === C.filter;
    b.setAttribute('aria-pressed', String(on));
    b._count.textContent = C.loaded ? nf.format(counts[b.dataset.id]) : '';
    if (on) current = b;
  }
  placeGlide(E.capFilters._seg.glide, current, animate);
}

function renderCaptures() {
  renderCapFilters();
  // Nothing to filter or choose until there is something.
  E.capFilters.hidden = C.loaded && C.items.length === 0;
  E.capSelect.hidden = !C.loaded || C.items.length === 0;
  if (!C.loaded && C.error) {
    E.capTotal.textContent = '';
    E.capBody.replaceChildren(errorState("Couldn't load your captures", C.error, () => {
      C.error = null;
      E.capBody.replaceChildren(capSkeleton());
      refreshCaptures(false);
    }));
    return;
  }
  const list = shownCaptures();
  const bytes = list.reduce((sum, c) => sum + num(c.size), 0);
  E.capTotal.textContent = list.length ? `${plural(list.length, 'capture', 'captures')} · ${fmtBytes(bytes)}` : '';
  if (!list.length) {
    if (C.selecting && !C.items.length) setSelecting(false);
    E.capBody.replaceChildren(capEmpty());
    syncSelection();
    return;
  }
  const groups = [];
  let cur = null;
  for (const c of list) {
    const key = dayKey(c.takenAt);
    if (!cur || cur.key !== key) {
      cur = { key, at: c.takenAt, items: [] };
      groups.push(cur);
    }
    cur.items.push(c);
  }
  const arriving = C.revealNext || !E.capBody.querySelector('.cap-tile:not(.is-skeleton)');
  C.revealNext = false;
  const days = groups.map((g) => h('section.cap-day', { 'aria-label': dayLabel(g.at) },
    h('div.cap-day-head',
      h('h2.cap-day-title', dayLabel(g.at)),
      h('span.cap-day-count', plural(g.items.length, 'capture', 'captures'))),
    h('div.cap-grid', g.items.map(capTile))));
  E.capBody.replaceChildren(...days);
  // Pictures replacing their placeholders (or a new filter) rise in, day by day.
  if (arriving) reveal(days);
  syncSelection();
}

function capEmpty() {
  const dev = deviceName();
  if (C.filter === 'videos' && C.items.length) {
    return emptyState('film', 'No recordings yet', `Hold the Screenshot tile in the quick menu on ${dev}, or hold L3 and R3 together, to record.`);
  }
  if (C.filter === 'pictures' && C.items.length) {
    return emptyState('camera', 'No screenshots yet', `Use the Screenshot tile in the quick menu on ${dev}, or press L3 and R3 together.`);
  }
  return emptyState('camera', 'No screenshots or recordings yet', `Take one on ${dev} from the quick menu, or press L3 and R3 together. Hold them to record.`);
}

function capSkeleton() {
  const tiles = [];
  for (let i = 0; i < 6; i += 1) tiles.push(h('div.cap-tile.is-skeleton', { 'aria-hidden': 'true' }, h('div.sk.cap-sk')));
  return h('section.cap-day', { 'aria-hidden': 'true' },
    h('div.cap-day-head', h('div.sk.sk-line', { style: { width: '96px', height: '14px' } })),
    h('div.cap-grid', tiles));
}

function dayKey(t) {
  const d = new Date(num(t));
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
}

function dayLabel(t) {
  const d = new Date(num(t));
  const today = new Date(nowMs());
  const yesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1);
  if (dayKey(d.getTime()) === dayKey(today.getTime())) return 'Today';
  if (dayKey(d.getTime()) === dayKey(yesterday.getTime())) return 'Yesterday';
  const sameYear = d.getFullYear() === today.getFullYear();
  return d.toLocaleDateString('en', { weekday: 'long', month: 'long', day: 'numeric', year: sameYear ? undefined : 'numeric' });
}

function timeLabel(t) {
  return new Date(num(t)).toLocaleTimeString('en', { hour: 'numeric', minute: '2-digit' });
}

function capKind(c) {
  return c.video ? 'Recording' : 'Screenshot';
}

function capTile(c) {
  const hit = C.tiles.get(c.id);
  if (hit) return hit;
  const length = c.video && num(c.durationMs) > 0 ? fmtClock(num(c.durationMs) / 1000) : '';
  const b = h('button.cap-tile', { type: 'button', 'aria-label': [capKind(c), timeLabel(c.takenAt), length].filter(Boolean).join(', ') },
    h('span.cap-thumb',
      art(c.thumb, { title: c.name, text: false, flat: true }),
      c.video ? h('span.cap-badge', { 'aria-hidden': 'true' }, icon('play'), length ? h('span', length) : null) : null,
      h('span.cap-check', { 'aria-hidden': 'true' }, icon('check'))),
    h('span.cap-time', { 'aria-hidden': 'true' }, timeLabel(c.takenAt)));
  holdToSelect(b, c.id);
  b.addEventListener('click', () => {
    if (b._held) {
      b._held = false;
      return;
    }
    if (C.selecting) toggleCapture(c.id);
    else openCapture(c.id);
  });
  C.tiles.set(c.id, b);
  return b;
}

/** A long press on a capture starts choosing several, with that one chosen. */
function holdToSelect(el, id) {
  let timer = 0;
  let start = null;
  const cancel = () => {
    clearTimeout(timer);
    timer = 0;
  };
  el.addEventListener('pointerdown', (e) => {
    if (e.pointerType === 'mouse' || C.selecting) return;
    start = { x: e.clientX, y: e.clientY };
    cancel();
    timer = setTimeout(() => {
      timer = 0;
      el._held = true;
      setSelecting(true);
      C.selected.add(id);
      syncSelection();
      if (navigator.vibrate) quiet(() => navigator.vibrate(12));
    }, 480);
  });
  el.addEventListener('pointermove', (e) => {
    if (timer && start && Math.hypot(e.clientX - start.x, e.clientY - start.y) > 10) cancel();
  });
  el.addEventListener('pointerup', cancel);
  el.addEventListener('pointercancel', cancel);
  el.addEventListener('contextmenu', (e) => {
    if (el._held) e.preventDefault();
  });
}

function setSelecting(on) {
  C.selecting = on;
  if (!on) C.selected.clear();
  E.capSelect.setAttribute('aria-pressed', String(on));
  E.capSelectLabel.textContent = on ? 'Cancel' : 'Select';
  E.capSelect.querySelector('use').setAttribute('href', on ? '#i-x' : '#i-square-check-big');
  E.capTab.classList.toggle('is-selecting', on);
  document.body.classList.toggle('is-selecting', on);
  syncSelection();
}

function toggleCapture(id) {
  if (C.selected.has(id)) C.selected.delete(id);
  else C.selected.add(id);
  syncSelection();
}

function syncSelection() {
  for (const [id, el] of C.tiles) {
    const on = C.selected.has(id);
    el.classList.toggle('is-selected', on);
    if (C.selecting) el.setAttribute('aria-pressed', String(on));
    else el.removeAttribute('aria-pressed');
  }
  E.capActions.hidden = !C.selecting;
  if (!C.selecting) return;
  const chosen = C.items.filter((c) => C.selected.has(c.id));
  const bytes = chosen.reduce((sum, c) => sum + num(c.size), 0);
  E.capCount.textContent = chosen.length ? `${nf.format(chosen.length)} selected · ${fmtBytes(bytes)}` : 'Choose captures to download';
  const shown = shownCaptures();
  E.capAll.textContent = shown.length && shown.every((c) => C.selected.has(c.id)) ? 'Clear' : 'Select all';
  E.capAll.hidden = !shown.length;
  E.capDownload.disabled = !chosen.length;
  E.capDownload.querySelector('.btn-label').textContent = chosen.length > 1 ? 'Download zip' : 'Download';
}

/** Asks the device for a download link, then lets the browser save the file (never held in memory). */
async function downloadCaptures(ids, b) {
  if (!ids.length) return;
  setBusy(b, true);
  try {
    const r = await api('/api/captures/download', { method: 'POST', body: { ids } });
    const url = safeUrl(r && r.url);
    if (!url || new URL(url).origin !== location.origin) throw new ApiError('Something went wrong. Try again.', 500);
    const a = h('a', { href: url, download: (r && typeof r.name === 'string' && r.name) || '', hidden: true });
    document.body.append(a);
    a.click();
    setTimeout(() => a.remove(), 1000);
    const size = r && num(r.size) > 0 ? ` (${fmtBytes(r.size)})` : '';
    toast(ids.length === 1 ? `Downloading${size}` : `Downloading ${nf.format(ids.length)} captures as a zip${size}`, 'success');
    if (C.selecting) setSelecting(false);
  } catch (e) {
    if (e.status !== 401) toast(e.message, 'error');
  } finally {
    setBusy(b, false);
  }
}

// The viewer: one capture at a time, full screen, with the ones around it a swipe away.

function openCapture(id) {
  const list = shownCaptures();
  const index = list.findIndex((c) => c.id === id);
  if (index < 0 || V) return;
  const back = h('button.icon-btn', { type: 'button', 'aria-label': 'Back' }, icon('chevron-left'));
  const title = h('p.viewer-title');
  const sub = h('p.viewer-sub');
  const download = btn('Download', { icon: 'download', kind: 'primary', size: 'sm' });
  const stage = h('div.viewer-stage');
  const prev = h('button.icon-btn.viewer-nav.is-prev', { type: 'button', 'aria-label': 'Previous' }, icon('chevron-left'));
  const next = h('button.icon-btn.viewer-nav.is-next', { type: 'button', 'aria-label': 'Next' }, icon('chevron-right'));
  const facts = h('div.viewer-facts');
  const el = h('div.viewer', { role: 'dialog', 'aria-modal': 'true', 'aria-label': 'Capture' },
    h('div.viewer-bar', back, h('div.viewer-head', title, sub), download),
    h('div.viewer-main', stage, prev, next),
    facts);
  const v = { list, index, el, stage, title, sub, facts, prev, next, download };
  const layer = {
    el,
    focus: back,
    dismiss() {
      const media = stage.querySelector('video');
      if (media) media.pause();
      if (V === v) V = null;
      el.classList.add('is-leaving');
      removeLater(el);
    },
  };
  back.addEventListener('click', () => closeLayer(layer));
  prev.addEventListener('click', () => stepCapture(-1));
  next.addEventListener('click', () => stepCapture(1));
  download.addEventListener('click', () => downloadCaptures([v.list[v.index].id], download));
  swipe(stage, (dir) => stepCapture(dir), (dir) => V === v && v.index + dir >= 0 && v.index + dir < v.list.length);
  V = v;
  E.layers.append(el);
  pushLayer(layer);
  showCapture();
}

function stepCapture(dir) {
  if (!V) return;
  const to = V.index + dir;
  if (to < 0 || to >= V.list.length) return;
  V.index = to;
  showCapture(dir);
}

function showCapture(dir = 0) {
  const v = V;
  const c = v.list[v.index];
  v.title.textContent = `${dayLabel(c.takenAt)}, ${timeLabel(c.takenAt)}`;
  v.sub.textContent = c.name;
  const old = v.stage.querySelector('video');
  if (old) old.pause();
  const src = safeUrl(c.url);
  let media;
  if (c.video) {
    media = h('video.viewer-media', { controls: true, playsinline: true, preload: 'metadata', poster: safeUrl(c.thumb) || null });
    if (src) media.src = src;
  } else {
    media = h('img.viewer-media', { alt: c.name, decoding: 'async', draggable: 'false' });
    media.referrerPolicy = 'no-referrer';
    const frame = h('div.viewer-frame.is-loading');
    const thumb = safeUrl(c.thumb);
    if (thumb) frame.style.setProperty('background-image', `url("${thumb}")`);
    media.addEventListener('load', () => frame.classList.remove('is-loading'), { once: true });
    media.addEventListener('error', () => frame.replaceChildren(emptyState('circle-alert', "Couldn't show this screenshot", 'It may have been removed on the device.', null, { compact: true, danger: true })), { once: true });
    if (src) media.src = src;
    frame.append(media, spinner('viewer-spin'));
    media = frame;
  }
  if (dir) media.classList.add(dir > 0 ? 'from-right' : 'from-left');
  v.stage.replaceChildren(media);
  const res = num(c.width) > 0 && num(c.height) > 0 ? `${num(c.width)} × ${num(c.height)}` : null;
  const length = c.video && num(c.durationMs) > 0 ? fmtClock(num(c.durationMs) / 1000) : null;
  v.facts.replaceChildren(...[
    h('span.pill', icon(c.video ? 'film' : 'camera'), capKind(c)),
    length ? h('span.pill', icon('clock'), length) : null,
    res ? h('span.pill', res) : null,
    h('span.pill', fmtBytes(c.size)),
    h('span.viewer-pos', `${nf.format(v.index + 1)} of ${nf.format(v.list.length)}`),
  ].filter(Boolean));
  v.prev.disabled = v.index === 0;
  v.next.disabled = v.index === v.list.length - 1;
}

/**
 * A horizontal swipe on [el] (pictures only: a video's own controls take the touch). The picture
 * follows the finger, resists at either end, and settles back if the swipe was too short.
 */
function swipe(el, onSwipe, canGo) {
  let start = null;
  let moved = null;
  el.addEventListener('pointerdown', (e) => {
    if (e.target.closest('video') || (e.pointerType === 'mouse' && e.button !== 0)) return;
    start = { x: e.clientX, y: e.clientY, t: Date.now(), id: e.pointerId, dragging: false };
  });
  el.addEventListener('pointermove', (e) => {
    if (!start || e.pointerId !== start.id) return;
    const dx = e.clientX - start.x;
    const dy = e.clientY - start.y;
    if (!start.dragging) {
      if (Math.abs(dx) < 8 || Math.abs(dx) < Math.abs(dy) * 1.2) return;
      start.dragging = true;
      quiet(() => el.setPointerCapture(e.pointerId));
      moved = reducedMotion.matches ? null : el.firstElementChild;
      if (moved) {
        moved.classList.remove('from-right', 'from-left', 'is-settling');
        moved.classList.add('is-dragging');
      }
    }
    if (!moved) return;
    const k = canGo(dx < 0 ? 1 : -1) ? 1 : 0.3;
    moved.style.setProperty('transform', `translateX(${Math.round(dx * k)}px)`);
  });
  const end = (e, cancelled) => {
    if (!start || e.pointerId !== start.id) return;
    const dx = e.clientX - start.x;
    const dy = e.clientY - start.y;
    const quick = Date.now() - start.t < 600;
    const far = Math.abs(dx) > Math.max(80, el.clientWidth * 0.22);
    const dir = dx < 0 ? 1 : -1;
    const go = !cancelled && Math.abs(dx) > Math.abs(dy) * 1.4 && ((quick && Math.abs(dx) > 56) || far) && canGo(dir);
    const m = moved;
    start = null;
    moved = null;
    if (m) m.classList.remove('is-dragging');
    if (go) {
      onSwipe(dir);
      return;
    }
    if (m) {
      m.classList.add('is-settling');
      m.style.setProperty('transform', 'translateX(0px)');
      setTimeout(() => {
        m.classList.remove('is-settling');
        m.style.removeProperty('transform');
      }, 320);
    }
  };
  el.addEventListener('pointerup', (e) => end(e, false));
  el.addEventListener('pointercancel', (e) => end(e, true));
}

document.addEventListener('keydown', (e) => {
  if (!V || layers[layers.length - 1]?.el !== V.el) return;
  if (e.key === 'ArrowLeft') stepCapture(-1);
  if (e.key === 'ArrowRight') stepCapture(1);
});

function init() {
  E.boot = $('#boot');
  E.bootMsg = $('#boot-msg');
  E.bootText = $('#boot-text');
  E.bootRetry = $('#boot-retry');
  E.signin = $('#signin');
  E.signinSub = $('#signin-sub');
  E.signinFoot = $('#signin-foot');
  E.form = $('#signin-form');
  E.user = $('#su-user');
  E.pass = $('#su-pass');
  E.eye = $('#su-eye');
  E.submit = $('#su-submit');
  E.submitLabel = $('#su-submit .btn-label');
  E.err = $('#signin-error');
  E.errText = $('#signin-error-text');
  E.countdown = $('#signin-countdown');
  E.note = $('#signin-note');
  E.noteText = $('#signin-note-text');
  E.shell = $('#shell');
  E.nowBody = $('#now-body');
  E.toolsBody = $('#tools-body');
  E.grid = $('#lib-grid');
  E.libStatus = $('#lib-status');
  E.libCount = $('#lib-count');
  E.libQ = $('#lib-q');
  E.libQClear = $('#lib-q-clear');
  E.libChips = $('#lib-chips');
  E.libSort = $('#lib-sort');
  E.libSortLabel = $('#lib-sort-label');
  E.libBar = $('#lib-bar');
  E.libStick = $('#lib-stick');
  E.sentinel = $('#lib-sentinel');
  E.layers = $('#layers');
  E.toasts = $('#toasts');
  E.offline = $('#offline');
  E.offlineText = $('#offline-text');
  E.capTab = $('#tab-captures');
  E.capTabBtn = $('.tab-btn[data-tab="captures"]');
  E.capFilters = $('#cap-filters');
  E.capTotal = $('#cap-total');
  E.capSelect = $('#cap-select');
  E.capSelectLabel = $('#cap-select-label');
  E.capBody = $('#cap-body');
  E.capActions = $('#cap-actions');
  E.capCount = $('#cap-count');
  E.capAll = $('#cap-all');
  E.capDownload = $('#cap-download');
  E.tabGlide = $('.tab-glide');

  E.countdown.setAttribute('aria-live', 'off');

  quiet(() => history.scrollRestoration = 'manual');
  const hash = location.hash.replace('#', '');
  if (TABS.includes(hash)) S.tab = hash;
  quiet(() => history.replaceState({ depth: 0 }, '', location.pathname + location.search + (TABS.includes(hash) ? `#${hash}` : '')));

  E.bootRetry.addEventListener('click', checkSession);
  E.form.addEventListener('submit', onSignin);
  E.eye.addEventListener('click', () => {
    const show = E.pass.type === 'password';
    E.pass.type = show ? 'text' : 'password';
    E.eye.setAttribute('aria-pressed', String(show));
    E.eye.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
    E.eye.querySelector('use').setAttribute('href', show ? '#i-eye-off' : '#i-eye');
    E.pass.focus();
  });
  for (const input of [E.user, E.pass]) input.addEventListener('input', hideSigninError);

  for (const b of $$('.tab-btn')) b.addEventListener('click', () => selectTab(b.dataset.tab));
  window.addEventListener('hashchange', () => {
    const t = location.hash.replace('#', '');
    if (S.signedIn && TABS.includes(t) && t !== S.tab) selectTab(t);
  });

  E.libQ.addEventListener('input', onSearchInput);
  E.libQ.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      applySearch();
      E.libQ.blur();
    }
  });
  E.libQClear.addEventListener('click', () => {
    E.libQ.value = '';
    E.libQClear.hidden = true;
    applySearch();
    E.libQ.focus();
  });
  E.capSelect.addEventListener('click', () => setSelecting(!C.selecting));
  E.capAll.addEventListener('click', () => {
    const shown = shownCaptures();
    const all = shown.length > 0 && shown.every((c) => C.selected.has(c.id));
    for (const c of shown) {
      if (all) C.selected.delete(c.id);
      else C.selected.add(c.id);
    }
    syncSelection();
  });
  E.capDownload.addEventListener('click', () => downloadCaptures(C.items.filter((c) => C.selected.has(c.id)).map((c) => c.id), E.capDownload));

  E.libSort.addEventListener('click', () => {
    openMenu({
      title: 'Sort by',
      items: SORTS.map((s) => ({ id: s.id, title: s.label, sub: s.sub, checked: L.sort === s.id })),
      onPick: setSort,
    });
  });

  if ('IntersectionObserver' in window) {
    new IntersectionObserver((entries) => {
      if (entries.some((en) => en.isIntersecting)) loadMore();
    }, { rootMargin: '0px 0px 900px 0px' }).observe(E.sentinel);
    new IntersectionObserver((entries) => {
      const en = entries[entries.length - 1];
      E.libBar.classList.toggle('is-stuck', S.tab === 'library' && !en.isIntersecting && en.boundingClientRect.top < 0);
    }).observe(E.libStick);
  } else {
    window.addEventListener('scroll', checkSentinel, { passive: true });
  }

  // Gliding markers are placed from layout: put them back when it changes.
  let resizeRaf = 0;
  window.addEventListener('resize', () => {
    cancelAnimationFrame(resizeRaf);
    resizeRaf = requestAnimationFrame(relayoutGlides);
  }, { passive: true });
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(relayoutGlides);

  setInterval(tick, 1000);
  checkSession();
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
else init();
