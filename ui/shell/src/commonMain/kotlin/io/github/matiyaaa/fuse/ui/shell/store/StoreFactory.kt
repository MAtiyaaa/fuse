package io.github.matiyaaa.fuse.ui.shell.store

import kotlinx.coroutines.CoroutineScope

/**
 * Builds the store over [services] and loads the saved settings before returning, so the first
 * frame already knows whether onboarding is done. [scope] lives as long as the app process.
 */
suspend fun createFuseStore(services: FuseServices, scope: CoroutineScope): FuseStore =
    TODO("DefaultFuseStore is being written")
