package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.repo.AppOverrideRepository
import io.github.matiyaaa.fuse.data.settings.SecretKeys
import io.github.matiyaaa.fuse.model.AppEntry
import io.github.matiyaaa.fuse.model.AppFilter
import io.github.matiyaaa.fuse.model.BorderStyle
import io.github.matiyaaa.fuse.model.CollectionId
import io.github.matiyaaa.fuse.model.GameCollection
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.Resolved
import io.github.matiyaaa.fuse.model.ScopeRef
import io.github.matiyaaa.fuse.model.ScopedKey
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.model.SettingScope
import io.github.matiyaaa.fuse.ui.shell.store.AppCard
import io.github.matiyaaa.fuse.ui.shell.store.AppOps
import io.github.matiyaaa.fuse.ui.shell.store.CollectionOps
import io.github.matiyaaa.fuse.ui.shell.store.CredentialOps
import io.github.matiyaaa.fuse.ui.shell.store.ScopedSettingsOps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import io.github.matiyaaa.fuse.model.MediaOwner
import kotlinx.coroutines.flow.stateIn

internal class DefaultCollectionOps(private val ctx: StoreContext) : CollectionOps {
    private val repo = ctx.data.collections

    override val collections: StateFlow<List<GameCollection>> =
        repo.observeManual().resilient().stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    override suspend fun create(name: String): CollectionId = repo.create(name.trim().ifEmpty { "New collection" })

    override suspend fun rename(id: CollectionId, name: String) {
        name.trim().takeIf { it.isNotEmpty() }?.let { repo.rename(id, it) }
    }

    /** Deletes the collection only; its games stay in the library. */
    override suspend fun delete(id: CollectionId) = repo.delete(id)

    override suspend fun add(id: CollectionId, game: GameId) = repo.addGames(id, listOf(game))

    override suspend fun remove(id: CollectionId, game: GameId) = repo.removeGames(id, listOf(game))

    override suspend fun membership(game: GameId): Set<CollectionId> = repo.observeCollectionsOf(game).first()
}

internal class DefaultAppOps(private val ctx: StoreContext) : AppOps {
    private val provider = ctx.services.apps
    private val overrides = ctx.data.apps
    override val supported: Boolean = provider != null

    private val entries: Flow<List<AppEntry>> = if (provider == null) {
        flowOf(emptyList())
    } else {
        combine(provider.apps, overrides.observeAll()) { apps, o -> AppOverrideRepository.applyTo(apps, o) }
    }

    private val all: StateFlow<List<AppEntry>> = entries.resilient().stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    /** Icons the user set for apps (Customise Artwork), by app id. They win over the app's own icon. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val customIcons: StateFlow<Map<String, Any>> = all
        .map { list -> list.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            ctx.data.media.observeFor(ids.map { MediaOwner.OfApp(it) }).map { media ->
                media.mapNotNull { (owner, set) ->
                    val id = (owner as? MediaOwner.OfApp)?.packageName ?: return@mapNotNull null
                    (set.icon ?: set.boxart ?: set.grid)?.model?.let { id to (it as Any) }
                }.toMap()
            }
        }
        .resilient()
        .stateIn(ctx.scope, SharingStarted.Eagerly, emptyMap())

    override fun apps(filter: AppFilter): Flow<List<AppCard>> = combine(all, customIcons) { list, icons ->
        val f = filter
        val visible = list.filterNot { it.hidden }
        when (f) {
            AppFilter.PINNED -> visible.filter { it.pinned }
            AppFilter.GAMES -> visible.filter { it.isGame }.sortedBy { it.displayTitle.lowercase() }
            AppFilter.ALL -> visible.sortedBy { it.displayTitle.lowercase() }
        }.map { card(it, icons) }
    }

    fun search(query: String): List<AppCard> =
        all.value.filter { !it.hidden && it.displayTitle.contains(query, ignoreCase = true) }.take(20).map { card(it, customIcons.value) }

    fun refreshInstalled() {
        provider?.refresh()
    }

    private fun card(entry: AppEntry, icons: Map<String, Any>) = AppCard(entry, icons[entry.id] ?: provider?.iconModel(entry))

    override suspend fun launch(app: AppCard) {
        val p = provider ?: return
        p.launch(app.entry)
        overrides.markUsed(app.entry.id, ctx.now())
    }

    override suspend fun setPinned(app: AppCard, pinned: Boolean) = overrides.setPinned(app.entry.id, pinned)

    override suspend fun setHidden(app: AppCard, hidden: Boolean) = overrides.setHidden(app.entry.id, hidden)

    override suspend fun rename(app: AppCard, title: String?) =
        overrides.setCustomTitle(app.entry.id, title?.trim()?.takeIf { it.isNotEmpty() })

    override suspend fun openInfo(app: AppCard) {
        provider?.openInfo(app.entry)
    }
}

/**
 * Which credentials are stored. Values are only ever read by the clients that need them and are
 * never exposed to the interface.
 */
internal class DefaultCredentialOps(
    private val ctx: StoreContext,
    private val onChanged: (String) -> Unit,
) : CredentialOps {
    private val secrets = ctx.services.secrets
    private val keys = MutableStateFlow<Set<String>>(emptySet())
    override val stored: StateFlow<Set<String>> = keys

    suspend fun load() {
        keys.value = SecretKeys.all.filter { runCatching { secrets.has(it) }.getOrDefault(false) }.toSet()
    }

    suspend fun get(key: String): String? = runCatching { secrets.get(key) }.getOrNull()?.takeIf { it.isNotBlank() }

    override suspend fun put(key: String, value: String) {
        // API keys and client ids never contain spaces, so pasted line breaks and invisible
        // characters go; user names and passwords are only trimmed.
        val isKey = key.contains("apikey", ignoreCase = true) || key.contains("client", ignoreCase = true)
        val trimmed = if (isKey) io.github.matiyaaa.fuse.integrations.sanitizeKey(value) else value.trim()
        if (trimmed.isEmpty()) return remove(key)
        secrets.put(key, trimmed)
        keys.value = keys.value + key
        onChanged(key)
    }

    override suspend fun remove(key: String) {
        secrets.remove(key)
        keys.value = keys.value - key
        onChanged(key)
    }
}

internal class DefaultScopedSettingsOps(
    private val ctx: StoreContext,
    /** Called after a global value changed, so preferences shown elsewhere reload. */
    private val onGlobalChanged: suspend () -> Unit,
) : ScopedSettingsOps {
    private val repo = ctx.data.scopedSettings

    override fun <T> observe(key: ScopedKey<T>, platform: PlatformId?, game: GameId?): Flow<Resolved<T>> =
        repo.observeResolved(key, platform, game)

    override suspend fun <T> set(key: ScopedKey<T>, scope: ScopeRef, value: T) {
        if (scope.scope !in key.scopes) return
        repo.set(key, scope, value)
        if (scope.scope == SettingScope.GLOBAL) onGlobalChanged()
    }

    override suspend fun <T> clear(key: ScopedKey<T>, scope: ScopeRef) {
        if (scope.scope !in key.scopes) return
        repo.clear(key, scope)
        if (scope.scope == SettingScope.GLOBAL) onGlobalChanged()
    }

    override suspend fun setLayout(platform: PlatformId?, layout: LibraryLayout) {
        if (platform == null) set(ScopedSettings.Layout, ScopeRef.Global, layout) else set(ScopedSettings.Layout, ScopeRef.platform(platform), layout)
    }

    override suspend fun setBorder(scope: ScopeRef, border: BorderStyle) = set(ScopedSettings.Border, scope, border)
}
