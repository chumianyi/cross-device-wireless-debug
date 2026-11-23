package moe.chumianyi.wirelessadb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 用 NsdManager 找无线调试的 mDNS 服务。
 *
 * 无线调试会注册两个服务：
 *  - `_adb-tls-pairing._tcp`：配对端口（二维码里只带服务名和配对码，端口需要靠这里解析）
 *  - `_adb-tls-connect._tcp`：连接端口
 */
object MdnsFinder {

    const val PAIRING = "_adb-tls-pairing._tcp"
    const val CONNECT = "_adb-tls-connect._tcp"

    class Service(val name: String, val host: String, val port: Int)

    fun find(
        context: Context,
        serviceType: String,
        name: String?,
        host: String?,
        timeoutMillis: Long
    ): Service? {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        return Query(manager, serviceType, name, host).run(timeoutMillis)
    }

    private class Query(
        private val manager: NsdManager,
        private val serviceType: String,
        private val name: String?,
        private val host: String?
    ) : NsdManager.DiscoveryListener, NsdManager.ResolveListener {

        private val latch = CountDownLatch(1)
        private val result = AtomicReference<Service?>(null)
        private val fallback = AtomicReference<Service?>(null)
        private val done = AtomicBoolean(false)
        private val resolving = AtomicBoolean(false)
        private val handler = Handler(Looper.getMainLooper())

        fun run(timeoutMillis: Long): Service? {
            handler.post {
                try {
                    manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, this)
                } catch (t: Throwable) {
                    finish()
                }
            }
            try {
                latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            handler.post {
                try {
                    manager.stopServiceDiscovery(this)
                } catch (t: Throwable) {
                    // ignore
                }
            }
            return result.get() ?: fallback.get()
        }

        private fun finish(service: Service? = null) {
            if (!done.compareAndSet(false, true)) return
            if (service != null) result.set(service)
            latch.countDown()
        }

        override fun onDiscoveryStarted(serviceType: String?) {}

        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
            finish()
        }

        override fun onDiscoveryStopped(serviceType: String?) {}

        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}

        override fun onServiceFound(info: NsdServiceInfo?) {
            if (info == null || done.get()) return
            if (!resolving.compareAndSet(false, true)) return
            try {
                manager.resolveService(info, this)
            } catch (t: Throwable) {
                resolving.set(false)
            }
        }

        override fun onServiceLost(info: NsdServiceInfo?) {}

        override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
            resolving.set(false)
        }

        override fun onServiceResolved(info: NsdServiceInfo?) {
            resolving.set(false)
            if (info == null || done.get()) return
            val address = info.host ?: return
            val service = Service(
                info.serviceName ?: "",
                address.hostAddress ?: "",
                info.port
            )
            if (!matches(service)) return
            if (address is Inet4Address) {
                finish(service)
            } else {
                // IPv6 链路本地地址经常带 scope，先留作兜底，优先等 IPv4
                fallback.set(service)
            }
        }

        private fun matches(service: Service): Boolean {
            if (!name.isNullOrEmpty()) {
                if (!service.name.equals(name, true) && !service.name.contains(name, true)) {
                    return false
                }
            }
            if (!host.isNullOrEmpty() && service.host != host) {
                // 只按 IP 找（配对码流程里没有服务名可对）
                if (name.isNullOrEmpty()) return false
            }
            return true
        }
    }
}
