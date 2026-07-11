package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.v2ray.ang.R
import com.v2ray.ang.databinding.FragmentMoreBinding

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

        binding.itemPerApp.setOnClickListener {
            startActivity(Intent(requireContext(), PerAppProxyActivity::class.java))
        }
        binding.itemAssets.setOnClickListener {
            startActivity(Intent(requireContext(), UserAssetActivity::class.java))
        }
        binding.itemLogcat.setOnClickListener {
            startActivity(Intent(requireContext(), LogcatActivity::class.java))
        }
        binding.itemBackup.setOnClickListener {
            startActivity(Intent(requireContext(), BackupActivity::class.java))
        }
        binding.itemUpdate.setOnClickListener {
            startActivity(Intent(requireContext(), CheckUpdateActivity::class.java))
        }
        binding.itemAbout.setOnClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
        }
    }
}
