package app.loadout.debug

import android.content.Context
import android.os.Build
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * Loadout's identity for wireless debugging. Android remembers the key once the user has
 * paired, so the key and its certificate are created once and kept in Loadout's private files.
 */
class LoadoutAdb private constructor(context: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(10, TimeUnit.SECONDS)
        val keyFile = File(context.filesDir, KEY_FILE)
        val certFile = File(context.filesDir, CERT_FILE)
        var loadedKey: PrivateKey? = null
        var loadedCert: Certificate? = null
        if (keyFile.isFile && certFile.isFile) {
            try {
                loadedKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                loadedCert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            } catch (_: Exception) {
                // Unreadable files mean a new key, and the user pairs again.
                loadedKey = null
                loadedCert = null
            }
        }
        if (loadedKey == null || loadedCert == null) {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
            val made = selfSigned(pair.private, pair.public)
            keyFile.writeBytes(pair.private.encoded)
            certFile.writeBytes(made.encoded)
            loadedKey = pair.private
            loadedCert = made
        }
        key = loadedKey
        certificate = loadedCert
    }

    override fun getPrivateKey(): PrivateKey = key

    override fun getCertificate(): Certificate = certificate

    override fun getDeviceName(): String = "Loadout"

    private fun selfSigned(privateKey: PrivateKey, publicKey: java.security.PublicKey): Certificate {
        val algorithm = "SHA512withRSA"
        val name = X500Name("CN=Loadout")
        val notBefore = Date()
        val notAfter = Date(notBefore.time + TEN_YEARS_MS)
        val extensions = CertificateExtensions()
        extensions.set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier))
        extensions.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        val info = X509CertInfo()
        info.set("version", CertificateVersion(2))
        info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
        info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
        info.set("subject", CertificateSubjectName(name))
        info.set("key", CertificateX509Key(publicKey))
        info.set("validity", CertificateValidity(notBefore, notAfter))
        info.set("issuer", CertificateIssuerName(name))
        info.set("extensions", extensions)
        return X509CertImpl(info).apply { sign(privateKey, algorithm) }
    }

    companion object {
        private const val KEY_FILE = "debug-link.key"
        private const val CERT_FILE = "debug-link.cert"
        private const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: LoadoutAdb? = null

        /** Creating the key takes a moment the first time, so call this off the main thread. */
        fun get(context: Context): LoadoutAdb =
            instance ?: synchronized(this) {
                instance ?: LoadoutAdb(context.applicationContext).also { instance = it }
            }
    }
}
