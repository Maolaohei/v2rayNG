package com.v2ray.ang.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.viewmodel.MainViewModel

class MainActivity : HelperBaseActivity() {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    val mainViewModel: MainViewModel by viewModels()

    val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        homeFragment()?.onVpnPermissionResult(it.resultCode == RESULT_OK)
    }

    val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        processPendingSettingsChanges()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.title_server))

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> switchTab(R.id.nav_home, ::HomeFragment, getString(R.string.title_server))
                R.id.nav_subscription -> switchTab(R.id.nav_subscription, ::SubSettingFragment, getString(R.string.title_sub_setting))
                R.id.nav_routing -> switchTab(R.id.nav_routing, ::RoutingSettingFragment, getString(R.string.routing_settings_title))
                R.id.nav_settings -> switchTab(R.id.nav_settings, ::MoreFragment, getString(R.string.home_nav_more))
                else -> false
            }
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_home
        } else {
            supportActionBar?.title = when (binding.bottomNav.selectedItemId) {
                R.id.nav_subscription -> getString(R.string.title_sub_setting)
                R.id.nav_routing -> getString(R.string.routing_settings_title)
                R.id.nav_settings -> getString(R.string.home_nav_more)
                else -> getString(R.string.title_server)
            }
        }
    }

    private fun switchTab(itemId: Int, factory: () -> Fragment, title: CharSequence): Boolean {
        val tag = tabTag(itemId)
        val fm = supportFragmentManager
        val transaction = fm.beginTransaction()
        val tabTags = setOf(tabTag(R.id.nav_home), tabTag(R.id.nav_subscription), tabTag(R.id.nav_routing), tabTag(R.id.nav_settings))
        tabTags.forEach { existingTag ->
            fm.findFragmentByTag(existingTag)?.let { existing ->
                if (existing.isAdded) transaction.hide(existing)
            }
        }
        var target = fm.findFragmentByTag(tag)
        if (target == null) {
            target = factory()
            transaction.add(R.id.fragment_container, target, tag)
        } else {
            transaction.show(target)
        }
        transaction.commit()
        supportActionBar?.title = title
        invalidateOptionsMenu()
        return true
    }

    private fun tabTag(itemId: Int) = "tab_$itemId"

    private fun homeFragment(): HomeFragment? {
        return supportFragmentManager.findFragmentByTag(tabTag(R.id.nav_home)) as? HomeFragment
    }

    fun refreshGroupTabTitles(refreshAll: Boolean = false) {
        homeFragment()?.refreshGroupTabTitles(refreshAll)
    }

    fun restartV2Ray() {
        homeFragment()?.restartV2Ray()
    }

    fun importConfigViaSub(): Boolean {
        return homeFragment()?.importConfigViaSub() ?: false
    }

    override fun onResume() {
        super.onResume()
        processPendingSettingsChanges()
    }

    private fun processPendingSettingsChanges() {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            homeFragment()?.restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            homeFragment()?.setupGroupTab()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (binding.bottomNav.selectedItemId != R.id.nav_home) {
                binding.bottomNav.selectedItemId = R.id.nav_home
                return true
            }
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
