package dev.tvapkpush.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.tvapkpush.adb.AdbAuth
import dev.tvapkpush.adb.AdbSigner
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.security.auth.x500.X500Principal

class IdentityStore : AdbSigner {
    private val alias = "tvapkpush.adb.identity.v1"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setKeySize(2048)
                        .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setCertificateSubject(X500Principal("CN=APK Push"))
                        .setCertificateSerialNumber(BigInteger.ONE)
                        .setCertificateNotBefore(Date(0))
                        .setCertificateNotAfter(Date(4_102_444_800_000L))
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
            }.generateKeyPair()
        }
    }

    override fun sign(token: ByteArray): ByteArray = Signature.getInstance("NONEwithRSA").run {
        initSign(store.getKey(alias, null) as PrivateKey)
        update(AdbAuth.digestInfo(token))
        sign()
    }

    override fun publicKey(): ByteArray = AdbAuth.publicKey(store.getCertificate(alias).publicKey as RSAPublicKey)
}
