package org.lsposed.corepatch.hook

import org.lsposed.corepatch.XposedHelper
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

open class BaseHook {
    open val name = "BaseHook"

    private var inited = false

    open fun hook() {

    }

    /**
     * Run an individual compatibility hook without allowing one missing/changed Samsung or
     * Android framework member to abort the rest of this hook group.
     */
    protected fun <T> compat(label: String, block: () -> T): T? {
        return try {
            block()
        } catch (t: Throwable) {
            XposedHelper.log("[$name] $label skipped on this framework", t)
            null
        }
    }

    protected fun findClassOrNull(className: String): Class<*>? =
        compat("class $className") {
            XposedHelper.hostClassLoader.loadClass(className)
        }

    protected fun findMethodOrNull(
        clazz: Class<*>,
        description: String,
        predicate: (Method) -> Boolean,
    ): Method? = compat("method $description") {
        clazz.declaredMethods.firstOrNull(predicate)
            ?: throw NoSuchMethodException("${clazz.name}#$description")
    }

    protected fun findFieldOrNull(
        clazz: Class<*>,
        description: String,
        predicate: (Field) -> Boolean,
    ): Field? = compat("field $description") {
        clazz.declaredFields.firstOrNull(predicate)
            ?: throw NoSuchFieldException("${clazz.name}#$description")
    }

    protected fun findConstructorOrNull(
        clazz: Class<*>,
        description: String,
        predicate: (Constructor<*>) -> Boolean,
    ): Constructor<*>? = compat("constructor $description") {
        clazz.declaredConstructors.firstOrNull(predicate)
            ?: throw NoSuchMethodException("${clazz.name}($description)")
    }

    private fun hookInternal() {
        try {
            hook()
        } catch (t: Throwable) {
            XposedHelper.log("[$name] hook failed", t)
        }
    }

    fun init() {
        if (inited) return
        inited = true
        XposedHelper.log("[$name] init: $name")
        hookInternal()
        XposedHelper.log("[$name] init: $name done")
    }
}
