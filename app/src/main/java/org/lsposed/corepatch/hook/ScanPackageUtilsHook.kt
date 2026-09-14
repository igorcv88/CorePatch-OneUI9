package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.os.Build
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper.hookBefore
import org.lsposed.corepatch.XposedHelper.log

object ScanPackageUtilsHook : BaseHook() {
    override val name = "ScanPackageUtilsHook"

    @SuppressLint("PrivateApi")
    override fun hook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val scanPackageUtilsClazz =
            findClassOrNull("com.android.server.pm.ScanPackageUtils") ?: return

        // Samsung's Android 17 / One UI 9 build removes or inlines this AOSP helper. This is an
        // expected framework variant, not an error. The minimum-scheme bypass remains covered by
        // ApkSignatureVerifierHook#getMinimumSignatureSchemeVersionForTargetSdk.
        val assertMinSignatureSchemeIsValidMethod = scanPackageUtilsClazz.declaredMethods
            .firstOrNull { m -> m.name == "assertMinSignatureSchemeIsValid" }
        if (assertMinSignatureSchemeIsValidMethod == null) {
            log("[$name] assertMinSignatureSchemeIsValid absent/inlined; skipping")
            return
        }

        compat("hook assertMinSignatureSchemeIsValid") {
            hookBefore(assertMinSignatureSchemeIsValidMethod) { callback ->
                if (Config.isBypassVerificationEnabled()) {
                    callback.returnAndSkip(null)
                }
            }
        }
    }
}
