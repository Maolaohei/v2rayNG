package com.v2ray.ang.xposed.hooks

import de.robv.android.xposed.XC_MethodHook
import io.github.libxposed.api.XposedInterface
import com.v2ray.ang.xposed.HookErrorStore
import java.lang.reflect.Member

/**
 * Unified before/after hook used by both modern libxposed and classic Xposed.
 */
abstract class SafeMethodHook(private val source: String) : XC_MethodHook() {
    @Volatile
    private var disabled = false

    final override fun beforeHookedMethod(param: MethodHookParam) {
        if (disabled) return
        try {
            beforeHook(LegacyParam(param))
        } catch (e: Throwable) {
            disabled = true
            HookErrorStore.e(source, "Hook disabled due to unrecoverable error", e)
        }
    }

    final override fun afterHookedMethod(param: MethodHookParam) {
        if (disabled) return
        try {
            afterHook(LegacyParam(param))
        } catch (e: Throwable) {
            disabled = true
            HookErrorStore.e(source, "Hook disabled due to unrecoverable error", e)
        }
    }

    fun interceptModern(chain: XposedInterface.Chain): Any? {
        if (disabled) return chain.proceed()
        val param = ModernParam(chain)
        try {
            beforeHook(param)
            if (param.returnEarly) {
                return param.result
            }
            try {
                param.result = if (param.argsOverride != null) {
                    chain.proceed(param.argsOverride!!)
                } else {
                    chain.proceed()
                }
            } catch (t: Throwable) {
                param.throwable = t
            }
            afterHook(param)
            param.throwable?.let { throw it }
            return param.result
        } catch (e: Throwable) {
            disabled = true
            HookErrorStore.e(source, "Hook disabled due to unrecoverable error", e)
            return chain.proceed()
        }
    }

    protected open fun beforeHook(param: HookParam) {}
    protected open fun afterHook(param: HookParam) {}

    // Keep old method names for existing subclasses that still override beforeHook/afterHook(MethodHookParam)
    @Deprecated("Use HookParam overloads")
    protected open fun beforeHook(param: MethodHookParam) {
        beforeHook(LegacyParam(param))
    }

    @Deprecated("Use HookParam overloads")
    protected open fun afterHook(param: MethodHookParam) {
        afterHook(LegacyParam(param))
    }

    interface HookParam {
        val method: Member?
        val thisObject: Any?
        var result: Any?
        var throwable: Throwable?
        var returnEarly: Boolean
        val args: Array<Any?>
        fun setArg(index: Int, value: Any?)
    }

    private class LegacyParam(private val raw: MethodHookParam) : HookParam {
        override val method: Member? get() = raw.method
        override val thisObject: Any? get() = raw.thisObject
        override var result: Any?
            get() = raw.result
            set(value) {
                raw.result = value
            }
        override var throwable: Throwable?
            get() = raw.throwable
            set(value) {
                raw.throwable = value
            }
        override var returnEarly: Boolean
            get() = raw.returnEarly
            set(value) {
                if (value) {
                    // Assigning result marks returnEarly=true in XC_MethodHook.
                    raw.result = raw.result
                }
            }
        override val args: Array<Any?> get() = raw.args ?: emptyArray()
        override fun setArg(index: Int, value: Any?) {
            raw.args[index] = value
        }
    }

    private class ModernParam(private val chain: XposedInterface.Chain) : HookParam {
        override val method: Member? get() = chain.executable
        override val thisObject: Any? get() = chain.thisObject
        override var result: Any? = null
        override var throwable: Throwable? = null
        override var returnEarly: Boolean = false
        private val mutableArgs: Array<Any?> = chain.args.toTypedArray()
        var argsOverride: Array<Any?>? = null
            private set
        override val args: Array<Any?> get() = mutableArgs
        override fun setArg(index: Int, value: Any?) {
            mutableArgs[index] = value
            argsOverride = mutableArgs
        }
    }
}
