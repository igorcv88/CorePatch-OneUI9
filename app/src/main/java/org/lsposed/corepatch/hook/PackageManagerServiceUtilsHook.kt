package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.os.Build
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper
import org.lsposed.corepatch.XposedHelper.hookBefore
import org.lsposed.corepatch.XposedHelper.log

object PackageManagerServiceUtilsHook : BaseHook() {
    override val name = "PackageManagerServiceUtilsHook"

    @SuppressLint("PrivateApi")
    override fun hook() {
        val packageManagerServiceUtilsClazz =
            findClassOrNull("com.android.server.pm.PackageManagerServiceUtils") ?: return

        // Signature-verification internals are frequently inlined or reshaped by OEM builds.
        // Resolve them independently so one missing member does not disable downgrade handling.
        findMethodOrNull(
            packageManagerServiceUtilsClazz,
            "verifySignatures",
        ) { m -> m.name == "verifySignatures" && m.returnType == Boolean::class.java }
            ?.let { verifySignaturesMethod ->
                compat("deoptimize verifySignatures") {
                    if (!XposedHelper.deoptimize(verifySignaturesMethod)) {
                        log("failed to deoptimize verifySignatures")
                    }
                }
                compat("hook verifySignatures") {
                    hookBefore(verifySignaturesMethod) { callback ->
                        if (Config.isBypassVerificationEnabled()) {
                            callback.returnAndSkip(false)
                        }
                    }
                }
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // One UI may inline the public overloads into a private/common implementation, so
            // hook every compatible checkDowngrade overload that ends in PackageInfoLite.
            val checkDowngradeMethods = packageManagerServiceUtilsClazz.declaredMethods
                .filter { it.name == "checkDowngrade" && it.returnType == Void.TYPE }
                .filter {
                    it.parameterTypes.lastOrNull()?.name ==
                        "android.content.pm.PackageInfoLite"
                }

            if (checkDowngradeMethods.isEmpty()) {
                compat<Unit>("checkDowngrade overloads") {
                    throw NoSuchMethodException(
                        "${packageManagerServiceUtilsClazz.name}#checkDowngrade(..., PackageInfoLite)"
                    )
                }
            } else {
                checkDowngradeMethods.forEach { checkDowngradeMethod ->
                    compat("hook checkDowngrade ${checkDowngradeMethod.parameterTypes.contentToString()}") {
                        hookBefore(checkDowngradeMethod) { callback ->
                            if (Config.isBypassDowngradeEnabled()) callback.returnAndSkip(null)
                        }
                    }
                }
            }

            // Used by shared-user signature reconciliation. It is an optimization only, so a
            // vendor build without this exact helper must not abort the rest of Core Patch.
            findMethodOrNull(
                packageManagerServiceUtilsClazz,
                "canJoinSharedUserId",
            ) { m -> m.name == "canJoinSharedUserId" }
                ?.let { canJoinSharedUserIdMethod ->
                    compat("deoptimize canJoinSharedUserId") {
                        if (!XposedHelper.deoptimize(canJoinSharedUserIdMethod)) {
                            log("failed to deoptimize canJoinSharedUserId")
                        }
                    }
                }
        }
    }
}
