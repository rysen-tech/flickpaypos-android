package uk.flickpay.flickpaypos

import android.content.Context
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.TrustManagerFactory

object LocalTls {

    fun createServerSocketFactory(
        context: Context,
        p12ResId: Int = R.raw.local_proxy,
        password: String = "changeit"
    ): SSLServerSocketFactory {
        val pass = password.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        context.resources.openRawResource(p12ResId).use { input ->
            keyStore.load(input, pass)
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, pass)

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)

        val ssl = SSLContext.getInstance("TLS")
        ssl.init(kmf.keyManagers, tmf.trustManagers, null)
        return ssl.serverSocketFactory
    }
}
