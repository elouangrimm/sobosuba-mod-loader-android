package xyz.elouan.sobosubamodloader.signing

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

object ApkSignerUtil {
    private const val KEYSTORE_ASSET = "modloader.jks"
    private const val KEYSTORE_PASSWORD = "sobosuba123"
    private const val ALIAS = "modloader"

    fun sign(context: Context, input: File, output: File) {
        val keyStore = KeyStore.getInstance("PKCS12")
        context.assets.open(KEYSTORE_ASSET).use { stream ->
            keyStore.load(stream, KEYSTORE_PASSWORD.toCharArray())
        }

        val privateKey = keyStore.getKey(ALIAS, KEYSTORE_PASSWORD.toCharArray()) as PrivateKey
        val certificate = keyStore.getCertificate(ALIAS) as X509Certificate

        val signerConfig = ApkSigner.SignerConfig.Builder(
            ALIAS,
            privateKey,
            listOf(certificate)
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }
}
