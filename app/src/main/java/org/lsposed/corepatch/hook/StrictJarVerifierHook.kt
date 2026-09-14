package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper.hookAfter
import org.lsposed.corepatch.XposedHelper.hookBefore

object StrictJarVerifierHook : BaseHook() {
    override val name = "StrictJarVerifierHook"

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    override fun hook() {
        val strictJarVerifierClazz =
            findClassOrNull("android.util.jar.StrictJarVerifier") ?: return

        findMethodOrNull(strictJarVerifierClazz, "verifyMessageDigest") { m ->
            m.name == "verifyMessageDigest" && m.returnType == Boolean::class.java
        }?.let { method ->
            compat("hook verifyMessageDigest") {
                hookBefore(method) { callback ->
                    if (Config.isBypassVerificationEnabled()) callback.returnAndSkip(true)
                }
            }
        }

        findMethodOrNull(strictJarVerifierClazz, "verify") { m ->
            m.name == "verify" && m.returnType == Boolean::class.java
        }?.let { method ->
            compat("hook StrictJarVerifier.verify") {
                hookBefore(method) { callback ->
                    if (Config.isBypassVerificationEnabled()) callback.returnAndSkip(true)
                }
            }
        }

        val rollbackField = findFieldOrNull(
            strictJarVerifierClazz,
            "signatureSchemeRollbackProtectionsEnforced",
        ) { it.name == "signatureSchemeRollbackProtectionsEnforced" }
            ?.apply { isAccessible = true }
        val verifierConstructor = strictJarVerifierClazz.declaredConstructors.firstOrNull()
        if (rollbackField != null && verifierConstructor != null) {
            compat("hook StrictJarVerifier constructor") {
                hookAfter(verifierConstructor) { callback ->
                    if (Config.isBypassVerificationEnabled()) {
                        compat("disable signature scheme rollback protections") {
                            rollbackField.set(callback.thisObject, false)
                        }
                    }
                }
            }
        }

        // V1/JAR certificate fallback used by the digest-bypass path. Keep this block isolated:
        // libcore/private PKCS classes may change without affecting the other verifier hooks.
        compat("V1 certificate fallback") {
            val pkcs7Clazz = findClassOrNull("sun.security.pkcs.PKCS7") ?: return@compat
            val pkcs7Constructor = findConstructorOrNull(
                pkcs7Clazz,
                "byte[]",
            ) { c -> c.parameterTypes.contentEquals(arrayOf(ByteArray::class.java)) }
                ?: return@compat
            val getSignerInfosMethod = findMethodOrNull(pkcs7Clazz, "getSignerInfos") { m ->
                m.name == "getSignerInfos" && m.parameterCount == 0
            } ?: return@compat

            val signerInfoClazz = findClassOrNull("sun.security.pkcs.SignerInfo") ?: return@compat
            val getCertificateChainMethod = findMethodOrNull(
                signerInfoClazz,
                "getCertificateChain(PKCS7)",
            ) { m ->
                m.name == "getCertificateChain" &&
                    m.parameterCount == 1 &&
                    m.parameterTypes[0] == pkcs7Clazz
            } ?: return@compat

            val verifyBytesMethod = findMethodOrNull(
                strictJarVerifierClazz,
                "verifyBytes(byte[], byte[])",
            ) { m ->
                m.name == "verifyBytes" &&
                    m.parameterTypes.contentEquals(
                        arrayOf(ByteArray::class.java, ByteArray::class.java)
                    )
            } ?: return@compat

            hookAfter(verifyBytesMethod) { callback ->
                if (!Config.isBypassDigestEnabled() || Config.isUsePreviousSignaturesEnabled()) {
                    return@hookAfter
                }
                compat("recover V1 signer certificate chain") {
                    val block = pkcs7Constructor.newInstance(callback.args[0])
                    val signerInfo = getSignerInfosMethod.invoke(block) as? Array<*>
                        ?: return@compat
                    if (signerInfo.isEmpty()) return@compat
                    val signer = signerInfo[0] ?: return@compat
                    val certs = getCertificateChainMethod.invoke(signer, block)
                    callback.result = certs
                    callback.throwable = null
                }
            }
        }
    }
}
