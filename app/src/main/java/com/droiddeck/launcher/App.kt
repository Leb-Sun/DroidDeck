package com.droiddeck.launcher

import android.app.Application
import com.droiddeck.launcher.runtime.BwrapSpawner
import com.droiddeck.launcher.runtime.SpawnServer
import com.droiddeck.launcher.session.CrashHandler
import com.droiddeck.launcher.session.GpuClockPin

/**
 * Process-wide setup: the crash handler, so a session's folder is finished even when we die, and
 * the GPU clock pin cleared in case a killed process left it set, and the socket the guest has
 * process trees started through (SpawnServer), Flatpak's sandboxes (BwrapSpawner) so far.
 */
class App : Application() {
    override fun attachBaseContext(base: android.content.Context) =
        super.attachBaseContext(com.droiddeck.launcher.core.AppLanguage.wrap(base))

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.droiddeck.launcher.core.AppLanguage.systemChanged(newConfig)
    }

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        com.droiddeck.launcher.session.QuestExperimental.initialize(this)
        com.droiddeck.launcher.session.SessionPrefs.settleGpuDriverMode(this)
        com.droiddeck.launcher.update.AppUpdates.init(this)
        GpuClockPin.clearLeftover(this)
        SpawnServer.register(SpawnServer.SANDBOX, BwrapSpawner)
        SpawnServer.start(this)
        com.droiddeck.launcher.frontend.GameFileSync.start(this)
        com.droiddeck.launcher.stores.StoresState.init(this)
        com.droiddeck.launcher.stores.StoresState.register(com.droiddeck.launcher.stores.gog.GogBackend)
        com.droiddeck.launcher.stores.StoresState.register(com.droiddeck.launcher.stores.epic.EpicBackend)
        com.droiddeck.launcher.stores.StoresState.register(com.droiddeck.launcher.stores.amazon.AmazonBackend)
    }
}
