package com.v2ray.ang.xposed.hooks.hidevpn

import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Bundle
import com.v2ray.ang.xposed.hooks.XposedApi
import com.v2ray.ang.xposed.HookErrorStore
import com.v2ray.ang.xposed.VpnHideContext
import com.v2ray.ang.xposed.VpnSanitizer
import com.v2ray.ang.xposed.hooks.SafeMethodHook

class HookConnectivityManagerRequestNetwork(private val helper: ConnectivityServiceHookHelper) {
    private companion object {
        private const val SOURCE = "HookRequestNetwork"
    }

    fun install() {
        // Hook requestNetwork based on API level
        hookRequestNetwork()

        // Hook listenForNetwork based on API level
        hookListenForNetwork()

        // Hook pendingRequestForNetwork
        hookPendingRequestForNetwork()

        // Hook pendingListenForNetwork
        hookPendingListenForNetwork()

        // Hook createDefaultNetworkCapabilitiesForUid (API 28+)
        if (helper.sdkInt >= 28) {
            try {
                hookCreateDefaultNetworkCapabilities()
            } catch (e: Throwable) {
                HookErrorStore.w(SOURCE, "hookCreateDefaultNetworkCapabilities failed: ${e.message}", e)
            }
        }

        // Hook copyDefaultNetworkCapabilitiesForUid (API 31+)
        if (helper.sdkInt >= 31) {
            try {
                hookCopyDefaultNetworkCapabilities()
            } catch (e: Throwable) {
                HookErrorStore.w(SOURCE, "hookCopyDefaultNetworkCapabilities failed: ${e.message}", e)
            }
        }

        // Hook callCallbackForRequest
        hookCallCallbackForRequest()

        // Hook sendPendingIntentForRequest
        try {
            hookSendPendingIntentForRequest()
        } catch (e: Throwable) {
            HookErrorStore.w(SOURCE, "hookSendPendingIntentForRequest failed: ${e.message}", e)
        }
    }

    private fun hookRequestNetwork() {
        val methods = listOf(
            "V16" to { hookRequestNetworkV16() },
            "V12" to { hookRequestNetworkV12() },
            "V11" to { hookRequestNetworkV11() },
            "V8" to { hookRequestNetworkV8() },
        )
        for ((version, hook) in methods) {
            try {
                hook()
                return
            } catch (e: Throwable) {
                HookErrorStore.w(SOURCE, "hookRequestNetwork$version failed: ${e.message}", e)
            }
        }
        HookErrorStore.e(SOURCE, "All hookRequestNetwork variants failed")
    }

    private fun hookListenForNetwork() {
        val methods = listOf(
            "V16" to { hookListenForNetworkV16() },
            "V12" to { hookListenForNetworkV12() },
            "V11" to { hookListenForNetworkV11() },
            "V8" to { hookListenForNetworkV8() },
        )
        for ((version, hook) in methods) {
            try {
                hook()
                return
            } catch (e: Throwable) {
                HookErrorStore.w(SOURCE, "hookListenForNetwork$version failed: ${e.message}", e)
            }
        }
        HookErrorStore.e(SOURCE, "All hookListenForNetwork variants failed")
    }

    private fun hookPendingRequestForNetwork() {
        val methods = listOf(
            "V12" to { hookPendingRequestForNetworkV12() },
            "V11" to { hookPendingRequestForNetworkV11() },
            "V8" to { hookPendingRequestForNetworkV8() },
        )
        for ((version, hook) in methods) {
            try {
                hook()
                return
            } catch (e: Throwable) {
                HookErrorStore.w(SOURCE, "hookPendingRequestForNetwork$version failed: ${e.message}", e)
            }
        }
        HookErrorStore.e(SOURCE, "All hookPendingRequestForNetwork variants failed")
    }

    private fun hookPendingListenForNetwork() {
        val methods = listOf(
            "V12" to { hookPendingListenForNetworkV12() },
            "V11" to { hookPendingListenForNetworkV11() },
            "V8" to { hookPendingListenForNetworkV8() },
        )
        for ((version, hook) in methods) {
            try {
                hook()
                return
            } catch (e: Throwable) {
                HookErrorStore.w(SOURCE, "hookPendingListenForNetwork$version failed: ${e.message}", e)
            }
        }
        HookErrorStore.e(SOURCE, "All hookPendingListenForNetwork variants failed")
    }

