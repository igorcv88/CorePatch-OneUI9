package org.lsposed.corepatch.hook

import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper

object MessageDigestHook : BaseHook() {
    override val name = "MessageDigestHook"

    override fun hook() {
        val messageDigestClazz =
            findClassOrNull("java.security.MessageDigest") ?: return

        val isEqualMethod = findMethodOrNull(
            messageDigestClazz,
            "isEqual(byte[], byte[])",
        ) { m ->
            m.name == "isEqual" &&
                m.parameterTypes.contentEquals(
                    arrayOf(ByteArray::class.java, ByteArray::class.java)
                )
        } ?: return

        compat("hook MessageDigest.isEqual") {
            XposedHelper.hookBefore(isEqualMethod) { callback ->
                if (Config.isBypassVerificationEnabled()) {
                    callback.returnAndSkip(true)
                }
            }
        }
    }
}
