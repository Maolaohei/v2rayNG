package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ProcessService {
    @Volatile
    private var process: Process? = null
    private var scope: CoroutineScope? = null

    /**
     * Runs a process with the given command.
     * @param context The context.
     * @param cmd The command to run.
     */
    fun runProcess(context: Context, cmd: MutableList<String>) {
        LogUtil.i(AppConfig.TAG, cmd.toString())

        try {
            stopProcess()
            val proBuilder = ProcessBuilder(cmd)
            proBuilder.redirectErrorStream(true)
            process = proBuilder
                .directory(context.filesDir)
                .start()

            scope = CoroutineScope(Dispatchers.IO)
            scope!!.launch {
                LogUtil.i(AppConfig.TAG, "runProcess check")
                process?.waitFor()
                LogUtil.i(AppConfig.TAG, "runProcess exited")
            }
            LogUtil.i(AppConfig.TAG, process.toString())

        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, e.toString(), e)
        }
    }

    /**
     * Stops the running process.
     */
    fun stopProcess() {
        scope?.cancel()
        scope = null
        try {
            LogUtil.i(AppConfig.TAG, "runProcess destroy")
            process?.destroy()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to destroy process", e)
        }
    }
}
