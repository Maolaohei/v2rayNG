package com.v2ray.ang.xposed.hooks

import de.robv.android.xposed.XposedBridge
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runtime facade that prefers modern libxposed [XposedInterface] hooks, and only falls back
 * to legacy de.robv APIs for classic assets/xposed_init entry.
 */
object XposedApi {
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

    fun findAndHookMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any {
        require(args.isNotEmpty()) { "findAndHookMethod requires callback" }
        val callback = args.last() as? SafeMethodHook
            ?: throw IllegalArgumentException("callback must be SafeMethodHook")
        val paramTypes = args.dropLast(1).map { toClass(it) }.toTypedArray()
        val method = clazz.getDeclaredMethod(methodName, *paramTypes).apply { isAccessible = true }
        return hookExecutable(method, callback)
    }

    fun hookAllMethods(clazz: Class<*>, methodName: String, callback: SafeMethodHook): Set<Any> {
        val matched = (clazz.declaredMethods + clazz.methods)
            .filter { it.name == methodName }
            .distinctBy { methodKey(it) }
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

    fun invokeOriginalMethod(method: Any?, thisObject: Any?, args: Array<Any?>?): Any? {
        val executable = method as? Executable
            ?: throw IllegalArgumentException("method must be Executable")
        val modernXp = modern
        if (modernXp != null) {
            return when (executable) {
                is Method -> modernXp.getInvoker(executable).setType(XposedInterface.Invoker.Type.ORIGIN).invoke(thisObject, *(args ?: emptyArray()))
                is Constructor<*> -> {
                    // Constructors rarely need original invoke in our code paths.
                    executable.isAccessible = true
                    executable.newInstance(*(args ?: emptyArray()))
                }
                else -> throw IllegalArgumentException("Unsupported executable")
            }
        }
        return XposedBridge.invokeOriginalMethod(executable, thisObject, args)
    }

    private fun methodKey(method: Method): String {
        return method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name } + method.returnType.name
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
        val unhook = XposedBridge.hookMethod(executable, callback)
        handles.add(unhook)
        return unhook
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
