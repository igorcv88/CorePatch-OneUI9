package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper.hookBefore

object ApkSigningBlockUtilsHook : BaseHook() {
    override val name = "ApkSigningBlockUtilsHook"

    @SuppressLint("PrivateApi")
    override fun hook() {
        val apkSigningBlockUtilsClazz =
            findClassOrNull("android.util.apk.ApkSigningBlockUtils") ?: return

        findMethodOrNull(
            apkSigningBlockUtilsClazz,
            "parseVerityDigestAndVerifySourceLength",
        ) { m -> m.name == "parseVerityDigestAndVerifySourceLength" }
            ?.let { method ->
                compat("hook parseVerityDigestAndVerifySourceLength") {
                    hookBefore(method) { callback ->
                        if (Config.isBypassVerificationEnabled()) {
                            val digest = callback.args.getOrNull(0) as? ByteArray
                                ?: return@hookBefore
                            if (digest.size >= 32) {
                                callback.returnAndSkip(digest.copyOfRange(0, 32))
                            }
                        }
                    }
                }
            }

        findMethodOrNull(
            apkSigningBlockUtilsClazz,
            "verifyIntegrityForVerityBasedAlgorithm",
        ) { m -> m.name == "verifyIntegrityForVerityBasedAlgorithm" }
            ?.let { method ->
                compat("hook verifyIntegrityForVerityBasedAlgorithm") {
                    hookBefore(method) { callback ->
                        if (Config.isBypassVerificationEnabled()) {
                            callback.returnAndSkip(null)
                        }
                    }
                }
            }
    }
}
