package com.v2ray.ang.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import java.lang.ref.SoftReference

class QSTileService : TileService() {

    private var mMsgReceive: BroadcastReceiver? = null

    /**
     * Sets the state of the tile.
     * @param state The state to set.
     */
    fun setState(state: Int) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(applicationContext, R.drawable.ic_stat_name)
        if (state == Tile.STATE_INACTIVE) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.app_name)
        } else if (state == Tile.STATE_ACTIVE) {
            tile.state = Tile.STATE_ACTIVE
            val name = CoreServiceManager.getRunningServerName()
            tile.label = if (name.isNotBlank()) name else getString(R.string.app_name)
        }
        tile.updateTile()
    }

    /** Daemon-process live check; sticky against flaky REGISTER races. */
    fun isSessionLive(): Boolean {
        return try {
            CoreServiceManager.hasLiveSession() ||
                CoreServiceManager.isRunning() ||
                CoreServiceManager.isSoftRestarting() ||
                CoreServiceManager.serviceControl != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun refreshFromCore() {
        if (isSessionLive()) {
            setState(Tile.STATE_ACTIVE)
        } else {
            setState(Tile.STATE_INACTIVE)
        }
    }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     */
    override fun onStartListening() {
        super.onStartListening()
        // Paint from local daemon state first so the tile does not flash off
        // while we wait for MSG_REGISTER_CLIENT round-trip.
        refreshFromCore()
        if (mMsgReceive == null) {
            mMsgReceive = ReceiveMessageHandler(this)
            val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
            ContextCompat.registerReceiver(applicationContext, mMsgReceive, mFilter, Utils.receiverFlags())
        }
        MessageUtil.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    /**
     * Called when the tile stops listening.
     */
    override fun onStopListening() {
        super.onStopListening()
        try {
            if (mMsgReceive != null) {
                applicationContext.unregisterReceiver(mMsgReceive)
                mMsgReceive = null
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to unregister receiver", e)
            mMsgReceive = null
        }
    }

    /**
     * Called when the tile is clicked.
     */
    override fun onClick() {
        super.onClick()
        // Prefer live session over visual tile state (visual can lag / race).
        if (isSessionLive()) {
            CoreServiceManager.stopVService(this)
            setState(Tile.STATE_INACTIVE)
        } else {
            CoreServiceManager.startVServiceFromToggle(this)
            // Connecting: keep inactive until START_SUCCESS/RUNNING arrives.
            setState(Tile.STATE_INACTIVE)
        }
    }

    private class ReceiveMessageHandler(context: QSTileService) : BroadcastReceiver() {
        var mReference: SoftReference<QSTileService> = SoftReference(context)
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val context = mReference.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING,
                AppConfig.MSG_STATE_START_SUCCESS,
                AppConfig.MSG_STATE_NETWORK_RECOVERED,
                -> {
                    context.setState(Tile.STATE_ACTIVE)
                }

                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    // Sticky: REGISTER races / soft-restart can emit NOT_RUNNING
                    // while core is still up. Re-check before dimming.
                    if (context.isSessionLive()) {
                        context.setState(Tile.STATE_ACTIVE)
                    } else {
                        context.setState(Tile.STATE_INACTIVE)
                    }
                }

                AppConfig.MSG_STATE_START_FAILURE,
                AppConfig.MSG_STATE_STOP_SUCCESS,
                -> {
                    if (context.isSessionLive()) {
                        context.setState(Tile.STATE_ACTIVE)
                    } else {
                        context.setState(Tile.STATE_INACTIVE)
                    }
                }

                AppConfig.MSG_STATE_NETWORK_RECOVERING -> {
                    // Keep active appearance during recovery; proxy still "on".
                    context.setState(Tile.STATE_ACTIVE)
                }
            }
        }
    }
}
