package com.v2ray.ang.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.databinding.ItemRecyclerLogcatBinding
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.viewmodel.LogcatViewModel

class LogcatRecyclerAdapter(
    private val viewModel: LogcatViewModel,
    private val onLongClick: ((String) -> Boolean)? = null
) : RecyclerView.Adapter<LogcatRecyclerAdapter.MainViewHolder>() {

    override fun getItemCount() = viewModel.getAll().size

    override fun onBindViewHolder(holder: MainViewHolder, position: Int) {
        try {
            val logs = viewModel.getAll()
            val log = logs.getOrNull(position).orEmpty()

            if (log.isEmpty()) {
                holder.itemSubSettingBinding.logTag.text = ""
                holder.itemSubSettingBinding.logContent.text = ""
            } else {
                val (tag, content) = splitLogLine(log)
                holder.itemSubSettingBinding.logTag.text = tag
                holder.itemSubSettingBinding.logContent.text = content
            }

            holder.itemView.setOnLongClickListener {
                onLongClick?.invoke(log) ?: false
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error binding log view data", e)
        }
    }

    /**
     * Supports:
     * - threadtime: "01-01 12:00:00.000  1234  5678 I tag: message"
     * - legacy time/s: "01-01 12:00:00.000 I/tag(1234): message"
     * - plain diagnostic lines we inject ourselves
     */
    private fun splitLogLine(log: String): Pair<String, String> {
        val colon = log.indexOf(": ")
        if (colon > 0) {
            val head = log.substring(0, colon).trim()
            val body = log.substring(colon + 2).trim()
            // Prefer last token of head as tag (threadtime ends with level+tag, legacy has tag(...))
            val tagToken = head.substringAfterLast(' ').ifBlank { head }
            val tag = tagToken
                .substringBefore('(')
                .removePrefix("I/")
                .removePrefix("D/")
                .removePrefix("W/")
                .removePrefix("E/")
                .removePrefix("V/")
                .ifBlank { "log" }
            return tag to body.ifBlank { log }
        }
        // Fallback: show whole line
        return "log" to log
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MainViewHolder {
        return MainViewHolder(
            ItemRecyclerLogcatBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    class MainViewHolder(val itemSubSettingBinding: ItemRecyclerLogcatBinding) :
        RecyclerView.ViewHolder(itemSubSettingBinding.root)
}
