package com.v2ray.ang.xposed.hooks

import com.v2ray.ang.xposed.HookErrorStore
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runtime facade for hidevpn hooks.
 *
 * Modern path (targetApi 102): only [XposedInterface], never calls de.robv APIs.
 * Classic path (assets/xposed_init): uses [ClassicSafeMethodHookAdapter] + XposedBridge
 * only when [modern] is null.
 */
object XposedApi {
    private const val TAG = "XposedApi"

    @Volatile
    var modern: XposedInterface? = null
        private set

    private val handles = CopyOnWriteArrayList<Any>()

    fun attachModern(xp: XposedInterface) {
        modern = xp
    }

    fun isModern(): Boolean = modern != null

    fun findClass(name: String, classLoader: ClassLoader?): Class<*> {
        return if (classLoader != null) {
            Class.forName(name, false, classLoader)
        } else {
            Class.forName(name)
        }
    }

    fun findClassIfExists(name: String, classLoader: ClassLoader?): Class<*>? = try {
        findClass(name, classLoader)
    } catch (_: Throwable) {
        null
    }

    fun findField(clazz: Class<*>, name: String): java.lang.reflect.Field {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw NoSuchFieldException("${clazz.name}.$name")
    }

    fun findFieldIfExists(clazz: Class<*>, name: String): java.lang.reflect.Field? = try {
        findField(clazz, name)
    } catch (_: Throwable) {
        null
    }

    fun getStaticObjectField(clazz: Class<*>, name: String): Any? = findField(clazz, name).get(null)

    fun getObjectField(obj: Any, name: String): Any? = findField(obj.javaClass, name).get(obj)

    fun setObjectField(obj: Any, name: String, value: Any?) {
        findField(obj.javaClass, name).set(obj, value)
    }

