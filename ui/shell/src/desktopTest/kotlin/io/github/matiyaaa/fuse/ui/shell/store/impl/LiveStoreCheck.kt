package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.FuseHttp
import io.github.matiyaaa.fuse.integrations.FuseHttpConfig
import io.github.matiyaaa.fuse.integrations.obtainium.DesktopRelease
import io.github.matiyaaa.fuse.integrations.obtainium.PackResolver
import io.github.matiyaaa.fuse.model.Host
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every Linux entry of the Store, resolved against its real source: run by hand with
 * FUSE_LIVE_STORE=1 (optionally FUSE_LIVE_STORE_ONLY=github,forge,listing,flathub). It prints each
 * entry's version and file and fails on any that has neither, so a project that renamed its files
 * or moved is caught before people find it.
 */
class LiveStoreCheck {
    @Test
    fun everyLinuxEntryResolves() = runBlocking {
        assumeTrue("Set FUSE_LIVE_STORE=1 to check the Store's sources", System.getenv("FUSE_LIVE_STORE") == "1")
        val only = System.getenv("FUSE_LIVE_STORE_ONLY")?.split(',')?.map { it.trim().uppercase() }?.toSet()
        val http = FuseHttp.client(OkHttp.create(), FuseHttpConfig(appVersion = "live-check"))
        val resolver = PackResolver(http, githubToken = { System.getenv("GITHUB_TOKEN") })
        val failures = ArrayList<String>()
        for (e in DesktopCatalogue.entries.filter { it.hosts == null || Host.LINUX in it.hosts }) {
            val source = e.sourceFor(Host.LINUX) ?: continue
            if (only != null && source.name !in only) continue
            val pattern = e.pick?.invoke(Host.LINUX, false, null)
            val r: ApiResult<DesktopRelease> = when (source) {
                DesktopEntry.Source.GITHUB -> resolver.desktop(e.repoFor(Host.LINUX)!!, e.name, Host.LINUX, "x86_64", pattern, e.prereleases)
                DesktopEntry.Source.FORGE -> resolver.desktopForgejo(e.forge!!, e.name, Host.LINUX, "x86_64", pattern, e.prereleases)
                DesktopEntry.Source.LISTING -> e.listings.getValue(Host.LINUX).let { resolver.desktopListing(it.page, e.name, Host.LINUX, it.pattern, it.then) }
                DesktopEntry.Source.FLATHUB -> resolver.flathub(e.flathub!!, e.name)
            }
            when (r) {
                is ApiResult.Success -> {
                    val ok = r.value.file != null || source == DesktopEntry.Source.FLATHUB && r.value.version != null
                    println("${if (ok) "ok  " else "NONE"} ${e.key} [$source] ${r.value.version} ${r.value.file?.name ?: ""}")
                    if (!ok) failures += e.key
                }
                is ApiResult.Failure -> {
                    println("FAIL ${e.key} [$source] $r")
                    failures += e.key
                }
            }
        }
        http.close()
        assertTrue(failures.isEmpty(), "No download found for: $failures")
    }
}