    // region requestNetwork versions

    private fun hookRequestNetworkV8() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "requestNetwork",
            NetworkCapabilities::class.java,
            android.os.Messenger::class.java,
            Int::class.javaPrimitiveType,
            android.os.IBinder::class.java,
            Int::class.javaPrimitiveType,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val uid = VpnHideContext.effectiveCallerUid()
                    if (!VpnSanitizer.shouldHide(uid)) return
                    val nc = param.args[0] as? NetworkCapabilities ?: return
                    param.args[0] = VpnSanitizer.sanitizeRequestCapabilities(nc)
                }
            },
        )
    }

    private fun hookRequestNetworkV11() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "requestNetwork",
            NetworkCapabilities::class.java,
            android.os.Messenger::class.java,
            Int::class.javaPrimitiveType,
            android.os.IBinder::class.java,
            Int::class.javaPrimitiveType,
            String::class.java,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val uid = VpnHideContext.effectiveCallerUid()
                    if (!VpnSanitizer.shouldHide(uid)) return
                    val nc = param.args[0] as? NetworkCapabilities ?: return
                    param.args[0] = VpnSanitizer.sanitizeRequestCapabilities(nc)
                }
            },
        )
    }

    private fun hookRequestNetworkV12() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "requestNetwork",
            Int::class.javaPrimitiveType,
            NetworkCapabilities::class.java,
            Int::class.javaPrimitiveType,
            android.os.Messenger::class.java,
            Int::class.javaPrimitiveType,
            android.os.IBinder::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val uid = VpnHideContext.effectiveCallerUid()
                    if (!VpnSanitizer.shouldHide(uid)) return
                    val nc = param.args[1] as? NetworkCapabilities ?: return
                    param.args[1] = VpnSanitizer.sanitizeRequestCapabilities(nc)
                }
            },
        )
    }

    private fun hookRequestNetworkV16() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "requestNetwork",
            Int::class.javaPrimitiveType,
            NetworkCapabilities::class.java,
            Int::class.javaPrimitiveType,
            android.os.Messenger::class.java,
            Int::class.javaPrimitiveType,
            android.os.IBinder::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val uid = VpnHideContext.effectiveCallerUid()
                    if (!VpnSanitizer.shouldHide(uid)) return
                    val nc = param.args[1] as? NetworkCapabilities ?: return
                    param.args[1] = VpnSanitizer.sanitizeRequestCapabilities(nc)
                }
            },
        )
    }

    // endregion

    // region listenForNetwork versions

    private fun hookListenForNetworkV8() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "listenForNetwork",
            NetworkCapabilities::class.java,
            android.os.Messenger::class.java,
            android.os.IBinder::class.java,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val uid = VpnHideContext.effectiveCallerUid()
                    if (!VpnSanitizer.shouldHide(uid)) return
                    val nc = param.args[0] as? NetworkCapabilities ?: return
                    param.args[0] = VpnSanitizer.sanitizeRequestCapabilities(nc)
                }
            },
        )
    }

    private fun hookListenForNetworkV11() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "listenForNetwork",
            NetworkCapabilities::class.java,
            android.os.Messenger::class.java,
            android.os.IBinder::class.java,
            String::class.java,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val uid = VpnHideContext.effectiveCallerUid()
                    if (!VpnSanitizer.shouldHide(uid)) return
                    val nc = param.args[0] as? NetworkCapabilities ?: return
                    param.args[0] = VpnSanitizer.sanitizeRequestCapabilities(nc)
                }
            },
        )
    }

    private fun hookListenForNetworkV12() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "listenForNetwork",
            NetworkCapabilities::class.java,
            android.os.Messenger::class.java,
            android.os.IBinder::class.java,
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val uid = VpnHideContext.effectiveCallerUid()
                    if (!VpnSanitizer.shouldHide(uid)) return
                    val nc = param.args[0] as? NetworkCapabilities ?: return
                    param.args[0] = VpnSanitizer.sanitizeRequestCapabilities(nc)
                }
            },
        )
    }

    private fun hookListenForNetworkV16() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "listenForNetwork",
            NetworkCapabilities::class.java,
            android.os.Messenger::class.java,
            android.os.IBinder::class.java,
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val uid = VpnHideContext.effectiveCallerUid()
                    if (!VpnSanitizer.shouldHide(uid)) return
                    val nc = param.args[0] as? NetworkCapabilities ?: return
                    param.args[0] = VpnSanitizer.sanitizeRequestCapabilities(nc)
                }
            },
        )
    }

    // endregion

    // region pendingRequestForNetwork versions

    private fun hookPendingRequestForNetworkV8() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "pendingRequestForNetwork",
            NetworkCapabilities::class.java,
            android.app.PendingIntent::class.java,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val uid = VpnHideContext.effectiveCallerUid()
                    if (!VpnSanitizer.shouldHide(uid)) return
                    val nc = param.args[0] as? NetworkCapabilities ?: return
                    param.args[0] = VpnSanitizer.sanitizeRequestCapabilities(nc)
                }
            },
        )
    }

    private fun hookPendingRequestForNetworkV11() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "pendingRequestForNetwork",
            NetworkCapabilities::class.java,
            android.app.PendingIntent::class.java,
            String::class.java,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val uid = VpnHideContext.effectiveCallerUid()
                    if (!VpnSanitizer.shouldHide(uid)) return
                    val nc = param.args[0] as? NetworkCapabilities ?: return
                    param.args[0] = VpnSanitizer.sanitizeRequestCapabilities(nc)
                }
            },
        )
    }

    private fun hookPendingRequestForNetworkV12() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "pendingRequestForNetwork",
            NetworkCapabilities::class.java,
            android.app.PendingIntent::class.java,
            String::class.java,
            String::class.java,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val uid = VpnHideContext.effectiveCallerUid()
                    if (!VpnSanitizer.shouldHide(uid)) return
                    val nc = param.args[0] as? NetworkCapabilities ?: return
                    param.args[0] = VpnSanitizer.sanitizeRequestCapabilities(nc)
                }
            },
        )
    }

    // endregion

    // region pendingListenForNetwork versions

    private fun hookPendingListenForNetworkV8() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "pendingListenForNetwork",
            NetworkCapabilities::class.java,
            android.app.PendingIntent::class.java,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val uid = VpnHideContext.effectiveCallerUid()
                    if (!VpnSanitizer.shouldHide(uid)) return
                    val nc = param.args[0] as? NetworkCapabilities ?: return
                    param.args[0] = VpnSanitizer.sanitizeRequestCapabilities(nc)
                }
            },
        )
    }

    private fun hookPendingListenForNetworkV11() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "pendingListenForNetwork",
            NetworkCapabilities::class.java,
            android.app.PendingIntent::class.java,
            String::class.java,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val uid = VpnHideContext.effectiveCallerUid()
                    if (!VpnSanitizer.shouldHide(uid)) return
                    val nc = param.args[0] as? NetworkCapabilities ?: return
                    param.args[0] = VpnSanitizer.sanitizeRequestCapabilities(nc)
                }
            },
        )
    }

    private fun hookPendingListenForNetworkV12() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "pendingListenForNetwork",
            NetworkCapabilities::class.java,
            android.app.PendingIntent::class.java,
            String::class.java,
            String::class.java,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val uid = VpnHideContext.effectiveCallerUid()
                    if (!VpnSanitizer.shouldHide(uid)) return
                    val nc = param.args[0] as? NetworkCapabilities ?: return
                    param.args[0] = VpnSanitizer.sanitizeRequestCapabilities(nc)
                }
            },
        )
    }

    // endregion

    // region default capabilities

    private fun hookCreateDefaultNetworkCapabilities() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "createDefaultNetworkCapabilitiesForUid",
            Int::class.javaPrimitiveType,
            object : SafeMethodHook(SOURCE) {
                override fun afterHook(param: SafeMethodHook.HookParam) {
                    val uid = param.args[0] as? Int ?: return
                    if (!VpnSanitizer.shouldHide(uid)) return
                    val nc = param.result as? NetworkCapabilities ?: return
                    param.result = VpnSanitizer.sanitizeRequestCapabilities(nc)
                }
            },
        )
    }

    private fun hookCopyDefaultNetworkCapabilities() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "copyDefaultNetworkCapabilitiesForUid",
            NetworkCapabilities::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
            object : SafeMethodHook(SOURCE) {
                override fun afterHook(param: SafeMethodHook.HookParam) {
                    val requestorUid = param.args[2] as? Int ?: return
                    if (!VpnSanitizer.shouldHide(requestorUid)) return
                    val nc = param.result as? NetworkCapabilities ?: return
                    param.result = VpnSanitizer.sanitizeRequestCapabilities(nc)
                }
            },
        )
    }

    // endregion

    // region callback hooks

    private fun hookCallCallbackForRequest() {
        if (helper.sdkInt >= 36) {
            // API 36+ has both WithAgent and WithBundle variants
            try {
                hookCallCallbackForRequestWithAgent()
            } catch (e: Throwable) {
                HookErrorStore.w(SOURCE, "hookCallCallbackForRequestWithAgent failed: ${e.message}", e)
            }
            try {
                hookCallCallbackForRequestWithBundle()
            } catch (e: Throwable) {
                HookErrorStore.w(SOURCE, "hookCallCallbackForRequestWithBundle failed: ${e.message}", e)
            }
        } else {
            try {
                hookCallCallbackForRequestWithAgent()
            } catch (e: Throwable) {
                HookErrorStore.w(SOURCE, "hookCallCallbackForRequestWithAgent failed: ${e.message}", e)
            }
        }
    }

    private fun hookCallCallbackForRequestWithAgent() {
        val (nriClass, naiClass) = helper.resolveNriAndNaiClasses()
        XposedApi.findAndHookMethod(
            helper.cls,
            "callCallbackForRequest",
            nriClass,
            naiClass,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val nri = param.args[0] ?: return
                    val uid = helper.getAsUid(nri)
                    VpnHideContext.setTargetUid(uid)
                    if (!VpnSanitizer.shouldHide(uid)) return
                    val networkAgent = param.args[1]
                    if (networkAgent != null && helper.isVpnNai(networkAgent)) {
                        val underlying = helper.getUnderlyingNai(param.thisObject, uid)
                        if (underlying != null) {
                            param.args[1] = underlying
                        }
                    }
                    
                }

                override fun afterHook(param: SafeMethodHook.HookParam) {
                    VpnHideContext.clear()
                }
            },
        )
    }

    private fun hookCallCallbackForRequestWithBundle() {
        val (nriClass, _) = helper.resolveNriAndNaiClasses()
        XposedApi.findAndHookMethod(
            helper.cls,
            "callCallbackForRequest",
            nriClass,
            Int::class.javaPrimitiveType,
            Bundle::class.java,
            Int::class.javaPrimitiveType,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val nri = param.args[0] ?: return
                    val uid = helper.getAsUid(nri)
                    VpnHideContext.setTargetUid(uid)
                    if (!VpnSanitizer.shouldHide(uid)) return
                }

                override fun afterHook(param: SafeMethodHook.HookParam) {
                    VpnHideContext.clear()
                }
            },
        )
    }

    private fun hookSendPendingIntentForRequest() {
        val (nriClass, naiClass) = helper.resolveNriAndNaiClasses()
        XposedApi.findAndHookMethod(
            helper.cls,
            "sendPendingIntentForRequest",
            nriClass,
            naiClass,
            Int::class.javaPrimitiveType,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val nri = param.args[0] ?: return
                    val uid = helper.getAsUid(nri)
                    VpnHideContext.setTargetUid(uid)
                    if (!VpnSanitizer.shouldHide(uid)) return
                    val networkAgent = param.args[1]
                    if (networkAgent != null && helper.isVpnNai(networkAgent)) {
                        val underlying = helper.getUnderlyingNai(param.thisObject, uid)
                        if (underlying != null) {
                            param.args[1] = underlying
                        }
                    }
                    
                }

                override fun afterHook(param: SafeMethodHook.HookParam) {
                    VpnHideContext.clear()
                }
            },
        )
    }

    // endregion
}
