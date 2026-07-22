package com.v2ray.ang.ui

import android.os.Bundle
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager

/** Stop-only shortcut; runs in :RunSoLibV2RayDaemon. */
class ScStopActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        moveTaskToBack(true)

        setContentView(R.layout.activity_none)

        val live = CoreServiceManager.hasLiveSession() ||
            CoreServiceManager.isRunning() ||
            CoreServiceManager.isSoftRestarting() ||
            CoreServiceManager.serviceControl != null
        if (live) {
            CoreServiceManager.stopVService(this)
        }
        finish()
    }
}
