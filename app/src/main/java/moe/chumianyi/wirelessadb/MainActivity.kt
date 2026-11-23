package moe.chumianyi.wirelessadb

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class MainActivity : AppCompatActivity() {

    private class Preset(val label: String, val command: String)

    private sealed class Outcome {
        class Success(val host: String, val port: Int, val model: String) : Outcome()
        class Failure(val message: String) : Outcome()
    }

    private lateinit var inputIp: EditText
    private lateinit var inputPort: EditText
    private lateinit var commandInput: EditText
    private lateinit var statusText: TextView
    private lateinit var statusChip: TextView
    private lateinit var console: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var presetBar: LinearLayout

    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnScan: Button
    private lateinit var btnPairCode: Button
    private lateinit var btnShowQr: Button
    private lateinit var btnRun: Button

    private var connecting = false
    private var running = false
    private var connected = false

    private val presets = listOf(
        Preset(
            "激活 Shizuku",
            "sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh"
        ),
        Preset(
            "设备信息",
            "getprop ro.product.brand; getprop ro.product.model; getprop ro.build.version.release; getprop ro.product.cpu.abi"
        ),
        Preset("三方应用", "pm list packages -3"),
        Preset("截屏", "screencap -p /sdcard/wadb_shot.png"),
        Preset("分辨率/DPI", "wm size; wm density"),
        Preset("熄屏", "input keyevent 223"),
        Preset("亮屏", "input keyevent 224"),
        Preset("清理后台", "am kill-all"),
        Preset("电池状态", "dumpsys battery"),
        Preset("Wi-Fi 信息", "ip addr show wlan0 2>/dev/null || ifconfig wlan0"),
        Preset("无线调试端口", "getprop service.adb.tls.port"),
        Preset("当前前台应用", "dumpsys activity activities | grep mResumedActivity")
    )

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val text = result.data?.getStringExtra(ScanActivity.EXTRA_QR_TEXT)
                if (!text.isNullOrBlank()) handleScanned(text)
            }
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openScanner() else toast("没有相机权限，请改用「配对码配对」")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputIp = findViewById(R.id.etIp)
        inputPort = findViewById(R.id.etPort)
        commandInput = findViewById(R.id.etCmd)
        statusText = findViewById(R.id.tvStatus)
        statusChip = findViewById(R.id.tvStatusChip)
        console = findViewById(R.id.tvOutput)
        consoleScroll = findViewById(R.id.consoleScroll)
        presetBar = findViewById(R.id.llPresets)

        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnScan = findViewById(R.id.btnScan)
        btnPairCode = findViewById(R.id.btnPairCode)
        btnShowQr = findViewById(R.id.btnShowQr)
        btnRun = findViewById(R.id.btnRun)

        buildPresets()
        restoreState()

        btnConnect.setOnClickListener { connectFromInput() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnScan.setOnClickListener { requestScan() }
        btnPairCode.setOnClickListener { showPairDialog() }
        btnShowQr.setOnClickListener { showQrDialog() }
        btnRun.setOnClickListener { runCommand(commandInput.text.toString()) }
        findViewById<TextView>(R.id.btnClear).setOnClickListener { console.text = "" }

        commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                runCommand(commandInput.text.toString())
                true
            } else {
                false
            }
        }

        updateUiForConnection()

        // 首次启动在后台把密钥准备好，避免点连接时卡主线程
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                WadbManager.get(applicationContext)
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    // ---------------------------------------------------------------- 连接

    private fun connectFromInput() {
        val host = inputIp.text.toString().trim()
        if (host.isEmpty()) {
            toast("请先输入目标设备的 IP")
            inputIp.requestFocus()
            return
        }
        val port = inputPort.text.toString().trim().toIntOrNull() ?: 0
        startConnect(host, port)
    }

    private fun startConnect(host: String, port: Int) {
        if (connecting) return
        setConnecting(true)
        setStatus("连接中… $host", false)
        val portSuffix = if (port > 0) ":$port" else "（自动发现端口）"
        appendConsole("\n[连接] $host$portSuffix\n")

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { doConnect(host, port) }
            setConnecting(false)
            when (outcome) {
                is Outcome.Success -> {
                    connected = true
                    inputIp.setText(outcome.host)
                    inputPort.setText(outcome.port.toString())
                    saveState(outcome.host, outcome.port)
                    val suffix = if (outcome.model.isNotBlank()) " · ${outcome.model}" else ""
                    setStatus("已连接 ${outcome.host}:${outcome.port}$suffix", false)
                    appendConsole("[连接成功] ${outcome.host}:${outcome.port}$suffix\n")
                }
                is Outcome.Failure -> {
                    connected = false
                    setStatus(outcome.message, true)
                    appendConsole("[连接失败] ${outcome.message}\n")
                    toast(outcome.message)
                }
            }
            updateUiForConnection()
        }
    }

    private fun doConnect(host: String, port: Int): Outcome {
        val manager = WadbManager.get(this)
        return try {
            if (manager.isConnected) {
                try {
                    manager.disconnect()
                } catch (t: Throwable) {
                    // ignore
                }
            }
            var targetPort = port
            if (targetPort <= 0) {
                val service = MdnsFinder.find(this, MdnsFinder.CONNECT, null, host, 8000)
                if (service != null) targetPort = service.port
            }
            if (targetPort <= 0) {
                return Outcome.Failure(
                    "没发现无线调试端口：请在目标机「无线调试」主页看 IP:端口，把端口填到端口框再连接。"
                )
            }
            if (!manager.connectWithRetry(host, targetPort)) {
                return Outcome.Failure(
                    "连接被拒绝：目标机还没信任本机密钥，请先「扫一扫配对」或「配对码配对」。"
                )
            }
            Outcome.Success(host, targetPort, manager.deviceSummary())
        } catch (t: Throwable) {
            Outcome.Failure(friendly(t))
        }
    }

    private fun disconnect() {
        if (connecting) return
        val manager = WadbManager.get(this)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    manager.disconnect()
                } catch (t: Throwable) {
                    // ignore
                }
            }
            connected = false
            setStatus("已断开", false)
            appendConsole("[断开连接]\n")
            updateUiForConnection()
        }
    }

    // ---------------------------------------------------------------- 配对

    private fun showPairDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_pair, null)
        val pairIp = view.findViewById<EditText>(R.id.etPairIp)
        val pairPort = view.findViewById<EditText>(R.id.etPairPort)
        val pairCode = view.findViewById<EditText>(R.id.etPairCode)
        pairIp.setText(inputIp.text.toString().trim())

        val dialog = AlertDialog.Builder(this, R.style.Theme_WirelessAdb_Dialog)
            .setTitle("配对码配对")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始配对", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = pairIp.text.toString().trim()
                val port = pairPort.text.toString().trim().toIntOrNull() ?: 0
                val code = pairCode.text.toString().trim()
                if (host.isEmpty() || port <= 0 || code.length < 6) {
                    toast("请完整填写 IP、配对端口和 6 位配对码")
                    return@setOnClickListener
                }
                inputIp.setText(host)
                dialog.dismiss()
                startPair(host, port, code)
            }
        }
        dialog.show()
    }

    private fun startPair(host: String, port: Int, code: String) {
        setConnecting(true)
        setStatus("配对中… $host:$port", false)
        appendConsole("\n[配对] $host:$port\n")

        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    val manager = WadbManager.get(this@MainActivity)
                    if (manager.isConnected) {
                        try {
                            manager.disconnect()
                        } catch (t: Throwable) {
                            // ignore
                        }
                    }
                    manager.pair(host, port, code)
                    null
                } catch (t: Throwable) {
                    friendly(t)
                }
            }
            setConnecting(false)
            if (error == null) {
                appendConsole("[配对成功] 正在建立连接…\n")
                startConnect(host, 0)
            } else {
                setStatus("配对失败：$error", true)
                appendConsole("[配对失败] $error\n")
                toast("配对失败：$error")
                updateUiForConnection()
            }
        }
    }

    private fun requestScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        openScanner()
    }

    private fun openScanner() {
        try {
            scanLauncher.launch(Intent(this, ScanActivity::class.java))
        } catch (t: Throwable) {
            toast("打不开相机：${t.message}")
        }
    }

    private fun handleScanned(text: String) {
        val endpoint = QrCode.parseEndpoint(text)
        if (endpoint != null) {
            inputIp.setText(endpoint.first)
            inputPort.setText(endpoint.second.toString())
            appendConsole("\n[扫码] 连接码 ${endpoint.first}:${endpoint.second}\n")
            startConnect(endpoint.first, endpoint.second)
            return
        }

        val pairing = QrCode.parsePairing(text)
        if (pairing == null) {
            appendConsole("[扫码] 无法识别的二维码：$text\n")
            toast("这个二维码不是无线调试配对码")
            return
        }

        appendConsole("\n[扫码] 配对服务 ${pairing.first}\n")
        setConnecting(true)
        setStatus("正在解析配对端口…", false)

        lifecycleScope.launch {
            val service = withContext(Dispatchers.IO) {
                MdnsFinder.find(this@MainActivity, MdnsFinder.PAIRING, pairing.first, null, 9000)
            }
            setConnecting(false)
            if (service == null) {
                val message = "局域网里没找到配对服务（多半是路由器屏蔽了 mDNS）。请改用「配对码配对」。"
                setStatus(message, true)
                appendConsole("[错误] $message\n")
                toast(message)
                updateUiForConnection()
            } else {
                inputIp.setText(service.host)
                startPair(service.host, service.port, pairing.second)
            }
        }
    }

    private fun showQrDialog() {
        val host = inputIp.text.toString().trim()
        if (host.isEmpty()) {
            toast("先在 IP 框填上目标设备地址")
            return
        }
        val portText = inputPort.text.toString().trim()

        setConnecting(true)
        lifecycleScope.launch {
            val port = withContext(Dispatchers.IO) {
                val manual = portText.toIntOrNull() ?: 0
                if (manual > 0) {
                    manual
                } else {
                    MdnsFinder.find(this@MainActivity, MdnsFinder.CONNECT, null, host, 8000)?.port ?: 0
                }
            }
            setConnecting(false)
            updateUiForConnection()
            if (port <= 0) {
                toast("没拿到端口，请在端口框填写目标机「无线调试」主页显示的端口")
                return@launch
            }
            inputPort.setText(port.toString())
            saveState(host, port)

            val content = QrCode.endpointText(host, port)
            val view = layoutInflater.inflate(R.layout.dialog_qr, null)
            view.findViewById<ImageView>(R.id.ivQr).setImageBitmap(QrCode.encode(content, 720))
            view.findViewById<TextView>(R.id.tvQrContent).text = content
            view.findViewById<TextView>(R.id.tvQrNote).text =
                "另一台装了本应用的手机点「扫一扫」扫这个码，就能直接连到 $host:$port"

            AlertDialog.Builder(this@MainActivity, R.style.Theme_WirelessAdb_Dialog)
                .setTitle("连接二维码")
                .setView(view)
                .setPositiveButton("复制内容") { _, _ -> copyToClipboard(content) }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    // ---------------------------------------------------------------- 命令

    private fun runCommand(raw: String) {
        val command = raw.trim()
        if (command.isEmpty()) return
        if (running) {
            toast("上一条命令还没跑完")
            return
        }
        val manager = WadbManager.get(this)
        if (!manager.isConnected) {
            toast("还没连上目标机")
            return
        }

        setRunning(true)
        appendConsole("\n$ $command\n")
        commandInput.setText("")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { manager.runShell(command, 20000L) }
            }
            setRunning(false)
            if (result.isSuccess) {
                appendConsole(formatOutput(command, result.getOrElse { "" }))
            } else {
                appendConsole("[异常] " + friendly(result.exceptionOrNull()))
            }
            appendConsole("\n")
        }
    }

    private fun formatOutput(command: String, raw: String): String {
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')
        text = text.replace(Regex("\u001B\\][^\u0007]*\u0007"), "")
        text = text.replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "")

        val lines = text.split("\n").toMutableList()
        val trimmedCommand = command.trim()
        lines.removeAll {
            val line = it.trim()
            line == trimmedCommand ||
                line == "stty -echo 2>/dev/null" ||
                line.contains("__WADB_END_")
        }
        while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)

        val body = lines.joinToString("\n").trim()
        return if (body.isEmpty()) "（无输出）" else body
    }

    // ---------------------------------------------------------------- 界面

    private fun buildPresets() {
        presetBar.removeAllViews()
        for (preset in presets) {
            val chip = TextView(this)
            chip.text = preset.label
            chip.setTextColor(ContextCompat.getColor(this, R.color.chip_text))
            chip.textSize = 12f
            chip.setBackgroundResource(R.drawable.bg_chip)
            chip.setPadding(dp(12), dp(7), dp(12), dp(7))
            val layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams.rightMargin = dp(8)
            chip.layoutParams = layoutParams
            chip.setOnClickListener { runCommand(preset.command) }
            chip.setOnLongClickListener {
                commandInput.setText(preset.command)
                commandInput.setSelection(commandInput.text.length)
                toast("已填入输入框，可改完再执行")
                true
            }
            presetBar.addView(chip)
        }
    }

    private fun setConnecting(value: Boolean) {
        connecting = value
        btnConnect.isEnabled = !value
        btnDisconnect.isEnabled = !value
        btnScan.isEnabled = !value
        btnPairCode.isEnabled = !value
        btnShowQr.isEnabled = !value
        btnRun.isEnabled = !value && !running
    }

    private fun setRunning(value: Boolean) {
        running = value
        btnRun.isEnabled = !value && !connecting
    }

    private fun setStatus(message: String, error: Boolean) {
        statusText.text = message
        statusText.setTextColor(
            ContextCompat.getColor(this, if (error) R.color.warn else R.color.text_secondary)
        )
    }

    private fun updateUiForConnection() {
        statusChip.text = if (connected) "已连接" else "未连接"
        statusChip.setTextColor(
            ContextCompat.getColor(this, if (connected) R.color.accent else R.color.text_secondary)
        )
        btnDisconnect.isEnabled = connected && !connecting
    }

    private fun appendConsole(text: String) {
        console.append(text)
        consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun friendly(error: Throwable?): String {
        if (error == null) return "未知错误"
        val message = error.message ?: ""
        return when {
            error is UnknownHostException -> "找不到这个地址，检查 IP 是否填对"
            error is SocketTimeoutException -> "连接超时：确认两台设备在同一个 Wi-Fi，且目标机开着无线调试"
            error is AdbAuthenticationFailedException -> "目标机没信任本机密钥：请先用「扫一扫配对」或「配对码配对」"
            error is AdbPairingRequiredException -> "目标机要求先配对"
            message.contains("Could not find any valid host address or port", true) -> "没发现无线调试端口"
            message.contains("Stream open actively rejected", true) -> "目标机拒绝了这次请求"
            message.contains("refused", true) -> "端口连不上：目标机端口可能变了，重开一次无线调试再试"
            message.contains("reset", true) -> "连接被重置：请在目标机重新开关无线调试后重新配对"
            message.contains("timed out", true) -> "操作超时（命令可能卡住了）"
            message.isEmpty() -> error.javaClass.simpleName
            else -> message
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText("wadb", text))
        toast("已复制")
    }

    private fun prefs(): SharedPreferences = getSharedPreferences("wadb", Context.MODE_PRIVATE)

    private fun restoreState() {
        val store = prefs()
        val host = store.getString("host", "") ?: ""
        val port = store.getInt("port", 0)
        if (host.isNotEmpty()) inputIp.setText(host)
        if (port > 0) inputPort.setText(port.toString())
    }

    private fun saveState(host: String, port: Int) {
        prefs().edit().putString("host", host).putInt("port", port).apply()
    }
}
