package xyz.elouan.sobosubamodloader.signing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

object ApkSignerUtil {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "sobosuba-mod-loader"

    fun sign(context: Context, input: File, output: File) {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=Sobosuba Mod Loader"))
                    .setCertificateSerialNumber(java.math.BigInteger.ONE)
                    .setCertificateNotBefore(java.util.Date(System.currentTimeMillis() - 86_400_000))
                    .setCertificateNotAfter(java.util.Date(System.currentTimeMillis() + 31_536_000_000L * 20))
                    .build()
            )
            generator.generateKeyPair()
        }

        val privateKey = keyStore.getKey(ALIAS, null) as? PrivateKey
            ?: error("Unable to load signing key")
        val certificate = keyStore.getCertificate(ALIAS) as? X509Certificate
            ?: error("Unable to load signing certificate")
        val signer = ApkSigner.SignerConfig.Builder(
            "sobosuba-mod-loader",
            privateKey,
            listOf(certificate)
        ).build()

        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }
}
