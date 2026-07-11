package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import androidx.fragment.app.activityViewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.FragmentSubscriptionManageBinding
import com.v2ray.ang.databinding.ItemQrcodeBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.SubscriptionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SubscriptionManageFragment : BaseFragment<FragmentSubscriptionManageBinding>() {
    override fun inflateBinding(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?) =
        FragmentSubscriptionManageBinding.inflate(inflater, container, false)

    private val hostActivity: androidx.fragment.app.FragmentActivity
        get() = requireActivity()
    private val viewModel: SubscriptionsViewModel by activityViewModels()
    private lateinit var adapter: SubSettingRecyclerAdapter
    private var mItemTouchHelper: ItemTouchHelper? = null
    private val share_method: Array<out String> by lazy {
        resources.getStringArray(R.array.share_sub_method)
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)

        adapter = SubSettingRecyclerAdapter(viewModel, ActivityAdapterListener())

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        // Card spacing is provided by item layout margins.
        binding.recyclerView.adapter = adapter

        mItemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter))
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        setHasOptionsMenu(!hidden)
        setMenuVisibility(!hidden)
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: android.view.MenuInflater) {
        inflater.inflate(R.menu.action_sub_setting, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.add_config -> {
            startActivity(Intent(requireContext(), SubEditActivity::class.java))
            true
        }

        R.id.sub_update -> {
            (requireActivity() as BaseActivity).showLoading()

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val result = AngConfigManager.updateConfigViaSubAll()
                delay(500L)
                launch(Dispatchers.Main) {
                    if (result.successCount + result.failureCount + result.skipCount == 0) {
                        requireContext().toast(R.string.title_update_subscription_no_subscription)
                    } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                        requireContext().toast(getString(R.string.title_update_config_count, result.configCount))
                    } else {
                        requireContext().toast(
                            getString(
                                R.string.title_update_subscription_result,
                                result.configCount, result.successCount, result.failureCount, result.skipCount
                            )
                        )
                    }
                    (requireActivity() as BaseActivity).hideLoading()
                    refreshData()
                }
            }

            true
        }

        else -> super.onOptionsItemSelected(item)

    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshData() {
        viewModel.reload()
        adapter.notifyDataSetChanged()
    }

    private inner class ActivityAdapterListener : BaseAdapterListener {
        override fun onEdit(guid: String, position: Int) {
            startActivity(
                Intent(requireContext(), SubEditActivity::class.java)
                    .putExtra("subId", guid)
            )
        }

        override fun onRemove(guid: String, position: Int) {
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE)) {
                AlertDialog.Builder(hostActivity)
                    .setMessage(R.string.del_config_comfirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        viewModel.remove(guid)
                        refreshData()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                viewModel.remove(guid)
                refreshData()
            }
        }

        override fun onShare(url: String) {
            AlertDialog.Builder(hostActivity)
                .setItems(share_method.asList().toTypedArray()) { _, i ->
                    try {
                        when (i) {
                            0 -> {
                                val ivBinding =
                                    ItemQrcodeBinding.inflate(LayoutInflater.from(hostActivity))
                                ivBinding.ivQcode.setImageBitmap(
                                    QRCodeDecoder.createQRCode(
                                        url

                                    )
                                )
                                AlertDialog.Builder(hostActivity).setView(ivBinding.root).show()
                            }

                            1 -> {
                                Utils.setClipboard(hostActivity, url)
                            }

                            else -> hostActivity.toast("else")
                        }
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "Share subscription failed", e)
                    }
                }.show()
        }

        override fun onRefreshData() {
            refreshData()
        }
    }
}
