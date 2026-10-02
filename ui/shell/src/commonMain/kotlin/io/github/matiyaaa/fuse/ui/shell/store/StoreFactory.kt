package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.ui.shell.store.impl.DefaultFuseStore
import kotlinx.coroutines.CoroutineScope

/**
 * Builds the store over [services] and loads the saved settings before returning, so the first
 * frame already knows whether onboarding is done. [scope] lives as long as the app process.
 */
suspend fun createFuseStore(services: FuseServices, scope: CoroutineScope, safeMode: Boolean = false): FuseStore =
    DefaultFuseStore.create(services, scope, safeMode)
