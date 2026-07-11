package com.v2ray.ang.ui

import android.annotation.SuppressLint
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.v2ray.ang.dto.GroupMapItem

/**
 * Pager adapter for subscription groups.
 * Hosted by [SubSettingFragment] so group pages share its child FragmentManager.
 */
class GroupPagerAdapter(fragment: Fragment, var groups: List<GroupMapItem>) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = groups.size
    override fun createFragment(position: Int) = GroupServerFragment.newInstance(groups[position].id)
    override fun getItemId(position: Int): Long = groups[position].id.hashCode().toLong()
    override fun containsItem(itemId: Long): Boolean = groups.any { it.id.hashCode().toLong() == itemId }

    @SuppressLint("NotifyDataSetChanged")
    fun update(groups: List<GroupMapItem>) {
        this.groups = groups
        notifyDataSetChanged()
    }
}
