package com.v2ray.ang.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.v2ray.ang.R
import com.v2ray.ang.databinding.FragmentMoreBinding

/**
 * Settings-only shell. Hosts [SettingsActivity.SettingsFragment] and handles nested
 * PreferenceScreen navigation (secondary settings pages).
 */
class MoreFragment : BaseFragment<FragmentMoreBinding>(),
    PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentMoreBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(R.id.settings_container) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsActivity.SettingsFragment())
                .commit()
        }

        // Pop nested settings pages before leaving the More tab.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (childFragmentManager.backStackEntryCount > 0) {
                        childFragmentManager.popBackStack()
                        (activity as? AppCompatActivity)?.supportActionBar?.title =
                            getString(R.string.home_nav_more)
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )
    }

    override fun onPreferenceStartScreen(
        caller: PreferenceFragmentCompat,
        pref: PreferenceScreen,
    ): Boolean {
        val fragment = SettingsActivity.SettingsFragment().apply {
            arguments = Bundle().apply {
                putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.key)
            }
        }
        childFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(pref.key)
            .commit()
        (activity as? AppCompatActivity)?.supportActionBar?.title = pref.title
        return true
    }
}
