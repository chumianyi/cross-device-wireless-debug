# 跨设备无线调试 · Wireless ADB

同一局域网内，用另一台手机的真实 **无线调试（Wireless Debugging）** 连上目标机，然后在手机上直接跑 ADB Shell 命令。

不是壳子、不是演示：ADB 协议（TLS 1.3 + 密钥认证 + 无线调试 SPAKE2 配对）在应用内真实实现，连接成功后执行的就是目标机的 `sh`。

- 最低支持 **Android 5.0（API 21）**
- 目标机需要 **Android 11 及以上**（无线调试配对是 Android 11 才有的系统能力）

## 功能

| 功能 | 说明 |
| --- | --- |
| 输入 IP 连接 | 填目标机 IP，端口可留空自动通过 mDNS 发现，也可手动填 |
| 扫一扫配对 | 扫目标机「使用二维码配对设备」弹窗里的二维码，自动解析服务名 + 配对码 + 配对端口，配对后直连 |
| 配对码配对 | 读不到二维码时用：填目标机显示的 IP、配对端口、6 位配对码 |
| 生成连接二维码 | 把 `IP:端口` 编码成二维码，另一台装了本应用的手机扫一下即可直连 |
| ADB 命令 | 内置默认指令（含一键激活 Shizuku），也可自己输入任意 shell 命令，输出显示在控制台 |
| 连接保持 | 密钥保存在应用私有目录，配对一次之后，之后只需 IP + 端口即可直连 |

## 关于「二维码的方向」

系统「无线调试」只有两种入口，这一点决定了扫码只能是这个方向：

- 目标机上「**使用二维码配对设备**」：**目标机显示**二维码，配对端去扫；
- 目标机上「**使用配对码配对设备**」：显示 `IP:端口` + 6 位配对码，手动输入。

所以本应用的「扫一扫」是去扫**目标机显示的那个系统二维码**（内容为 `WIFI:T:ADB;S:<服务名>;P:<配对码>;;`，配对端口靠 mDNS 解析，不需要你手抄）。
同时本应用也能「生成连接二维码」，把当前 `IP:端口` 分享给另一台装了本应用的手机，扫一下就直连。

## 使用步骤

1. 两台手机连同一个 Wi-Fi（目标机不要开「AP 隔离」的访客网络）。
2. 目标机：开发者选项 → 无线调试 → 打开；点「使用二维码配对设备」。
3. 本机打开应用 → 点「扫一扫配对」→ 对准目标机二维码。
   - 扫不出来：回到目标机点「使用配对码配对设备」，把弹窗里的 IP、端口、6 位配对码填进本应用「配对码配对」。
4. 配对成功后自动连上，状态变成「已连接」，控制台可以开始执行命令。
5. 之后再用：填目标机 IP（端口可留空自动发现）→ 连接。

## 内置指令

| 指令 | 作用 |
| --- | --- |
| 激活 Shizuku | `sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh` |
| 设备信息 | 品牌/型号/系统版本/ABI |
| 三方应用 | `pm list packages -3` |
| 截屏 | `screencap -p /sdcard/wadb_shot.png` |
| 分辨率/DPI | `wm size; wm density` |
| 熄屏 / 亮屏 | `input keyevent 223` / `224` |
| 清理后台 | `am kill-all` |
| 电池状态 | `dumpsys battery` |
| Wi-Fi 信息 | `ip addr show wlan0` |
| 无线调试端口 | `getprop service.adb.tls.port` |
| 当前前台应用 | `dumpsys activity activities \| grep mResumedActivity` |

长按任意预设指令，会把它填进输入框，改完再执行。

## 实现要点

- ADB 协议栈：[MuntashirAkon/libadb-android](https://github.com/Muntashirakon/libadb-android)（`dadb` 的 Android 分支，含无线调试 SPAKE2 配对实现）
- 远端 ADB 需要 TLS 1.3，使用自定义 Conscrypt（`org.conscrypt:conscrypt-android`）
- 本机 ADB 身份：RSA 2048 + 自签名证书，BouncyCastle 生成，存成 PKCS12 放在应用私有目录
- 端口发现：`NsdManager` 查 `_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp`
- 命令执行：打开 `shell:` 伪终端，写入命令 + 结束标记，读到标记为止；带 20 秒看门狗，防止命令卡死
- 二维码：ZXing 生成；CameraX 帧 + ZXing 解码

## 构建

GitHub Actions 自动构建，不需要本地环境：

- 每次 push 到 `main`：构建 debug APK，并发布到 Release（`v1.0.N`）
- 也可在 Actions 页面手动触发 `workflow_dispatch`

本地构建（可选，需 JDK 17 + Android SDK 34）：

```bash
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 常见问题

**扫一扫一直提示「没找到配对服务」**
路由器屏蔽了 mDNS（常见于部分企业/校园网、开启 AP 隔离的热点）。改用「配对码配对」，它直接连目标机弹窗里显示的 `IP:端口`，不依赖 mDNS。

**连接被拒绝 / unauthorized**
目标机没信任本机密钥。先配对一次；如果目标机重新开关过无线调试或重启过，需要重新配对。

**端口填什么**
无线调试主页显示的 `IP:端口` 里的端口就是连接端口；配对弹窗里的端口是配对端口，两个端口不一样。

**能控制几台设备**
一次连一台，连接时会先断掉上一条会话。

## 免责

仅用于连接你本人拥有或已获授权的设备。无线调试等价于对目标机的完全控制权限，请自行评估风险。

ADB 协议库遵循 Apache-2.0 / GPL-3.0 双许可，详见其仓库。
