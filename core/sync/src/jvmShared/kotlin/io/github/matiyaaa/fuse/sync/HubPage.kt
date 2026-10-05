package io.github.matiyaaa.fuse.sync

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Fuse Sync's Hub as a web page, on the host computer only (http://127.0.0.1:47311/hub): everything
 * the host keeps. The host at a glance (how long it has run, what it keeps, the space left), every
 * device, and for each profile its play time (in all and by device) and every game: play time by
 * device, sessions and Last Played, and each save and save state with every version, which device
 * saved it and when, how big it is, why it was kept, and each file with where the host keeps it. It
 * only reads; changes are made in Fuse.
 */
internal object HubPage {
    private val time = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())

    fun render(status: HostStatus, reports: List<ProfileReport>, now: Long): String {
        val h = status.hello
        val names = status.devices.associate { it.id to it.name }
        fun deviceName(id: String) = names[id] ?: id
        val totalPlay = reports.sumOf { it.playSeconds }
        val devices = status.devices.filterNot { it.revoked }.joinToString("") { d ->
            val online = now - d.lastSeen < 2 * 60_000
            val seen = when {
                online -> if (d.connection == "REMOTE") "Online, from outside" else "Online, at home"
                d.lastSeen > 0 -> "Seen ${at(d.lastSeen)}"
                else -> "Not seen yet"
            }
            val who = status.profiles.firstOrNull { it.id == d.profile }?.name?.let { " · ${esc(it)} playing" }.orEmpty()
            val synced = if (d.lastSync > 0) " · last synced ${at(d.lastSync)}" else ""
            """<li class="row"><span class="dot${if (online) " on" else ""}"></span><div><b>${esc(d.name)}</b>""" +
                """<small>${platform(d.platform)} · $seen$who$synced</small></div></li>"""
        }.ifEmpty { """<li class="empty">No devices yet. In Fuse on this computer: Settings, Addons, Fuse Sync, Add a Device.</li>""" }
        val profiles = reports.joinToString("") { profile(it, ::deviceName, now) }
            .ifEmpty { """<section class="card"><p class="empty">No profiles yet. Make one in Fuse: Who's playing?, Add Profile.</p></section>""" }
        val up = (now - status.startedAt).coerceAtLeast(0) / 60_000
        val upText = when {
            up < 60 -> "$up min"
            up < 48 * 60 -> "${up / 60} h"
            else -> "${up / 1440} days"
        }
        val store = reports.firstOrNull()?.storePath.orEmpty()
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(h.name)} · Fuse Sync</title>
<style>$CSS</style></head><body><main>
<header><div class="mark"><svg viewBox="0 0 24 24"><path d="M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"/><path d="M16 16h5v5"/></svg></div>
<div><h1>${esc(h.name)}</h1><div class="sub">Fuse Sync by Fuse · the host for your devices</div></div>
<a class="refresh" href="">Refresh</a></header>
<section class="facts">
<div class="fact"><small>Running for</small><b>$upText</b></div>
<div class="fact"><small>Play time, everyone</small><b>${duration(totalPlay)}</b></div>
<div class="fact"><small>Kept here</small><b>${size(status.storageBytes)}</b><small>${status.objectCount} files · ${status.revisionCount} save versions</small></div>
<div class="fact"><small>Free on this drive</small><b>${if (status.freeBytes >= 0) size(status.freeBytes) else "Unknown"}</b></div>
</section>
<section class="card"><h2>Devices</h2><ul>$devices</ul></section>
$profiles
<footer>Every file is kept once, by its SHA-256, under <code>${esc(store)}</code>; each version lists the files it is made of.
This page only shows; changes are made in Fuse (Settings, Addons, Fuse Sync). It opens on this computer only.${if (h.fuseVersion.isNotEmpty()) " Fuse ${esc(h.fuseVersion)}." else ""}</footer>
</main></body></html>
"""
    }

    private fun profile(r: ProfileReport, deviceName: (String) -> String, now: Long): String {
        val p = r.profile
        val played = r.games.filter { it.playSeconds > 0 }
        val saved = r.games.count { it.slots.isNotEmpty() }
        val byDevice = bars(r.devicePlay, deviceName)
        val games = r.games.joinToString("") { game(it, deviceName) }
            .ifEmpty { """<p class="empty">Nothing played or saved yet.</p>""" }
        return """<section class="card profile">
