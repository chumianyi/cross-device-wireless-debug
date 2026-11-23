package moe.chumianyi.wirelessadb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStreamWriter
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

/**
 * 把 libadb 的连接管理器接上本机密钥，并补一层“等待目标机 adbd 起来”的重试。
 */
class WadbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val identity: AdbKeyStore.Identity = AdbKeyStore.identity(context)
    private val deviceName: String = buildString {
        append("wadb")
        val model = Build.MODEL ?: ""
        if (model.isNotBlank()) {
            append("-")
            append(model.replace(' ', '_').take(24))
        }
    }

    init {
        setTimeout(20, TimeUnit.SECONDS)
        setThrowOnUnauthorised(false)
    }

    protected override fun getPrivateKey(): PrivateKey = identity.privateKey

    protected override fun getCertificate(): Certificate = identity.certificate

    protected override fun getDeviceName(): String = deviceName

    /** 连接，最多重试 [attempts] 次（目标机刚开无线调试时 adbd 偶尔还没就绪）。 */
    fun connectWithRetry(host: String, port: Int, attempts: Int = 3): Boolean {
        for (index in 0 until attempts) {
            try {
                if (isConnected) return true
                if (connect(host, port)) return true
            } catch (e: AdbAuthenticationFailedException) {
                // 目标机没信任本机密钥，必须重新配对，重试没有意义
                throw e
            } catch (e: Exception) {
                if (index == attempts - 1) throw e
            }
            try {
                Thread.sleep(700)
            } catch (e: InterruptedException) {
                break
            }
        }
        return false
    }

    /**
     * 在目标机上跑一条 shell 命令。
     *
     * 走的是交互式 pty（`shell:`），因此管道、重定向、`&&`、中文路径都能原样交给目标机的 sh。
     * 等待期间用看门狗线程兜底：超时就关掉流，避免命令卡住时一直挂着。
     */
    fun runShell(command: String, timeoutMillis: Long): String {
        val stream = openStream("shell:")
        val token = "__WADB_END_" + System.nanoTime() + "__"
        val output = StringBuilder()
        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMillis)
            } catch (e: InterruptedException) {
                return@Thread
            }
            try {
                stream.close()
            } catch (t: Throwable) {
                // ignore
            }
        }
        watchdog.isDaemon = true
        watchdog.start()
        try {
            val writer = BufferedWriter(OutputStreamWriter(stream.openOutputStream(), "UTF-8"))
            writer.write("stty -echo 2>/dev/null\n")
            writer.write(command)
            writer.write("\n")
            writer.write("echo $token\n")
            writer.flush()

            val input: InputStream = stream.openInputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.append(String(buffer, 0, read, Charsets.UTF_8))
                if (output.contains(token)) break
            }
        } catch (t: Throwable) {
            // 超时或连接中断时返回已经收到的内容
        } finally {
            watchdog.interrupt()
            try {
                stream.close()
            } catch (t: Throwable) {
                // ignore
            }
        }
        return output.toString()
    }

    /** 拿一份设备标识，连上之后显示用。 */
    fun deviceSummary(): String {
        return try {
            val raw = runShell(
                "getprop ro.product.model; getprop ro.build.version.release",
                8000
            )
            val lines = raw.split('\n').map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("__WADB") }
            if (lines.isEmpty()) "" else lines.joinToString(" / ").take(60)
        } catch (t: Throwable) {
            ""
        }
    }

    companion object {
        @Volatile
        private var instance: WadbManager? = null

        fun get(context: Context): WadbManager {
            instance?.let { return it }
            synchronized(WadbManager::class.java) {
                instance?.let { return it }
                val created = WadbManager(context.applicationContext)
                instance = created
                return created
            }
        }
    }
}
