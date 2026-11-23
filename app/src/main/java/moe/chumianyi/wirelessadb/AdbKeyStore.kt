package moe.chumianyi.wirelessadb

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID

/**
 * 本机 ADB 客户端身份（RSA 私钥 + 自签名证书）。
 *
 * 无线调试配对时，密钥会写入目标机的信任列表；之后连接只需要这对密钥做 TLS 客户端认证。
 */
object AdbKeyStore {

    private const val FILE_NAME = "adb_identity.p12"
    private const val ALIAS = "adb-client"
    private const val PASSWORD = "wireless-adb-local"

    class Identity(val privateKey: PrivateKey, val certificate: X509Certificate)

    @Volatile
    private var cached: Identity? = null

    @Synchronized
    fun identity(context: Context): Identity {
        cached?.let { return it }
        val file = File(context.filesDir, FILE_NAME)
        var identity = read(file)
        if (identity == null) {
            identity = generate()
            write(file, identity)
        }
        cached = identity
        return identity
    }

    private fun read(file: File): Identity? {
        if (!file.exists()) return null
        return try {
            FileInputStream(file).use { input ->
                val store = KeyStore.getInstance("PKCS12")
                store.load(input, PASSWORD.toCharArray())
                val key = store.getKey(ALIAS, PASSWORD.toCharArray()) as? PrivateKey
                val certificate = store.getCertificate(ALIAS) as? X509Certificate
                if (key != null && certificate != null) Identity(key, certificate) else null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun write(file: File, identity: Identity) {
        try {
            FileOutputStream(file).use { output ->
                val store = KeyStore.getInstance("PKCS12")
                store.load(null, null)
                store.setKeyEntry(
                    ALIAS,
                    identity.privateKey,
                    PASSWORD.toCharArray(),
                    arrayOf(identity.certificate)
                )
                store.store(output, PASSWORD.toCharArray())
            }
        } catch (t: Throwable) {
            file.delete()
        }
    }

    private fun generate(): Identity {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keyPair = generator.generateKeyPair()
        val suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8)
        val name = X500Name("CN=wadb-$suffix,O=WirelessAdb")
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 60_000L)
        val notAfter = Date(now + 3650L * 24L * 60L * 60L * 1000L)
        val serial = BigInteger.valueOf(now)
        val builder = JcaX509v3CertificateBuilder(
            name, serial, notBefore, notAfter, name, keyPair.public
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        val signer = JcaContentSignerBuilder("SHA256WITHRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return Identity(keyPair.private, certificate)
    }
}
