package org.lsposed.corepatch.hook

import android.os.Build
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper
import org.lsposed.corepatch.XposedHelper.hookAfter

/**
 * Samsung One UI 9 ASKS/AASA compatibility hook.
 *
 * ASKSManagerService.verifyToken(...) uses 0 as its local success value. Its caller,
 * verifyASKStokenForPackage(...), translates non-zero token failures into package-install
 * error codes before PackageManager rejects the install. Keep the original verifier running
 * so its parsing/session side effects are preserved, then normalize only the result when the
 * existing "bypass block" option is enabled.
 *
 * Deliberately scoped to Samsung Android 17. The separate ASKS blacklist/protection checks and
 * the later ADP rollback/downgrade policy remain untouched by this hook.
 */
object SamsungAsksHook : BaseHook() {
    override val name = "SamsungAsksHook"

    private const val ANDROID_17_SDK = 37
    private const val ASKS_MANAGER_SERVICE = "com.android.server.asks.ASKSManagerService"
    private const val ASKS_SESSION =
        "com.android.server.asks.ASKSManagerService\$ASKSSession"

    override fun hook() {
        if (Build.VERSION.SDK_INT != ANDROID_17_SDK) return
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return

        val asksManagerServiceClass = findClassOrNull(ASKS_MANAGER_SERVICE) ?: return
        val verifyTokenMethod = findMethodOrNull(
            asksManagerServiceClass,
            "verifyToken(ASKSSession,String,String,boolean,int)",
        ) { method ->
            method.name == "verifyToken" &&
                method.returnType == Int::class.javaPrimitiveType &&
                method.parameterCount == 5 &&
                method.parameterTypes[0].name == ASKS_SESSION &&
                method.parameterTypes[1] == String::class.java &&
                method.parameterTypes[2] == String::class.java &&
                method.parameterTypes[3] == Boolean::class.javaPrimitiveType &&
                method.parameterTypes[4] == Int::class.javaPrimitiveType
        } ?: return

        hookAfter(verifyTokenMethod) { callback ->
            if (!Config.isBypassBlockEnabled()) return@hookAfter

            // Preserve exceptional behavior. The caller has its own IOException handling and
            // this hook should only alter an actual verifier return value.
            if (callback.throwable != null) return@hookAfter

            val originalResult = callback.result as? Int ?: return@hookAfter
            if (originalResult == 0) return@hookAfter

            callback.result = 0
            XposedHelper.log(
                "[$name] normalized ASKS/AASA verifyToken result $originalResult to 0"
            )
        }
    }
}