    fun getIntField(obj: Any, name: String): Int = findField(obj.javaClass, name).getInt(obj)

    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        requireNotNull(obj) { "callMethod thisObject is null" }
        val method = findBestMethod(obj.javaClass, methodName, args)
            ?: throw NoSuchMethodException("${obj.javaClass.name}.$methodName(${args.size} args)")
        method.isAccessible = true
        return method.invoke(obj, *args)
    }

    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        val method = findBestMethod(clazz, methodName, args)
            ?: throw NoSuchMethodException("${clazz.name}.$methodName(${args.size} args)")
        method.isAccessible = true
        return method.invoke(null, *args)
    }

    fun findAndHookMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any {
        require(args.isNotEmpty()) { "findAndHookMethod requires callback" }
        val callback = args.last() as? SafeMethodHook
            ?: throw IllegalArgumentException("callback must be SafeMethodHook")
        val paramTypes = args.dropLast(1).map { toClass(it) }.toTypedArray()
        val method = findDeclaredMethodInHierarchy(clazz, methodName, paramTypes)
            ?: throw NoSuchMethodException(
                "${clazz.name}.$methodName" +
                    paramTypes.joinToString(prefix = "(", postfix = ")") { it.name },
            )
        method.isAccessible = true
        return hookExecutable(method, callback)
    }

    fun findAndHookConstructor(clazz: Class<*>, vararg args: Any?): Any {
        require(args.isNotEmpty()) { "findAndHookConstructor requires callback" }
        val callback = args.last() as? SafeMethodHook
            ?: throw IllegalArgumentException("callback must be SafeMethodHook")
        val paramTypes = args.dropLast(1).map { toClass(it) }.toTypedArray()
        val ctor: Constructor<*> = clazz.getDeclaredConstructor(*paramTypes).apply { isAccessible = true }
        return hookExecutable(ctor, callback)
    }

    fun hookAllMethods(clazz: Class<*>, methodName: String, callback: SafeMethodHook): Set<Any> {
        val matched = LinkedHashSet<Method>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            c.declaredMethods.filterTo(matched) { it.name == methodName }
            c = c.superclass
        }
        if (matched.isEmpty()) return emptySet()
        return matched.mapTo(LinkedHashSet()) { method ->
            method.isAccessible = true
            hookExecutable(method, callback)
        }
    }

    fun hookMethod(method: Method, callback: SafeMethodHook): Any {
        method.isAccessible = true
        return hookExecutable(method, callback)
    }

    fun unhook(handle: Any?) {
        if (handle == null) return
        try {
            when (handle) {
                is XposedInterface.HookHandle -> handle.unhook()
                else -> {
                    val m = handle.javaClass.methods.firstOrNull {
                        it.name == "unhook" && it.parameterTypes.isEmpty()
                    } ?: return
                    m.isAccessible = true
                    m.invoke(handle)
                }
            }
        } catch (e: Throwable) {
            HookErrorStore.w(TAG, "unhook failed: ${e.message}", e)
        } finally {
            handles.remove(handle)
        }
    }

    fun invokeOriginalMethod(method: Any?, thisObject: Any?, args: Array<Any?>?): Any? {
        val executable = method as? Executable
            ?: throw IllegalArgumentException("method must be Executable")
        val modernXp = modern
        if (modernXp != null) {
            return when (executable) {
                is Method -> {
                    val invoker = modernXp.getInvoker(executable)
                    invoker.setType(XposedInterface.Invoker.Type.ORIGIN)
                    invoker.invoke(thisObject, *(args ?: emptyArray()))
                }
                is Constructor<*> -> {
                    executable.isAccessible = true
                    executable.newInstance(*(args ?: emptyArray()))
                }
                else -> throw IllegalArgumentException("Unsupported executable")
            }
        }
        return classicInvokeOriginal(executable, thisObject, args)
    }

    private fun findBestMethod(clazz: Class<*>, name: String, args: Array<out Any?>): Method? {
        val candidates = ArrayList<Method>()
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredMethods.filterTo(candidates) { it.name == name && it.parameterTypes.size == args.size }
            c = c.superclass
        }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        return candidates.firstOrNull { method ->
            method.parameterTypes.indices.all { i ->
                val expected = method.parameterTypes[i]
                val actual = args[i]
                if (actual == null) {
                    !expected.isPrimitive
                } else {
                    boxType(expected).isAssignableFrom(actual.javaClass)
                }
            }
        } ?: candidates.first()
    }

    private fun boxType(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> type
    }

    private fun findDeclaredMethodInHierarchy(
        clazz: Class<*>,
        name: String,
        paramTypes: Array<Class<*>>,
    ): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, *paramTypes)
            } catch (_: NoSuchMethodException) {
                c = c.superclass
            }
        }
        return try {
            clazz.getMethod(name, *paramTypes)
        } catch (_: NoSuchMethodException) {
            null
        }
    }

    private fun hookExecutable(executable: Executable, callback: SafeMethodHook): Any {
        val modernXp = modern
        if (modernXp != null) {
            val handle = modernXp.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(XposedInterface.Hooker { chain -> callback.interceptModern(chain) })
            handles.add(handle)
            return handle
        }
        val unhook = classicHookMethod(executable, callback)
        handles.add(unhook)
        return unhook
    }

    private fun classicHookMethod(executable: Executable, callback: SafeMethodHook): Any {
        val bridge = Class.forName("de.robv.android.xposed.XposedBridge")
        val hookMethod = bridge.getMethod(
            "hookMethod",
            java.lang.reflect.Member::class.java,
            Class.forName("de.robv.android.xposed.XC_MethodHook"),
        )
        val adapter = ClassicSafeMethodHookAdapter(callback)
        return hookMethod.invoke(null, executable, adapter)
            ?: error("XposedBridge.hookMethod returned null")
    }

    private fun classicInvokeOriginal(
        executable: Executable,
        thisObject: Any?,
        args: Array<Any?>?,
    ): Any? {
        val bridge = Class.forName("de.robv.android.xposed.XposedBridge")
        val invokeOriginal = bridge.getMethod(
            "invokeOriginalMethod",
            java.lang.reflect.Member::class.java,
            Any::class.java,
            Array<Any>::class.java,
        )
        return invokeOriginal.invoke(null, executable, thisObject, args)
    }

    private fun toClass(type: Any?): Class<*> {
        return when (type) {
            is Class<*> -> type
            is String -> Class.forName(type)
            null -> throw IllegalArgumentException("null parameter type")
            else -> throw IllegalArgumentException("Unsupported parameter type marker: ${type.javaClass.name}")
        }
    }
}