<div class="phead"><span class="avatar">${esc(p.name.take(1).uppercase())}</span><div><h3>${esc(p.name)}</h3>
<small>${played.size} games played · $saved with saves · ${size(r.savesBytes)} of saves${if (p.protected) " · PIN" else ""}</small></div>
<div class="big"><b>${duration(r.playSeconds)}</b><small>played in all</small></div></div>
${if (byDevice.isNotEmpty()) """<h2>Play time by device</h2>$byDevice""" else ""}
<h2>Games</h2>$games
</section>"""
    }

    private fun game(g: GameReport, deviceName: (String) -> String): String {
        val kinds = g.slots.joinToString(", ") { "${it.kind.label.lowercase()}s (${it.versions.size})" }
        val summary = listOfNotNull(
            g.platform.uppercase().takeIf { it.isNotEmpty() },
            duration(g.playSeconds).takeIf { g.playSeconds > 0 },
            g.lastPlayed?.let { "last played ${at(it)}" },
            kinds.takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
        val slots = g.slots.joinToString("") { s ->
            val rows = s.versions.joinToString("") { v ->
                val files = v.files.joinToString("") { f -> """<li><code>${esc(f.path)}</code> <span>${size(f.bytes)}</span> <small>kept as <code>${esc(f.stored)}</code></small></li>""" }
                """<tr${if (v.current) " class=\"current\"" else ""}><td>${at(v.at)}${if (v.current) " <em>in use</em>" else ""}</td><td>${esc(v.device)}</td>""" +
                    """<td>${if (v.playSeconds > 0) duration(v.playSeconds) else "–"}</td><td>${size(v.bytes)}</td><td>${reason(v.reason)}</td>""" +
                    """<td><details><summary>${v.files.size} ${if (v.files.size == 1) "file" else "files"}</summary><ul class="files">$files</ul></details></td></tr>"""
            }
            """<h4>${s.kind.label}s <small>${s.versions.size} versions · ${size(s.bytes)} kept${if (s.format.isNotEmpty()) " · ${esc(s.format)}" else ""}</small></h4>
<table><thead><tr><th>Saved</th><th>On</th><th>Played by then</th><th>Size</th><th>Why kept</th><th>Files</th></tr></thead><tbody>$rows</tbody></table>"""
        }
        val play = bars(g.devicePlay, deviceName)
        return """<details class="game"><summary><b>${esc(g.name)}</b>${if (g.favorite) " <span class=\"fav\">Favourite</span>" else ""}<small>$summary</small></summary>
