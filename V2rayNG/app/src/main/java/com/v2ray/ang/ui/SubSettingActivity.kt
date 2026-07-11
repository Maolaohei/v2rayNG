package com.v2ray.ang.ui

import android.os.Bundle
import com.v2ray.ang.R

/**
 * Compatibility shell for deep-links/old entry points.
 * UI is hosted in [SubSettingFragment] inside [MainActivity] bottom navigation.
 */
class SubSettingActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(R.layout.activity_sub_setting, showHomeAsUp = true, title = getString(R.string.title_sub_setting))
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.legacy_fragment_container, SubSettingFragment())
                .commit()
        }
    }
}
