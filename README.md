# 🎵 Bluetooth-Music-Canvas (蓝牙音乐大屏看板)

> **极简、高颜值的 Android 蓝牙音频大屏看板 & 桌面黑胶悬浮球。**  
> 手机连上蓝牙放歌，电视 / 车机 / 智慧屏秒变黑胶唱片大屏与全屏流光歌词幕布。

---

## 🌟 核心特性 (Features)

- 📻 **蓝牙即连即播 (A2DP / AVRCP 监听)**：手机蓝牙连接车机/电视后，播放任意音乐自动唤醒大屏看板，暂停 10 秒后自动静默退出，无需手动开闭。
- 💿 **3D 拟物黑胶唱机与唱针 (Vinyl Turntable)**：精细绘制的复古黑胶纹理，播放时随节奏平滑旋转，唱针自动归位起落。
- 🖼️ **多源官方超清封面匹配 (HD Cover Art)**：原生 Java 多级聚合检索（Apple iTunes 600×600 超清原画 + 酷狗官方库），无惧反爬与跨域防盗链。
- 🎤 **毫秒级逐行滚动高亮歌词 (Synced Scrolling Lyrics)**：内置 LRCLIB 与酷狗歌词库，自动同步时间轴滚屏高亮；完美兼容车载蓝牙歌词模式（AVRCP Stream Lyrics）。
- 🌌 **动态流光氛围背景 (Ambient Glow Backdrop)**：全屏背景根据当前播放专辑封面的主色调实时提取流光散景与渐变，质感拉满。
- 🎈 **桌面迷你旋转黑胶悬浮球 (Mini Floating Vinyl Widget)**：点击「返回桌面」后，全屏退居后台，并在启动器桌面左上角常驻平滑旋转的迷你黑胶悬浮球。可任意拖拽位置，轻触立即无缝恢复大屏看板。
- 🚀 **极度轻量与高兼容性 (Pure Java & WebView)**：无 NDK / `.so` 本地库依赖，占用内存 < 30MB，完美兼容 Android 8.0 ~ 14（32 位 / 64 位 armeabi-v7a, arm64-v8a, x86）。

---

## 📸 实机展示 (Screenshots)

### 1. 播放大屏看板（官方封面 + 毫秒级逐行歌词）
![全屏播放效果](docs/images/screen_restored.png)

### 2. 多语言与国风歌曲自适应
![多语言播放效果](docs/images/screen_live_now.png)

---

## 📦 安装与快速开始 (Quick Start)

可以直接下载仓库根目录提供的已签名正式版本 `C10H_MusicCanvas.apk`，通过 ADB 或 U 盘安装到您的安卓电视盒、智慧屏或车机设备。

### 1. 安装 APK
```bash
adb install -r C10H_MusicCanvas.apk
```

### 2. 授予必要系统权限 (仅需一次)
由于本应用需要监听系统底层蓝牙音频流媒体控制器并在后台/开机自启显示悬浮窗，需要授予相应权限：

```bash
# 1. 授予媒体会话监听权限 (Notification Listener)
adb shell cmd notification allow_listener com.c10h.musiccanvas/com.c10h.musiccanvas.MediaMonitorService

# 2. 授予悬浮窗权限 (System Alert Window)
adb shell appops set com.c10h.musiccanvas SYSTEM_ALERT_WINDOW allow

# 3. 授予后台运行与电池白名单 (防止被系统休眠清理)
adb shell cmd deviceidle whitelist +com.c10h.musiccanvas
adb shell appops set com.c10h.musiccanvas RUN_IN_BACKGROUND allow
```

### 3. 开始使用
* 手机蓝牙配对连接电视/机顶盒。
* 在手机任意音乐软件（网易云、QQ 音乐、酷狗、Spotify、Apple Music 等）播放音乐。
* 大屏立即自动亮屏弹出全屏黑胶音乐看板！

---

## 🛠️ 自主构建工程 (Zero-Dependency Build)

本项目内置完全独立的自动化构建流水线 [`build.py`](build.py)，**无需安装重型 Android Studio**，仅依赖 JDK 即可完成资源打包、D8 字节码编译与签名：

```bash
python build.py
```

执行完毕后即在根目录生成已对齐并签名的 `C10H_MusicCanvas.apk`。

---

## 🏗️ 架构说明 (Architecture)

```text
手机 (蓝牙发送端)
       │ (Bluetooth A2DP Audio + AVRCP Metadata)
       ▼
Android Bluetooth Stack (com.android.bluetooth)
       │
       ▼ (MediaSessionManager)
MediaMonitorService (NotificationListenerService)
       │
       ├──> MetadataFetcher (Java 多线程聚合检索 iTunes / Kugou / LRCLIB)
       ├──> WindowManager (桌面悬浮迷你旋转黑胶 TYPE_APPLICATION_OVERLAY)
       └──> MainActivity (硬件加速 WebView 纯净全屏容器)
              │
              ▼
       assets/index.html (3D 黑胶动效、流光氛围、毫秒歌词引擎、播控桥接)
```

---

## 📄 开源许可证 (License)

本项目采用 [MIT License](LICENSE) 开源。欢迎提交 PR 与 Issue！
