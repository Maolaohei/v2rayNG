package com.v2ray.ang.ui

import android.os.Bundle
import com.v2ray.ang.R

class RoutingSettingActivity : HelperBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(R.layout.activity_routing_setting, showHomeAsUp = true, title = getString(R.string.routing_settings_title))
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.legacy_fragment_container, RoutingSettingFragment())
                .commit()
        }
    }
}
