package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.os.Build
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper.hookBefore
import java.util.Arrays

object SigningDetailsHook : BaseHook() {
    override val name = "SigningDetailsHook"

    @SuppressLint("PrivateApi")
    override fun hook() {
        val signingDetailsClazz = findClassOrNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "android.content.pm.SigningDetails"
            } else {
                "android.content.pm.PackageParser\$SigningDetails"
            }
        ) ?: return

        findMethodOrNull(signingDetailsClazz, "checkCapability(SigningDetails, int)") { m ->
            m.name == "checkCapability" &&
                m.parameterCount == 2 &&
                m.parameterTypes[0] == signingDetailsClazz &&
                m.parameterTypes[1] == Int::class.javaPrimitiveType
        }?.let { method ->
            compat("hook checkCapability") {
                hookBefore(method) { callback ->
                    if (Config.isBypassDigestEnabled()) {
                        val capability = callback.args.getOrNull(1) as? Int ?: return@hookBefore
                        if (capability != 4 && capability != 16) callback.returnAndSkip(true)
                    }
                }
            }
        }

        findMethodOrNull(signingDetailsClazz, "checkCapabilityRecover(SigningDetails, int)") { m ->
            m.name == "checkCapabilityRecover" &&
                m.parameterCount == 2 &&
                m.parameterTypes[0] == signingDetailsClazz &&
                m.parameterTypes[1] == Int::class.javaPrimitiveType
        }?.let { method ->
            compat("hook checkCapabilityRecover") {
                hookBefore(method) { callback ->
                    if (Config.isBypassDigestEnabled()) {
                        val capability = callback.args.getOrNull(1) as? Int ?: return@hookBefore
                        if (capability != 4 && capability != 16) callback.returnAndSkip(true)
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            findMethodOrNull(signingDetailsClazz, "hasCommonAncestor(SigningDetails)") { m ->
                m.name == "hasCommonAncestor" &&
                    m.parameterCount == 1 &&
                    m.parameterTypes[0] == signingDetailsClazz
            }?.let { method ->
                compat("hook hasCommonAncestor") {
                    hookBefore(method) { callback ->
                        if (Config.isBypassDigestEnabled() && Config.isBypassSharedUserEnabled() &&
                            Arrays.stream(Thread.currentThread().stackTrace)
                                .anyMatch { o: StackTraceElement -> "verifySignatures" == o.methodName }
                        ) {
                            callback.returnAndSkip(true)
                        }
                    }
                }
            }
        }

        findMethodOrNull(signingDetailsClazz, "signaturesMatchExactly(SigningDetails)") { m ->
            m.name == "signaturesMatchExactly" &&
                m.parameterCount == 1 &&
                m.parameterTypes[0] == signingDetailsClazz
        }?.let { method ->
            compat("hook signaturesMatchExactly") {
                hookBefore(method) { callback ->
                    if (Config.isBypassExactSignatureMatch()) {
                        callback.returnAndSkip(true)
                    }
                }
            }
        }
    }
}
