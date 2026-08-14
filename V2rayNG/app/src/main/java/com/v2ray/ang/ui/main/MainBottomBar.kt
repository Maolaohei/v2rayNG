package com.v2ray.ang.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R

enum class MainTab(@DrawableRes val iconRes: Int, @StringRes val labelRes: Int) {
    Home(R.drawable.ic_home_24dp, R.string.home_nav_home),
    Subscription(R.drawable.ic_subscriptions_24dp, R.string.home_nav_subscription),
    Routing(R.drawable.ic_routing_24dp, R.string.home_nav_routing),
    More(R.drawable.ic_settings_24dp, R.string.home_nav_more)
}

@Composable
fun MainBottomBar(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit
) {
    NavigationBar {
        MainTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selectedTab,
                onClick = { onTabSelected(tab) },
                icon = { Icon(painterResource(tab.iconRes), contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) }
            )
        }
    }
}
