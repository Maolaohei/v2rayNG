package com.v2ray.ang.ui

import android.os.Bundle
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager

/** Start-only shortcut; runs in :RunSoLibV2RayDaemon. */
class ScStartActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        moveTaskToBack(true)

        setContentView(R.layout.activity_none)

        val live = CoreServiceManager.hasLiveSession() ||
            CoreServiceManager.isRunning() ||
            CoreServiceManager.isSoftRestarting()
        if (!live) {
            CoreServiceManager.startVServiceFromToggle(this)
        }
        finish()
    }
}
