<div align="center">

<img src="docs/images/logo.png" width="128" height="128" alt="Bluetooth Music Canvas Logo" style="border-radius: 28px; box-shadow: 0 10px 30px rgba(0,0,0,0.3);" />

# 🎵 Bluetooth Music Canvas (蓝牙音乐看板)

> **极简、高颜值的 Android 蓝牙音频大屏看板 & 桌面黑胶悬浮球**  
> 手机蓝牙连上大屏设备放歌，电视 / 车机 / 智慧屏秒变现代拟物黑胶唱机与全屏流光歌词幕布。

[![Platform](https://img.shields.io/badge/Platform-Android%205.0%2B%20(API%2021%2B)-brightgreen.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Arch-Universal-blue.svg)]()
[![License](https://img.shields.io/badge/License-MIT-orange.svg)](LICENSE)
[![APK Size](https://img.shields.io/badge/APK%20Size-~460%20KB-success.svg)](BluetoothMusicCanvas.apk)

</div>

---

## 🌟 核心特性 (Features)

### 💿 1. 拟物级毛玻璃黑胶唱机与物理循迹唱针
- **现代毛玻璃极简美学**：采用高质量 3D 渲染白透磨砂毛玻璃唱针与圆形基座，通透自然。
- **真实唱针物理循迹**：根据乐曲实时播放进度，唱针从黑胶外圈导槽平滑向内圈游走；暂停时自动复位升起至外圈休眠座，内置 1.5s 乐观播控防抖锁杜绝蓝牙 AVRCP 回声引发的唱针抽动。
- **超清同心导槽黑胶盘**：随专辑封面动态提取并生成专属微距黑胶唱片；采用**静态外投影与旋转纹理分层合成架构**（`isolation: isolate` + `::before` 静态阴影层），在低配电视盒 GPU 上亦能保持丝滑旋转。

### 📊 2. 真·9频段 & 5频段双态动态频谱仪（1024点 FFT 物理频带 + 按需跨桥门控）
- **1024 点采样与 9 个真实物理频段换算**：依据真实采样率 $F_s$ 与频点宽度 $f_k = k \cdot F_s / N$ 将 20Hz ~ 16kHz 划分为 9 个精细物理频带（次重低音 20~120Hz、底鼓 120~250Hz、中低频 250~500Hz、人声基频 500~1000Hz、人声核心 1~2kHz、泛音 2~4kHz、明亮度 4~7kHz、齿音 7~11kHz、空气感 11~16kHz），同时驱动全屏 9 柱律动音浪与 5 柱迷你均衡器。
- **彻底脱钩系统音量大小**：内置自适应动态增益控制（AGC），自动跟踪频段能量分布。**无论电视音量调为 100%、5% 甚至静音，频谱波形均可全动态弹性律动**。
- **按需跨桥门控（On-Demand Spectrum Gating）**：当正常滚动歌词激活、频谱组件隐藏时，前端自动通知原生层关闭 25Hz `evaluateJavascript` 跨桥推送，转为 250ms 低功耗静默环境音监测，大幅释放 WebView 与主线程 CPU。
- **静音平滑平息与休眠基底**：无音频或播放暂停时，柱条自适应衰减归位于底座胶囊基线（`scaleY(0.08)`），彻底消除静音死循环正弦波。

### 🖼️ 3. 多源官方超清封面与智能语义排版
- **四平台聚合检索与时长校验**：原生多线程聚合检索（网易云音乐 / QQ音乐 / 酷狗音乐 / Apple Music iTunes 600×600 / LRCLIB），结合曲目时长差值（$\Delta \le 2\text{s}$ 满分，$\Delta > 15\text{s}$ 降权）精准防翻唱/伴奏误配，无惧跨域与防盗链。
- **古典乐与长曲名智能分行排版**：智能识别作品编号与乐章号（如 `K. 285: I. Allegro`），将乐章号与速度标记紧密绑定防孤立折行；长副标题括号与破折号自动语义断行。

### 🎤 4. 6 大歌词来源自由切换 & 车载蓝牙歌词双层校准 (Sub-Second Lyric Sync)
- **多歌词源一键切换与偏好记忆**：支持「自动推荐」、「网易云音乐」、「QQ音乐」、「酷狗音乐」、「LRCLIB 全球库」、「蓝牙实时歌词」及「律动氛围」自由切换，切歌时严格保持用户选定的歌词源偏好。
- **车载蓝牙歌词流原生解析**：完美兼容网易云音乐与 QQ 音乐「车载蓝牙歌词」模式（自动剥离动态歌词头、提取歌名/歌手/内嵌专辑并维持稳态播放代次，不引发假切歌）。
- **双层时间窗口与防倒跳权威校准**：
  - **第一层（优先窗口）**：在估计进度 $\pm 15$ 秒内严密匹配，杜绝相同副歌重复段落误跳回第一段；
  - **第二层（全局兜底）**：当大幅 Seek 跳转或进度漂移时，自动选取时间绝对距离最近行归位；
  - **演职员过滤与 2.5s 防倒跳锁**：自动过滤 `作词/作曲/编曲/演唱/录音师/录音棚/混音/母带/方言指导` 等片头制作信息行，仅在时钟偏差 $> 2.5\text{s}$ 时重设基准时钟，杜绝正常播放时进度条向后倒跳。
- **多代次身份隔离与局部 DOM 高亮**：引入 `PlaybackGeneration` 与 `SourceRequestId` 严格防串歌乱序；采用 $[old-4..old+4] \cup [new-4..new+4]$ 邻域并集增量更新 DOM。

### 🎬 5. 智能视频音轨与正规音乐多层漏斗识别 (`MediaClassifier`)
- **单一权威分类器 (`MediaClassifier`)**：集中判定视频与正规音乐——强特征定视频（B站 UP 主、剧集号、视频分区括号），有正规专辑名强保音乐，杜绝前后端双重标准冲突。
- **底层硬件音频能量感知**：针对无标准 MediaSession 元数据但有音频输出的短视频/投屏应用，仅在处于真实播放态且持续检测到有效音频能量时自动切入“视频模式”律动音浪，待机或刚连接蓝牙时绝不误触发。

### 🎈 6. 桌面迷你旋转黑胶悬浮球 (Mini Floating Vinyl Widget)
- 应用退居后台时，在桌面常驻平滑旋转的微缩黑胶悬浮球（暂停播放时自动停止旋转动画，悬浮窗未展示时按需懒加载封面 `Bitmap` 零冗余解码），支持全屏幕任意拖拽与坐标记忆，轻触即刻秒级拉起全屏看板。

### 🚀 7. 针对低配电视盒 / 车机的深度渲染与能效优化
- **WebView & GPU 管线减负**：
  - 60fps 时间轴更新内置秒数、进度百分比与唱针角度脏值阈值检测，消除无效 DOM 写入与 CSS Transition 抖动；
  - 隐藏态弹窗与沉浸模式（`body.idle`）自动卸载 `backdrop-filter`，避免透明图层持续触发全屏高斯模糊采样；
  - 退居后台悬浮球时自动调用 `WebView.onPause()` / `pauseTimers()`，后台 WebView 渲染 CPU 降至 **0%**。
  - 在四核 ARMv7 Cortex-A53 + Mali-450 电视盒实机测试中，全屏播放总 CPU 占用降低 **47%**，`Chrome_InProcGp` 降低 **88%**，P99 帧耗时缩短 **45%**。
- **蓝牙 A2DP Sink 真实音频规格透传**：实时监听 `A2DP` / `A2DP_SINK` 连接状态，结合底层输出采样率动态展示真实音频链路规格（如 `A2DP / 48.0kHz / 16bit · 立体声`）。
- **专辑补全缓存继承与并发请求合并**：原生层 50 首 LRU 缓存 + 前端 50 首容量上限保护 + 飞行中请求合并（In-Flight Deduplication），当 AVRCP 晚到补全专辑名或时长时自动复用已有缓存，零重复网络请求。

---

## 📸 实机展示 (Screenshots)

<div align="center">
  <img src="docs/images/screen_latest.png" width="92%" alt="Bluetooth Music Canvas 音乐看板与高亮歌词" style="border-radius: 12px; box-shadow: 0 12px 40px rgba(0, 0, 0, 0.45);" />
  <p><i>（实机运行 1：音乐模式 —— 拟物毛玻璃唱机循迹 + 高清微距黑胶 + 450ms 预读平滑高亮滚动歌词）</i></p>
  <br/>
  <img src="docs/images/screen_video_mode.png" width="92%" alt="Bluetooth Music Canvas 视频与环境音频模式" style="border-radius: 12px; box-shadow: 0 12px 40px rgba(0, 0, 0, 0.45);" />
  <p><i>（实机运行 2：视频/环境音频模式 —— 智能音轨识别 + 延续高清封面 + 9频段 AGC 动态律动音浪）</i></p>
</div>

---

## 📦 安装与快速开始 (Quick Start)

可以直接下载本仓库根目录提供的已签名正式版本 [`BluetoothMusicCanvas.apk`](BluetoothMusicCanvas.apk)，通过 ADB 或 U 盘直接安装到您的设备。

### 1. 安装 APK
```bash
adb install -r BluetoothMusicCanvas.apk
```

### 2. 授予必要系统权限 (仅需一次)
为了实现蓝牙播放自动唤醒大屏、捕获音频频谱及后台悬浮黑胶球，需通过 ADB 或系统设置授权：

```bash
# 1. 授予媒体会话监听权限 (监听蓝牙音乐播放/暂停/元数据)
adb shell cmd notification allow_listener com.bluetoothmusiccanvas/com.bluetoothmusiccanvas.MediaMonitorService

# 2. 授予音频分析与频谱捕获权限
adb shell pm grant com.bluetoothmusiccanvas android.permission.RECORD_AUDIO

# 3. 授予悬浮窗权限 (System Alert Window)
adb shell appops set com.bluetoothmusiccanvas SYSTEM_ALERT_WINDOW allow

# 4. 授予后台运行与电池白名单 (防止电视休眠被系统杀后台)
adb shell cmd deviceidle whitelist +com.bluetoothmusiccanvas
adb shell appops set com.bluetoothmusiccanvas RUN_IN_BACKGROUND allow
```

### 3. 开始享受音乐
1. 手机与大屏设备配对连接蓝牙。
2. 在手机上打开任意流媒体软件（网易云、QQ 音乐、Apple Music、Spotify、YouTube Music 等）播放乐曲。
3. 大屏设备自动点亮并进入全屏黑胶看板！

---

## 🛠️ 自主构建、测试与一键部署 (Zero-Dependency Build & Test)

本项目内置完全独立的跨平台自动化构建流水线 [`build.py`](build.py) 与前后端回归测试套件 [`tests/run_all.py`](tests/run_all.py)，**无需安装数十 GB 的重型 Android Studio**，仅依赖标准 JDK（8+ / 11 / 17）与轻量 Android CLI 构建工具（`aapt2`, `android.jar`, `d8/r8`）：

```bash
# 1. 仅极速编译并签名 APK (耗时通常仅 3~5 秒)
python build.py

# 2. 先运行全量自动化回归测试 (Node.js JSDOM + Java 原生测试)，通过后再构建 APK
python build.py --test

# 3. 构建完成后自动通过 ADB 安装到指定实机、授予监听权限并拉起主界面
python build.py --test --install 192.168.5.18:5555 --launch

# 4. 单独运行全量前后端单元测试与回归测试套件
python tests/run_all.py
```

构建流水线会自动查找系统 JDK 与 Android 工具链，按序执行：资源编译打包 (`aapt2 compile/link`) ➔ Java 编译 (`javac`) ➔ Dalvik Release 字节码优化生成 (`D8 --release`) ➔ APK 归档与签名 (`jarsigner`)，并在根目录输出最终的 `BluetoothMusicCanvas.apk`。

---

## 🏗️ 架构说明 (Architecture)

```text
手机 (蓝牙发送端)
       │ (Bluetooth A2DP Audio + AVRCP Controls & Dynamic Car Lyrics)
       ▼
Android 系统底层协议栈 (com.android.bluetooth)
       │
       ▼ (MediaSessionManager / NotificationListenerService.requestRebind)
MediaMonitorService (com.bluetoothmusiccanvas.MediaMonitorService)
       │
       ├──> BluetoothMetadataNormalizer & MediaStateStore (车载歌词剥离、防抖与 PlaybackGeneration 代次状态机)
       ├──> MediaClassifier (视频/音乐漏斗式单一权威分类器)
       ├──> MetadataFetcher (网易云 / QQ音乐 / 酷狗 / iTunes / LRCLIB 多线程聚合检索 + 50首 LRU 缓存 + 时长打分)
       ├──> AudioVisualizerHelper (1024点 FFT 9频段物理频谱 + AGC 动态增益 + 按需跨桥门控)
       ├──> WindowManager (桌面迷你旋转黑胶悬浮窗 TYPE_APPLICATION_OVERLAY，按需懒加载封面 Bitmap)
       └──> MainActivity (硬件加速 WebView 沉浸容器，支持后台 onPause/pauseTimers 休眠)
              │
              ▼
       assets/index.html (分层合成3D黑胶、毛玻璃唱针循迹、双层窗口歌词校准、9/5频段音浪)
```

---

## 📜 项目背景 (Project Background)

本项目的早期开发基于 [天翼 C10H / 创维 SK-L1001 改造项目](https://github.com/Arrow36/tianyi-c10h-skyworth-SK-L1001-mod) 中的相关探索，现已独立为更通用、高性能的现代 Android 大屏蓝牙音乐看板开源项目。

---

## 📄 开源许可证 (License)

本项目采用 [MIT License](LICENSE) 开源。欢迎提交 PR 与 Issue！
