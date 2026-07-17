package com.v2ray.ang.xposed.hooks

import com.v2ray.ang.xposed.HookErrorStore
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Member
import java.util.concurrent.atomic.AtomicInteger

/**
 * Framework-neutral before/after hook.
 * Modern libxposed path uses [interceptModern]; classic path adapts via reflection.
 * Do NOT extend de.robv.android.xposed.XC_MethodHook (forbidden for targetApi 102).
 */
abstract class SafeMethodHook(private val source: String) {
    private val failCount = AtomicInteger(0)

    fun interceptModern(chain: XposedInterface.Chain): Any? {
        val param = ModernParam(chain)
        try {
            beforeHook(param)
            if (!param.returnEarly) {
                try {
                    param.result = if (param.argsOverride != null) {
                        chain.proceed(param.argsOverride!!)
                    } else {
                        chain.proceed()
                    }
                    // proceed() assignment flips returnEarly; clear so afterHook is free to override.
                    param.returnEarly = false
                } catch (t: Throwable) {
                    param.throwable = t
                    param.returnEarly = false
                }
            }
            try {
                afterHook(param)
            } catch (e: Throwable) {
                val n = failCount.incrementAndGet()
                HookErrorStore.e(source, "afterHook error #$n (kept original/proceed result)", e)
                // Never permanently disable: one bad call must not kill hide forever.
            }
            param.throwable?.let { throw it }
            return param.result
        } catch (e: Throwable) {
            val n = failCount.incrementAndGet()
            HookErrorStore.e(source, "intercept error #$n, fall through to original", e)
            return chain.proceed()
        }
    }

    fun classicBefore(raw: Any) {
        try {
            beforeHook(ClassicReflectParam(raw))
        } catch (e: Throwable) {
            val n = failCount.incrementAndGet()
            HookErrorStore.e(source, "classic beforeHook error #$n", e)
        }
    }

    fun classicAfter(raw: Any) {
        try {
            afterHook(ClassicReflectParam(raw))
        } catch (e: Throwable) {
            val n = failCount.incrementAndGet()
            HookErrorStore.e(source, "classic afterHook error #$n", e)
        }
    }

    protected open fun beforeHook(param: HookParam) {}
    protected open fun afterHook(param: HookParam) {}

    interface HookParam {
        val method: Member?
        val thisObject: Any?
        var result: Any?
        var throwable: Throwable?
        var returnEarly: Boolean
        val args: Array<Any?>
        fun setArg(index: Int, value: Any?)
    }

    private class ModernParam(private val chain: XposedInterface.Chain) : HookParam {
        override val method: Member? get() = chain.executable
        override val thisObject: Any? get() = chain.thisObject
        private var resultValue: Any? = null
        override var result: Any?
            get() = resultValue
            set(value) {
                resultValue = value
                throwableValue = null
                returnEarly = true
            }
        private var throwableValue: Throwable? = null
        override var throwable: Throwable?
            get() = throwableValue
            set(value) {
                throwableValue = value
                if (value != null) {
                    returnEarly = true
                }
            }
        override var returnEarly: Boolean = false
        private val mutableArgs: Array<Any?> = chain.args.toTypedArray()
        var argsOverride: Array<Any?>? = null
            private set
        override val args: Array<Any?>
            get() {
                argsOverride = mutableArgs
                return mutableArgs
            }
        override fun setArg(index: Int, value: Any?) {
            mutableArgs[index] = value
            argsOverride = mutableArgs
        }
    }

    private class ClassicReflectParam(private val raw: Any) : HookParam {
        private val cls = raw.javaClass

        override val method: Member?
            get() = runCatching { cls.getField("method").get(raw) as? Member }.getOrNull()
        override val thisObject: Any?
            get() = runCatching { cls.getField("thisObject").get(raw) }.getOrNull()
        override var result: Any?
            get() = runCatching { cls.getMethod("getResult").invoke(raw) }.getOrNull()
                ?: runCatching { cls.getField("result").get(raw) }.getOrNull()
            set(value) {
                runCatching {
                    cls.getMethod("setResult", Any::class.java).invoke(raw, value)
                }.recoverCatching {
                    cls.getField("result").set(raw, value)
                    runCatching { cls.getField("returnEarly").setBoolean(raw, true) }
                }
            }
        override var throwable: Throwable?
            get() = runCatching { cls.getMethod("getThrowable").invoke(raw) as? Throwable }.getOrNull()
                ?: runCatching { cls.getField("throwable").get(raw) as? Throwable }.getOrNull()
            set(value) {
                runCatching {
                    cls.getMethod("setThrowable", Throwable::class.java).invoke(raw, value)
                }.recoverCatching {
                    cls.getField("throwable").set(raw, value)
                    if (value != null) {
                        runCatching { cls.getField("returnEarly").setBoolean(raw, true) }
                    }
                }
            }
        override var returnEarly: Boolean
            get() = runCatching { cls.getField("returnEarly").getBoolean(raw) }.getOrDefault(false)
            set(value) {
                if (value) {
                    result = result
                } else {
                    runCatching { cls.getField("returnEarly").setBoolean(raw, false) }
                }
            }
        override val args: Array<Any?>
            get() {
                @Suppress("UNCHECKED_CAST")
                return (runCatching { cls.getField("args").get(raw) as? Array<Any?> }.getOrNull())
                    ?: emptyArray()
            }

        override fun setArg(index: Int, value: Any?) {
            val arr = runCatching { cls.getField("args").get(raw) as? Array<Any?> }.getOrNull() ?: return
            arr[index] = value
        }
    }
}