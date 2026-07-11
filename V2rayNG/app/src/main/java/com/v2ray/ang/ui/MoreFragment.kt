package com.v2ray.ang.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.v2ray.ang.R
import com.v2ray.ang.databinding.FragmentMoreBinding

/**
 * Settings-only shell. Tool entries live under VPN / Core preference categories.
 */
class MoreFragment : BaseFragment<FragmentMoreBinding>() {
    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentMoreBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(R.id.settings_container) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsActivity.SettingsFragment())
                .commit()
        }
    }
}