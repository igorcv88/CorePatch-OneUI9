package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.os.Build
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper.hookBefore

object VerifyingSessionHook : BaseHook() {
    override val name = "VerifyingSessionHook"

    private const val INSTALL_DISABLE_VERIFICATION = 0x00080000

    @SuppressLint("PrivateApi")
    override fun hook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        val verifyingSessionClazz =
            findClassOrNull("com.android.server.pm.VerifyingSession") ?: return

        val installFlagsField = findFieldOrNull(
            verifyingSessionClazz,
            "mInstallFlags",
        ) { it.name == "mInstallFlags" }?.apply { isAccessible = true }

        findMethodOrNull(
            verifyingSessionClazz,
            "handleStartVerify",
        ) { m -> m.name == "handleStartVerify" }
            ?.let { handleStartVerifyMethod ->
                compat("hook handleStartVerify") {
                    hookBefore(handleStartVerifyMethod) { callback ->
                        if (!Config.isDisableVerificationAgentEnabled()) return@hookBefore
                        val field = installFlagsField ?: return@hookBefore
                        val session = callback.thisObject ?: return@hookBefore
                        compat("set INSTALL_DISABLE_VERIFICATION") {
                            field.setInt(session, field.getInt(session) or INSTALL_DISABLE_VERIFICATION)
                        }
                    }
                }
            }

        findMethodOrNull(
            verifyingSessionClazz,
            "isAdbVerificationEnabled",
        ) { m ->
            m.name == "isAdbVerificationEnabled" && m.returnType == Boolean::class.java
        }?.let { isAdbVerificationEnabledMethod ->
            compat("hook isAdbVerificationEnabled") {
                hookBefore(isAdbVerificationEnabledMethod) { callback ->
                    if (Config.isDisableVerificationAgentEnabled()) {
                        callback.returnAndSkip(false)
                    }
                }
            }
        }
    }
}
