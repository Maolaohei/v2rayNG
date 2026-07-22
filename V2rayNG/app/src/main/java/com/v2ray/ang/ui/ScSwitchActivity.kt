package com.v2ray.ang.ui

import android.os.Bundle
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager

/**
 * Toggle activity runs in :RunSoLibV2RayDaemon (same process as core services).
 * Prefer live-session signals so soft-restart gaps do not double-start.
 */
class ScSwitchActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        moveTaskToBack(true)

        setContentView(R.layout.activity_none)

        val live = CoreServiceManager.hasLiveSession() ||
            CoreServiceManager.isRunning() ||
            CoreServiceManager.isSoftRestarting()
        if (live) {
            CoreServiceManager.stopVService(this)
        } else {
            CoreServiceManager.startVServiceFromToggle(this)
        }
        finish()
    }
}