<div class="gbody">${if (play.isNotEmpty()) "<h4>Play time by device <small>${g.sessions} sessions</small></h4>$play" else ""}${slots.ifEmpty { "<p class=\"empty\">No saves kept for it yet.</p>" }}</div></details>"""
    }

    /** Play time by device as bars, longest first. */
    private fun bars(play: Map<String, Long>, deviceName: (String) -> String): String {
        val max = play.values.maxOrNull()?.takeIf { it > 0 } ?: return ""
        return "<ul class=\"bars\">" + play.entries.sortedByDescending { it.value }.joinToString("") { (d, sec) ->
            """<li><span>${esc(deviceName(d))}</span><i style="width:${(sec * 100 / max).coerceAtLeast(2)}%"></i><b>${duration(sec)}</b></li>"""
        } + "</ul>"
    }

    private fun reason(r: RevisionReason) = when (r) {
        RevisionReason.PLAYED -> "Played"
        RevisionReason.CONFLICT_COPY -> "The other side of a conflict"
        RevisionReason.BEFORE_RESTORE -> "Before a restore"
        RevisionReason.MILESTONE -> "Kept for good"
    }

    private fun at(ms: Long) = time.format(Instant.ofEpochMilli(ms))

    private fun duration(seconds: Long): String {
        val m = seconds / 60
        return when {
            m < 1 -> "under a minute"
            m < 60 -> "$m min"
            else -> "${m / 60} h ${m % 60} min"
        }
    }

    private fun platform(p: String) = when (p) {
        "ANDROID" -> "Android"
        "LINUX" -> "Linux"
        "WINDOWS" -> "Windows"
        "MACOS" -> "macOS"
        else -> p.lowercase().replaceFirstChar { it.uppercase() }
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
        else -> "%.1f GB".format(bytes / 1073741824.0)
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private const val CSS = """
:root{--bg:#07080b;--panel:#12141a;--raised:#191c24;--line:#232631;--text:#eef0f5;--muted:#9aa0ae;--accent:#ff6a3d;--ok:#3ccf8e}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(1200px 500px at 20% -10%,rgba(255,106,61,.16),transparent),var(--bg);color:var(--text);font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif;padding:40px 20px}
main{max-width:1080px;margin:0 auto}header{display:flex;align-items:center;gap:16px;margin-bottom:28px}
.mark{width:56px;height:56px;border-radius:16px;background:linear-gradient(135deg,rgba(255,106,61,.35),rgba(255,106,61,.1));display:grid;place-items:center;flex:none}
.mark svg{width:28px;height:28px;stroke:var(--accent);fill:none;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}
h1{margin:0;font-size:28px;letter-spacing:-.01em}h3{margin:0;font-size:20px}.sub{color:var(--muted)}
.refresh{margin-left:auto;color:var(--text);text-decoration:none;border:1px solid var(--line);background:var(--panel);padding:8px 16px;border-radius:999px}.refresh:hover{border-color:var(--accent)}
.facts{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px;margin-bottom:16px}
.fact,.card{background:var(--panel);border:1px solid var(--line);border-radius:18px;padding:16px 18px}.card{margin-bottom:16px}
.fact b{display:block;font-size:22px}small{color:var(--muted)}.fact small{display:block}
h2{margin:18px 0 10px;font-size:12px;letter-spacing:.14em;text-transform:uppercase;color:var(--muted)}.card>h2:first-child{margin-top:0}
h4{margin:16px 0 8px;font-size:14px}h4 small{font-weight:400;margin-left:6px}
ul{list-style:none;margin:0;padding:0}.row{display:flex;gap:12px;align-items:center;padding:10px 0;border-top:1px solid var(--line)}.row:first-child{border-top:0}
.row b{display:block}.dot{width:9px;height:9px;border-radius:50%;background:#4a4f5c;margin:0 12px;flex:none}.dot.on{background:var(--ok)}
.phead{display:flex;gap:14px;align-items:center}.phead small{display:block}.big{margin-left:auto;text-align:right}.big b{display:block;font-size:24px}
.avatar{width:44px;height:44px;border-radius:50%;background:linear-gradient(135deg,#ff8a65,#e0457b);display:grid;place-items:center;font-weight:700;font-size:18px;flex:none}
.bars li{display:grid;grid-template-columns:160px 1fr 110px;gap:12px;align-items:center;padding:4px 0}.bars i{display:block;height:8px;border-radius:99px;background:linear-gradient(90deg,#ffb085,var(--accent))}.bars b{text-align:right;font-weight:600}
details.game{border-top:1px solid var(--line)}details.game>summary{cursor:pointer;list-style:none;padding:12px 4px;display:flex;flex-wrap:wrap;gap:4px 10px;align-items:baseline}
details.game>summary::-webkit-details-marker{display:none}details.game>summary::before{content:"›";color:var(--muted);transition:transform .15s;display:inline-block}details.game[open]>summary::before{transform:rotate(90deg)}
details.game>summary small{flex-basis:100%;padding-left:14px}.fav{font-size:12px;color:var(--accent);border:1px solid rgba(255,106,61,.4);border-radius:99px;padding:0 8px}
.gbody{padding:0 4px 16px 18px}table{width:100%;border-collapse:collapse;font-size:14px}th{text-align:left;color:var(--muted);font-weight:500;font-size:12px;padding:6px 8px;border-bottom:1px solid var(--line)}
td{padding:8px;border-bottom:1px solid var(--line);vertical-align:top}tr.current td{background:rgba(255,106,61,.06)}em{font-style:normal;font-size:12px;color:var(--accent);margin-left:6px}
td details summary{cursor:pointer;color:var(--muted)}.files li{padding:4px 0}.files span{margin-left:6px}.files small{display:block}
code{font:12px ui-monospace,SFMono-Regular,Menlo,monospace;color:#cfd3dc;word-break:break-all}.empty{color:var(--muted);padding:8px 0;margin:0}
footer{margin-top:8px;color:var(--muted);font-size:13px}
@media (max-width:640px){.bars li{grid-template-columns:100px 1fr 80px}table{font-size:13px}th:nth-child(3),td:nth-child(3){display:none}}
"""
}
