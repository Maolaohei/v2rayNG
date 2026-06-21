
package com.v2ray.ang.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.ItemRecyclerFooterBinding
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.viewmodel.MainViewModel
import java.util.Collections

class MainRecyclerAdapter(
    private val mainViewModel: MainViewModel,
    private val adapterListener: MainAdapterListener?
) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>(), ItemTouchHelperAdapter {

    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2
    }

    private val doubleColumnDisplay = MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)
    private var data: List<ServersCache> = emptyList()
    private var selectedGuid: String? = null

    fun setData(newData: List<ServersCache>?, position: Int = -1) {
        val oldData = data
        val updatedData = newData?.toList() ?: emptyList()
        data = updatedData
        selectedGuid = mainViewModel.getSelectedServer()

        if (position >= 0 && position in data.indices) {
            notifyItemChanged(position)
            ensureSelectedIndicator()
        } else {
            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldData.size
                override fun getNewListSize() = data.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) = oldData[oldPos].guid == data[newPos].guid
                override fun areContentsTheSame(oldPos: Int, newPos: Int) = oldData[oldPos] == data[newPos]
            })
            diffResult.dispatchUpdatesTo(this)
            ensureSelectedIndicator()
        }
    }

    override fun getItemCount() = data.size + 1

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is MainViewHolder && position < data.size) {
            val context = holder.itemMainBinding.root.context
            val item = data[position]
            val guid = item.guid
            val profile = item.profile

            holder.itemView.setBackgroundColor(Color.TRANSPARENT)

            holder.itemMainBinding.tvName.text = profile.remarks
            holder.itemMainBinding.tvStatistics.text = getAddress(profile)
            holder.itemMainBinding.tvType.text = getProtocolDescription(profile)

            val testDelay = item.testDelayMillis
            holder.itemMainBinding.tvTestResult.text = if (testDelay != 0L) "${testDelay}ms" else ""
            if (testDelay < 0L) {
                holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(context, R.color.colorPingRed))
            } else {
                holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(context, R.color.colorPing))
            }

            if (guid == selectedGuid) {
                holder.itemMainBinding.layoutIndicator.setBackgroundResource(R.color.colorIndicator)
            } else {
                holder.itemMainBinding.layoutIndicator.setBackgroundResource(0)
            }

            val subRemarks = getSubscriptionRemarks(profile)
            holder.itemMainBinding.tvSubscription.text = subRemarks
            holder.itemMainBinding.layoutSubscription.visibility = if (subRemarks.isEmpty()) View.GONE else View.VISIBLE

            if (doubleColumnDisplay) {
                holder.itemMainBinding.layoutShare.visibility = View.GONE
                holder.itemMainBinding.layoutEdit.visibility = View.GONE
                holder.itemMainBinding.layoutRemove.visibility = View.GONE
                holder.itemMainBinding.layoutMore.visibility = View.VISIBLE

                holder.itemMainBinding.layoutMore.setOnClickListener {
                    adapterListener?.onShare(guid, profile, position, true)
                }
            } else {
                holder.itemMainBinding.layoutShare.visibility = View.VISIBLE
                holder.itemMainBinding.layoutEdit.visibility = View.VISIBLE
                holder.itemMainBinding.layoutRemove.visibility = View.VISIBLE
                holder.itemMainBinding.layoutMore.visibility = View.GONE

                holder.itemMainBinding.layoutShare.setOnClickListener {
                    adapterListener?.onShare(guid, profile, position, false)
                }

                holder.itemMainBinding.layoutEdit.setOnClickListener {
                    adapterListener?.onEdit(guid, position, profile)
                }
                holder.itemMainBinding.layoutRemove.setOnClickListener {
                    adapterListener?.onRemove(guid, position)
                }
            }

            holder.itemMainBinding.infoContainer.setOnClickListener {
                adapterListener?.onSelectServer(guid)
            }
        }
    }

    private fun getAddress(profile: ProfileItem): String {
        return profile.description.nullIfBlank() ?: AngConfigManager.generateDescription(profile)
    }

    private fun getSubscriptionRemarks(profile: ProfileItem): String {
        val subRemarks =
            if (mainViewModel.subscriptionId.isEmpty())
                MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks?.firstOrNull()
            else
                null
        return subRemarks?.toString() ?: ""
    }

    private fun getProtocolDescription(profile: ProfileItem): String {
        if (profile.configType.isComplexType()) {
            return profile.configType.name
        }

        val parts = mutableListOf<String>()
        parts.add(profile.configType.name)

        profile.network?.let { net ->
            if (net.isNotBlank() && !net.equals("tcp", ignoreCase = true)) {
                parts.add(net)
            }
        }

        profile.security?.let { sec ->
            if (sec.isNotBlank()) {
                if (profile.insecure == true && sec.equals("tls", ignoreCase = true)) {
                    parts.add("$sec insecure")
                } else {
                    parts.add(sec)
                }
            }
        }

        return parts.joinToString(" / ")
    }

    fun removeServerSub(guid: String, position: Int) {
        val mutable = data.toMutableList()
        val idx = mutable.indexOfFirst { it.guid == guid }
        if (idx >= 0) {
            mutable.removeAt(idx)
            data = mutable
            notifyItemRemoved(idx)
            notifyItemRangeChanged(idx, data.size - idx)
            ensureSelectedIndicator()
        }
    }

    private fun ensureSelectedIndicator() {
        val guid = selectedGuid ?: return
        val idx = data.indexOfFirst { it.guid == guid }
        if (idx >= 0) {
            notifyItemChanged(idx)
        }
    }

    fun setSelectServer(fromPosition: Int, toPosition: Int) {
        selectedGuid = mainViewModel.getSelectedServer()
        if (fromPosition >= 0 && fromPosition < data.size) {
            notifyItemChanged(fromPosition)
        }
        if (toPosition >= 0 && toPosition < data.size) {
            notifyItemChanged(toPosition)
        }
    }

    fun setSelectedGuid(guid: String?) {
        if (guid == selectedGuid) return
        val oldGuid = selectedGuid
        selectedGuid = guid
        if (oldGuid != null) {
            val oldIdx = data.indexOfFirst { it.guid == oldGuid }
            if (oldIdx >= 0) notifyItemChanged(oldIdx)
        }
        if (guid != null) {
            val newIdx = data.indexOfFirst { it.guid == guid }
            if (newIdx >= 0) notifyItemChanged(newIdx)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return when (viewType) {
            VIEW_TYPE_ITEM ->
                MainViewHolder(ItemRecyclerMainBinding.inflate(LayoutInflater.from(parent.context), parent, false))

            else ->
                FooterViewHolder(ItemRecyclerFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == data.size) {
            VIEW_TYPE_FOOTER
        } else {
            VIEW_TYPE_ITEM
        }
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onItemSelected() {
            itemView.setBackgroundColor(Color.LTGRAY)
        }

        fun onItemClear() {
            itemView.setBackgroundColor(0)
        }
    }

    class MainViewHolder(val itemMainBinding: ItemRecyclerMainBinding) :
        BaseViewHolder(itemMainBinding.root), ItemTouchHelperViewHolder

    class FooterViewHolder(val itemFooterBinding: ItemRecyclerFooterBinding) :
        BaseViewHolder(itemFooterBinding.root)

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        mainViewModel.swapServer(fromPosition, toPosition)
        if (fromPosition < data.size && toPosition < data.size) {
            val mutable = data.toMutableList()
            Collections.swap(mutable, fromPosition, toPosition)
            data = mutable
        }
        notifyItemMoved(fromPosition, toPosition)
        ensureSelectedIndicator()
        return true
    }

    override fun onItemMoveCompleted() {
    }

    override fun onItemDismiss(position: Int) {
    }
}
