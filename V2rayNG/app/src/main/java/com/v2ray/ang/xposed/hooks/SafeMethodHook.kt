package com.v2ray.ang.xposed.hooks

import com.v2ray.ang.xposed.HookErrorStore
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
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
        // Copied lazily: most hooks never touch args, so avoid an unconditional
        // array copy on every intercepted call.
        private val mutableArgs: Array<Any?> by lazy { chain.args.toTypedArray() }
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

    /**
     * Reflective handles for one classic hook-param class.
     * [ClassicReflectParam] used to run getField/getMethod lookups on every
     * property access; those lookups are hot (every hooked call, several per
     * call) and never change, so resolve them once per class.
     */
    private class ClassicHandles(cls: Class<*>) {
        val method: Field? = runCatching { cls.getField("method") }.getOrNull()
        val thisObject: Field? = runCatching { cls.getField("thisObject") }.getOrNull()
        val getResult: Method? = runCatching { cls.getMethod("getResult") }.getOrNull()
        val setResult: Method? = runCatching { cls.getMethod("setResult", Any::class.java) }.getOrNull()
        val result: Field? = runCatching { cls.getField("result") }.getOrNull()
        val getThrowable: Method? = runCatching { cls.getMethod("getThrowable") }.getOrNull()
        val setThrowable: Method? = runCatching { cls.getMethod("setThrowable", Throwable::class.java) }.getOrNull()
        val throwable: Field? = runCatching { cls.getField("throwable") }.getOrNull()
        val returnEarly: Field? = runCatching { cls.getField("returnEarly") }.getOrNull()
        val args: Field? = runCatching { cls.getField("args") }.getOrNull()

        companion object {
            private val cache = ConcurrentHashMap<Class<*>, ClassicHandles>()

            fun of(cls: Class<*>): ClassicHandles = cache.getOrPut(cls) { ClassicHandles(cls) }
        }
    }

    private class ClassicReflectParam(private val raw: Any) : HookParam {
        private val h = ClassicHandles.of(raw.javaClass)

        override val method: Member?
            get() = h.method?.let { runCatching { it.get(raw) as? Member }.getOrNull() }
        override val thisObject: Any?
            get() = h.thisObject?.let { runCatching { it.get(raw) }.getOrNull() }
        override var result: Any?
            get() = h.getResult?.let { runCatching { it.invoke(raw) }.getOrNull() }
                ?: h.result?.let { runCatching { it.get(raw) }.getOrNull() }
            set(value) {
                val applied = h.setResult?.let { runCatching { it.invoke(raw, value) }.isSuccess } == true
                if (!applied) {
                    runCatching {
                        h.result?.set(raw, value)
                        h.returnEarly?.setBoolean(raw, true)
                    }
                }
            }
        override var throwable: Throwable?
            get() = h.getThrowable?.let { runCatching { it.invoke(raw) as? Throwable }.getOrNull() }
                ?: h.throwable?.let { runCatching { it.get(raw) as? Throwable }.getOrNull() }
            set(value) {
                val applied = h.setThrowable?.let { runCatching { it.invoke(raw, value) }.isSuccess } == true
                if (!applied) {
                    runCatching {
                        h.throwable?.set(raw, value)
                        if (value != null) {
                            h.returnEarly?.setBoolean(raw, true)
                        }
                    }
                }
            }
        override var returnEarly: Boolean
            get() = h.returnEarly?.let { runCatching { it.getBoolean(raw) }.getOrDefault(false) } ?: false
            set(value) {
                if (value) {
                    result = result
                } else {
                    runCatching { h.returnEarly?.setBoolean(raw, false) }
                }
            }
        @Suppress("UNCHECKED_CAST")
        override val args: Array<Any?>
            get() = h.args?.let { runCatching { it.get(raw) as? Array<Any?> }.getOrNull() } ?: emptyArray()

        @Suppress("UNCHECKED_CAST")
        override fun setArg(index: Int, value: Any?) {
            val arr = h.args?.let { runCatching { it.get(raw) as? Array<Any?> }.getOrNull() } ?: return
            arr[index] = value
        }
    }
}