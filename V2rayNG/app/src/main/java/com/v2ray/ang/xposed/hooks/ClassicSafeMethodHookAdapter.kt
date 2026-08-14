package com.v2ray.ang.xposed.hooks

import de.robv.android.xposed.XC_MethodHook

/**
 * Classic-only adapter. Used solely by assets/xposed_init path when modern XposedInterface
 * is unavailable. Modern targetApi=102 path must never instantiate this class.
 */
internal class ClassicSafeMethodHookAdapter(
    private val delegate: SafeMethodHook,
) : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) {
        delegate.classicBefore(param)
    }

    override fun afterHookedMethod(param: MethodHookParam) {
        delegate.classicAfter(param)
    }
}
