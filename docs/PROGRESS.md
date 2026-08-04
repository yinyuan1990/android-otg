# Android 开发进度（对照 iOS）

> 更新时间：2026-07-01（个人中心 Profile：扫一扫入口 + 已绑定列表/解绑已接线，见「十一」）  
> Android 仓库：`yql-android` · iOS 对照：`srs_yql`（分支 `srs-yql-fz`）  
> **本文档供下一个 AI / 开发者接力使用，请优先阅读「十一、2026-07-01 Profile 扫一扫入口 + 已绑定列表接力（最新）」，再看「十」了解前序背景**

---

## 一、仓库对照

| 角色 | 本地路径（Mac 原工程） | 本地路径（Windows 当前） | 远程仓库 |
| --- | --- | --- | --- |
| **Android 端** | `/Users/chengyuan/aiqipai/ios/sysandroid` | `D:\sb\ios-android\yql-android` | https://github.com/yinyuan1990/yql-android.git |
| **iOS 端** | `/Users/chengyuan/aiqipai/ios/srs_yql` | `D:\sb\ios` | https://github.com/yinyuan1990/srs_yql.git |
| **PC 端** | `/Users/chengyuan/aiqipai/ios/yql/aifs` | — | https://github.com/yinyuan1990/aifs-pc.git |
| **后端** | `/Users/chengyuan/aiqipai/ios/yql-all/houduan-jfh` | — | https://github.com/yinyuan1990/houduan-jfh.git |

**拉取说明**：`D:\sb\ios-android\android.txt` 指示与 iOS 同账号拉 Android 代码；Windows 侧已 clone 到 `D:\sb\ios-android\yql-android`（2026-07-01）。

**平台区分方式**：API 与 iOS 完全一致，仅 `deviceId` 带 `android` 前缀（SHA256 派生）。

---

## 二、已完成功能 ✅

### 2.1 鉴权与 API 对齐

| 功能 | Android | iOS 参考 |
| --- | --- | --- |
| 登录/注册接口 | `/auth/login/device`、`/auth/register/device` | `APIService.swift` |
| AES 加密 body | `{ data, deviceId }` | 同 iOS |
| User-Agent | `iPhone/iOS`（与 iOS 一致） | 同 |
| deviceId | `android` + SHA256 前 32 位大写 | iOS 无前缀 |
| 推流 Token | `/auth/stream/token/simple` | 同 |
| 删号路径 | `/user/account/delete` | 同 |

### 2.2 WebSocket / STOMP

| 功能 | Android | iOS 参考 |
| --- | --- | --- |
| 连接地址 | `wss://ws.147258yql.cn/ws?token=&deviceId=` | `WebSocketManager.swift` |
| CONFIG_STATE 心跳 | 含 `connectstype=0`、`connectMode=srs`、`p2pViewerCount=0` | 同 |
| 远程配置订阅 | `/topic/device/{deviceId}/config` | 同 |

### 2.3 SRS WebRTC 推流

| 功能 | Android | iOS 参考 |
| --- | --- | --- |
| 推流 API | `http://{srsIP}:1985/rtc/v1/publish/` | `SRSManager.swift` |
| app 名称 | `tenantA` | 同 |
| streamKey | `{permanentToken}_{unixTimestamp}` | 同 |
| baseStreamKey | 存 permanentToken，重启推流复用 | 同 |
| 仅视频轨 | 无 audio track | 同 |
| stopPublish | SRS unpublish / deleteStream | 同 |
| initialize 幂等 | 防止重复创建 EglBase/Factory（黑屏修复） | — |

### 2.4 分辨率档位（4 档）

与 iOS `getCaptureResolutionForProfile()` 对齐，每档选设备最接近分辨率，**直接采集、不 scaleDown**：

| 档位 | iOS 目标 | 码率范围 (kbps) |
| --- | --- | --- |
| STANDARD | 1024×768 | 2700–4500 |
| HIGH | 1440×1080 | 3300–5500 |
| P4K (4:3) | 1920×1440 | 4500–7500 |
| ULTRA (16:9) | 1280×720 | 3300–5500 |

**🆕 选档策略：60fps 优先（2026-07-01，见「十二」）**：每档在就近选分辨率时，**优先选能跑 60fps 的分辨率**，只有当该比例下没有任何 60fps 分辨率时才退回 30fps。避免此前「选到最接近但只支持 30fps 的分辨率、旁边能跑 60fps 的近似分辨率被忽略」的问题。

实现文件：`WebRTCManager.kt` → `queryCameraCapabilities()`（逐分辨率算最大fps）/ `findBestResolution()`（帧率优先就近）/ `calculateLadder()` / `applyProfile()`

### 2.5 自定义 Camera2 采集器

`Camera2ControlCapturer.kt` 替代 WebRTC 内置 `Camera2Capturer`，通过 `CaptureRequest` 做硬件控制：

| 远程配置 key | 硬件实现 | iOS 参考 |
| --- | --- | --- |
| zoom | `CONTROL_ZOOM_RATIO` / `SCALER_CROP_REGION` | 同 |
| focus | `LENS_FOCUS_DISTANCE` + AF OFF | 同 |
| cjfps | `SENSOR_EXPOSURE_TIME` + 手动 AE | 同 |
| exposure / test_brightness | AE 补偿 / ISO | 同 |
| white_balance / applyWhiteBalance | AWB 锁定 / 增益 | 同 |

`WebRTCManager.applyRemoteConfig()` 已路由上述分支。

### 2.6 UI 与系统

| 功能 | 状态 |
| --- | --- |
| 登录 / 注册 / 扫码绑定 | ✅ |
| 推流预览页 `StreamingScreen` | ✅ 自动 publish |
| **固定竖屏** | ✅ Manifest + MainActivity + 帧旋转固定 `deviceOrientation=0` |
| 权限（相机/网络） | ✅ |

### 2.7 🆕 发热优化（2026-07-01，代码已写，**待真机验证**）

| 项 | 说明 | 文件 |
| --- | --- | --- |
| **采集帧率空转修复** | ultra 档原先按设备能力采集到 240/120fps，但 `maxPushFps=60`，多出的帧在 ISP/传感器/纹理管线空转后被丢弃，是主要发热源之一。已改为 `ultraFps = minOf(60, maxFps)`，与推流上限对齐；慢门/快门仍由 `SENSOR_EXPOSURE_TIME` 控制，不依赖高采集帧率 | `WebRTCManager.kt` → `calculateLadder()` |
| **设备热状态监听** | 对标 iOS `ProcessInfo.thermalState`，基于 `PowerManager` 热状态 API（API 29+）。升温时自动降帧降码，回落恢复 | `ThermalManager.kt`（新） |
| **热控降档策略** | FAIR→≤30fps·0.8 码率；SERIOUS→≤20fps·0.6；CRITICAL→≤12fps·0.4；NOMINAL 恢复档位原始参数。约束贯穿 `setEncodingParameters` / `setTargetFps` / 自适应升帧 | `WebRTCManager.kt` → `applyThermalPolicy()` |

**设计原则（用户确认）**：优先低发热，不引入逐帧 GPU 滤镜。

### 2.8 🆕 颜色管线对标 iOS — 方案 A（2026-07-01，代码已写，**待 PC 拉流验证**）

| 项 | 说明 | 文件 |
| --- | --- | --- |
| **问题** | iOS `NV12MetalProcessor.swift` 给 NV12 打 BT.709 + full-range 元数据；Android 硬编码器默认不写 VUI，PC 端多按 BT.601 有限范围猜测 → 红色发暗、整体偏色 | iOS 参考：`srs/Managers/NV12MetalProcessor.swift` |
| **方案选择** | **方案 A**（低发热）：包装 `VideoEncoderFactory`，仅在**关键帧**改写 H264 SPS 的 VUI，写入 `video_full_range_flag=1` + BT.709 primaries/transfer/matrix。P 帧原样透传，几乎零额外功耗 | — |
| **未采用方案 B** | 自定义 `HardwareVideoEncoder` + MediaFormat `KEY_COLOR_STANDARD/RANGE/TRANSFER`（工作量大、回归风险高） | — |
| **未做 GPU 滤镜** | iOS 的曝光/亮度/gamma/对比度/饱和度/锐化/chroma 滤镜（`NV12Filter.metal`）**本轮未移植**，因逐帧 GPU 处理会增加发热 | 后续独立里程碑 |

实现文件：

- `ColorTaggingVideoEncoderFactory.kt` — 包装 H264 编码器，拦截关键帧回调
- `H264VuiEditor.kt` — Annex-B 切分、SPS 解析、VUI 改写、防竞争字节处理
- `WebRTCManager.kt` → `initialize()` 使用 `ColorTaggingVideoEncoderFactory(baseEncoderFactory)`

### 2.9 🆕 后台保活（2026-07-01，代码已写，**待真机验证**）

> 需求：系统**息屏后无法推流**（CPU 休眠 / 后台被限制 → 采集/编码/推流线程被冻结）。对标 iOS 用「无声音频保活」。

| 项 | 说明 | 文件 |
| --- | --- | --- |
| **无声音频保活** | 用 `AudioTrack` 以低采样率(8kHz/单声道/16bit)持续写「全零 PCM」（绝对静音），属性 `USAGE_MEDIA`，系统据此认为 App 在播放媒体，降低息屏/后台被限频、被杀概率。**纯零采样、无任何二进制音频资源、无可听声音** | `KeepAliveManager.kt`（新） |
| **PARTIAL_WAKE_LOCK** | 保持 CPU 运行，防止息屏后编码/推流线程被冻结（无超时，随推流启停 acquire/release） | `KeepAliveManager.kt` |
| **前台常亮** | Activity `FLAG_KEEP_SCREEN_ON`，对标 iOS `isIdleTimerDisabled`，进入推流页禁止自动息屏、离开恢复 | `StreamingScreen.kt` |
| **生命周期接入** | 自动推流 / `gongzuo`(唤醒) → `start()`；关闭按钮 / `shuimian`(睡眠) / `TryDisconnect` / 离开页面 → `stop()`。`start/stop` 幂等 | `StreamingScreen.kt` |

**iOS 对标**：`ContentView.swift` 的 `BackgroundAudioManager.startBackgroundKeepAlive()`（无声音频，因 App Store 审核当前注释隐藏）+ `UIApplication.shared.isIdleTimerDisabled = true`。

**权限**：无需新增——`WAKE_LOCK` 已在 Manifest 声明，`AudioTrack` 播放无需权限。

**设计取舍**：本方案覆盖「息屏但 App 仍在前台/推流」主场景。若需 App 完全退到后台仍长时间保活，Android 还需**前台服务(Foreground Service)** 承载（列为可选增强，见「三」）。

---

## 三、进行中 / 待完成 ⏳

| 项 | 说明 | 优先级 | iOS 状态 |
| --- | --- | --- | --- |
| **真机验证：发热** | Galaxy S25 等真机长时间推流，对比改前/改后机身温度、帧率、码率；确认热控降档是否触发/恢复 | 🔴 高 | iOS 有 thermalState |
| **真机验证：颜色** | PC 端拉 Android 流，对比 iOS 同色场景（尤其红色/肤色），确认 VUI 改写后 PC 不再偏色 | 🔴 高 | iOS 已对齐 |
| **真机验证：保活** | 真机推流后手动息屏 / 锁屏，观察是否持续推流不中断；确认 `KeepAliveManager` 的无声音频 + WakeLock 生效 | 🔴 高 | iOS 无声音频保活 |
| **前台服务保活增强** | App 完全退到后台时的长时间保活。当前仅无声音频+WakeLock，覆盖「息屏但在前台」主场景；如需退后台长稳，加 `ForegroundService`(mediaPlayback/microphone) + 通知 | 中 | iOS 后台音频 |
| **编译验证** | Windows 侧无 Android SDK，未跑 `./gradlew assembleDebug`；需在 Mac/Android Studio 编译 | 🔴 高 | — |
| **fbldy 档位日志** | 切换档位时打印当前分辨率/档位前缀 `fbldy` | 中 | iOS 有类似日志 |
| **B 类 GPU 滤镜** | captureColor、brightness/contrast/saturation/gamma、LUT（OpenGL ES 移植） | 低（发热敏感，暂缓） | iOS：`NV12MetalProcessor` + `NV12LUTProcessor` |
| **注册 check-device** | 注册前检查设备 | 中 | iOS 有调用 |
| **Profile 个人中心对标 iOS** | 🟡 **进行中**（见「十」「十一」）：API/修改密码/注销/关于我们/问题反馈/资料展示/**扫一扫导航/已绑定列表页/解绑** 已完成；**头像上传、激活会员、扫码时释放推流相机** 待完成 | 🔴 高 | iOS：`ProfileView.swift` 完整 |
| **P2P 推流** | 暂缓，优先 SRS 稳定 | 低 | iOS 已实现 |
| **SRT 推流** | 暂缓 | 低 | iOS 已实现 |

---

## 四、核心文件对照

| Android | iOS | 说明 |
| --- | --- | --- |
| `WebRTCManager.kt` | `WebRTCManager.swift` | 推流总控、档位、热控、编码工厂 |
| `Camera2ControlCapturer.kt` | `CustomAVCaptureVideoCapturer.swift` | 自定义采集 + 硬件控制 |
| `ThermalManager.kt` | `ProcessInfo.thermalState` 处理逻辑 | 🆕 热状态监听 |
| `ColorTaggingVideoEncoderFactory.kt` | `NV12MetalProcessor` 色彩元数据部分 | 🆕 H264 VUI 包装 |
| `H264VuiEditor.kt` | — | 🆕 SPS VUI 位级改写 |
| `KeepAliveManager.kt` | `BackgroundAudioManager`(iOS) + `isIdleTimerDisabled` | 🆕 无声音频 + WakeLock 后台保活 |
| `ProfileScreen.kt` | `ProfileView.swift` | 🟡 个人中心（对标进行中，见「十」） |
| `ChangePasswordScreen.kt` | `ChangePasswordView.swift` | 🆕 修改密码（登录密码+绑定码） |
| `MessageScreen.kt` | `MessageView.swift` | 🆕 问题反馈列表/发起/详情 |
| `LocalWebViewScreen.kt` | `LocalWebView.swift` | 🆕 本地 HTML（关于我们） |
| `QRScannerScreen.kt` | `DeviceBindingQRScannerView` + `BindingConfirmView` | ✅ 扫码绑定（登录流程 + **Profile 入口已接线**，见「十一」） |
| `BindingListScreen.kt` | `BindingListView.swift` + `UnbindView.swift` | 🆕 已绑定控制端列表 + 解绑（对标进行中，见「十一」） |
| `NetworkService.kt` | `APIService.swift` | REST API（Profile 相关 API 已补，见「十」） |
| `WebSocketManager.kt` | `WebSocketManager.swift` | STOMP 长连接 |
| `NetworkService.kt` | `APIService.swift` | REST API |
| `APIConfig.kt` | `APIConfig.swift` | 配置 |
| `DeviceIDManager.kt` | 设备 ID 生成逻辑 | — |
| `StreamingScreen.kt` | 推流 UI | — |
| `LoginScreen.kt` | `MonitorLoginView.swift` | — |

---

## 五、已知问题与修复记录

| 问题 | 原因 | 修复 | 状态 |
| --- | --- | --- | --- |
| PC 收不到流 | `app=live`、streamKey 格式错误 | 改为 `tenantA` + `{token}_{ts}` | ✅ 已修 |
| 预览黑屏 | 重复 `initialize()` 创建多套 Factory | 幂等 guard | ✅ 已修 |
| 采集正常但无画面 | 推流参数与 iOS 不一致 | 对齐 SRS publish 字段 | ✅ 已修 |
| 物理旋转导致预览转 | 读取 Display.rotation | 固定竖屏 + rotation=0 | ✅ 已修 |
| **Android 发热严重** | ultra 档采集 240fps 但只推 60fps，帧空转；无热控 | 采集帧率钳 60 + `ThermalManager` 热控降档 | 🟡 代码已写，待真机验证 |
| **PC 端 Android 流偏色** | H264 无 BT.709+full-range VUI | `ColorTaggingVideoEncoderFactory` 关键帧改 SPS | 🟡 代码已写，待 PC 验证 |

---

## 六、下一步建议（给接力 AI）

1. **编译**：在 Mac/Android Studio 执行 `./gradlew assembleDebug`，修复可能的编译错误（尤其 `EncodedImage.builder()` API 是否与 `stream-webrtc-android:1.1.1` 匹配）。
2. **真机发热测试**：Galaxy S25 推流 30 分钟，记录改前/改后温度；切换 ultra 档确认采集帧率不再 >60；升温时确认热控日志 `🌡️ [热控]` 出现。
3. **PC 颜色对比**：Android 推流 → PC 拉流，与 iOS 同色场景截图对比；若仍有偏色，考虑方案 B（MediaFormat 注入）或检查机型 MediaCodec 实际色彩转换。
4. **提交代码**：改动未 push，本地在 `D:\sb\ios-android\yql-android`，建议 commit 后 push 到 `yinyuan1990/yql-android`。
5. **暂缓项**：GPU 滤镜移植、P2P/SRT、fbldy 日志 — 等 SRS + 发热 + 颜色验证通过后再做。

---

## 七、2026-07-01 本轮改动详情（接力必读）

### 7.1 背景与目标

用户要求：
1. 按 `D:\sb\ios-android\android.txt` 拉 Android 代码
2. **解决 Android 发热问题**
3. **颜色管线对标 iOS**
4. **优先低发热**（不引入逐帧 GPU 滤镜）

### 7.2 改动文件清单

| 文件 | 操作 | 改动摘要 |
| --- | --- | --- |
| `manager/WebRTCManager.kt` | 修改 | `calculateLadder()` ultra 采集帧率钳 60；新增 `ThermalManager` 集成与 `applyThermalPolicy()`；`initialize()` 使用 `ColorTaggingVideoEncoderFactory`；热控约束贯穿编码/自适应 FPS |
| `manager/ThermalManager.kt` | **新增** | `PowerManager.OnThermalStatusChangedListener`，归一化 NOMINAL/FAIR/SERIOUS/CRITICAL |
| `manager/ColorTaggingVideoEncoderFactory.kt` | **新增** | 包装 H264 `VideoEncoder`，关键帧回调中调用 `H264VuiEditor` |
| `manager/H264VuiEditor.kt` | **新增** | Annex-B NALU 切分、SPS 解析、VUI 写入 BT.709+full-range、防竞争字节 |
| `docs/PROGRESS.md` | 修改 | 本文档 |

### 7.3 关键代码位置（快速跳转）

```
WebRTCManager.kt
  ├── calculateLadder()          ~L253  ultraFps = minOf(60, maxFps)
  ├── initialize()               ~L156  ColorTaggingVideoEncoderFactory 包装
  ├── applyThermalPolicy()       ~L178  热控降帧降码
  ├── setEncodingParameters()    ~L707  热控约束叠加
  └── destroy()                  ~L1258 thermalManager.stop()

ThermalManager.kt              完整新文件
ColorTaggingVideoEncoderFactory.kt  完整新文件
H264VuiEditor.kt               完整新文件
```

### 7.4 iOS 对标参考（Windows 本地路径）

| iOS 文件 | 路径 | 对标内容 |
| --- | --- | --- |
| 色彩元数据 | `D:\sb\ios\srs\Managers\NV12MetalProcessor.swift` L91-98 | BT.709 + full-range 三件套 |
| GPU 滤镜 shader | `D:\sb\ios\srs\Managers\NV12Filter.metal` | 曝光/饱和度/chroma 等（Android 未移植） |
| 采集色彩格式 | `D:\sb\ios\srs\Managers\CustomAVCaptureVideoCapturer.swift` L32 | `kCVPixelFormatType_420YpCbCr8BiPlanarFullRange` |
| 热控 | iOS `ProcessInfo.thermalState` | Android 用 `ThermalManager` 近似 |

### 7.5 未验证 / 潜在风险

| 风险 | 说明 | 建议 |
| --- | --- | --- |
| 未编译 | Windows 环境无 Android SDK | Mac 上 `./gradlew assembleDebug` |
| ~~`EncodedImage.builder()` API~~ | ✅ **已核实通过**（2026-07-01 代码审查）：对照 stream-webrtc-android 官方 API 文档，`EncodedImage.Builder` 的 `setBuffer(ByteBuffer, Runnable?)` / `setEncodedWidth` / `setEncodedHeight` / `setCaptureTimeNs` / `setFrameType` / `setRotation` / `setQp(Integer?)` / `createEncodedImage()` 全部匹配当前 `ColorTaggingVideoEncoderFactory.kt` 的调用；`VideoEncoder.Callback` 为单方法 SAM，Kotlin lambda 转换合法。详见「九」 | 无需改动 |
| VUI 改写兼容性 | 部分机型 SPS 结构复杂，解析失败会透传原帧（安全兜底） | 真机 + PC 拉流确认 |
| 方案 A 局限 | 仅改 VUI 标签，若 MediaCodec 实际按 BT.601 转换像素，个别机型可能仍有轻微偏差 | 偏色仍存在时升级方案 B |
| API < 29 无热控 | `ThermalManager` 在 API 29 以下跳过监听 | 可接受，或后续加 CPU 温度 fallback |
| 改动未 commit/push | 仅本地 `D:\sb\ios-android\yql-android` | 验证通过后提交 |

### 7.6 用户决策记录

- 颜色方案：**A**（SPS VUI 改写，低发热）
- GPU 滤镜：**本轮不做**（发热敏感）
- 文档：**要求写进度文档供下一个 AI 接力**（即本文档）

---

## 八、构建与运行

```bash
cd D:\sb\ios-android\yql-android   # Windows
# 或 cd /Users/chengyuan/aiqipai/ios/sysandroid  # Mac

./gradlew assembleDebug
```

依赖：`io.getstream:stream-webrtc-android:1.1.1`（见 `gradle/libs.versions.toml`）

---

## 九、2026-07-01 代码审查 & API 核实结论（接力必读）

> 本节由接力 AI 在 Windows 环境（无 Android SDK，无法跑 `./gradlew`）完成，采用**静态代码审查 + 官方 API 文档核对**方式，专门验证「六、下一步建议」中的头号风险——本轮新增/修改代码是否与 `stream-webrtc-android:1.1.1` API 匹配、逻辑是否自洽。

### 9.1 结论速览

- ✅ **未发现编译阻断错误**（基于对 stream-webrtc-android 官方 API 文档逐方法核对）
- ✅ **未发现会导致崩溃/黑帧/推流失效的逻辑 bug**
- ✅ 文档原先担心的 `EncodedImage.builder()` API **已核实完全匹配**，无需改动
- ⚠️ 仍需**真机 + PC 拉流**做最终功能验证（发热、颜色），静态审查无法替代

### 9.2 逐文件核对

| 文件 | 核对项 | 结论 |
| --- | --- | --- |
| `ColorTaggingVideoEncoderFactory.kt` | `VideoEncoderFactory` 三方法（`getSupportedCodecs`/`createEncoder`）、`VideoEncoder` 全部 override 方法签名 | ✅ 与官方接口一致。`VideoEncoder` 接口中 `setRates`/`getResolutionBitrateLimits`/`getEncoderInfo`/`isHardwareEncoder` 为 `open`（可覆盖），`initEncode`/`release`/`encode`/`setRateAllocation`/`getScalingSettings`/`getImplementationName` 为 `abstract`（必须覆盖），代码全部正确覆盖 |
| 同上 | `EncodedImage.Builder` 链式调用 | ✅ `setBuffer(out, null)`（stream 版签名为 `setBuffer(ByteBuffer, Runnable?)`）、`setQp(frame.qp)`（`setQp(Integer?)`）等全部匹配 |
| 同上 | 关键帧 buffer 生命周期 | ✅ 安全。`maybeTag` 用 `src.duplicate().get(data)` **拷贝**出字节，不持有原 buffer；返回帧用自建 `allocateDirect` buffer。符合「onEncodedFrame 返回后原 buffer 不可再访问」的约束 |
| 同上 | `VideoEncoder.Callback` SAM 转换 | ✅ 该接口仅一个抽象方法 `onEncodedFrame(EncodedImage, CodecSpecificInfo)`，Kotlin `VideoEncoder.Callback { frame, info -> ... }` lambda 合法 |
| `H264VuiEditor.kt` | Annex-B 切分 / EBSP↔RBSP / SPS 位级解析 / Exp-Golomb 读写 / 最小 VUI 写入 | ✅ 逻辑自洽，纯 Kotlin 无第三方依赖。异常一律返回 null（调用方透传原帧），安全兜底完整 |
| `ThermalManager.kt` | `PowerManager.OnThermalStatusChangedListener`、`addThermalStatusListener`/`removeThermalStatusListener`/`currentThermalStatus`、API 29 门禁 | ✅ 均为标准 Android API，SDK_INT<Q 正确跳过 |
| `WebRTCManager.kt` | 热控约束是否贯穿 | ✅ `thermalFpsCap`/`thermalBitrateScale` 在 `setEncodingParameters()`（帧率 `minOf(currentFps, maxPushFps, thermalFpsCap)`、码率 ×scale）与自适应升帧上限（`maxFps = minOf(maxPushFps, thermalFpsCap)`）均已叠加；`initialize()` 正确用 `ColorTaggingVideoEncoderFactory` 包装 `DefaultVideoEncoderFactory` |
| 同上 | `calculateLadder()` 采集帧率钳制 | ✅ `safeFps60 = minOf(60, maxFps)`、`ultraFps = minOf(60, maxFps)`，四档采集帧率均已对齐推流上限 60 |

### 9.3 核实所用官方 API 依据

- `EncodedImage` / `EncodedImage.Builder`：getstream.github.io/webrtc-android 官方 dokka 文档 + chromium/webrtc 源码，确认 `setBuffer(ByteBuffer, Runnable?)`、`setQp(Integer?)`、`createEncodedImage()` 等签名。
- `VideoEncoder` 接口：官方 dokka 列出全部方法及其 `abstract`/`open` 属性，据此确认包装类 override 无遗漏、无多余。
- `VideoEncoder.Callback`：单抽象方法 `onEncodedFrame(EncodedImage, CodecSpecificInfo)`，支持 SAM。

### 9.4 非阻断的观察项（可后续优化，非必须）

| 观察 | 说明 | 影响 | 建议 |
| --- | --- | --- | --- |
| 热控降帧未即时回写 `adaptiveFps` | `applyThermalPolicy()` 设 `currentFps=targetFps`，但自适应模块的 `adaptiveFps` 仍保留旧值，直到网络触发才下调 | **无实际风险**：`setEncodingParameters()` 下发编码器时用 `minOf(currentFps, maxPushFps, thermalFpsCap)`，最终推流帧率始终受 `thermalFpsCap` 钳制 | 可选：热控降档时同步 `adaptiveFps = minOf(adaptiveFps, thermalFpsCap)`，使日志更直观 |
| `createNative(webrtcEnvRef: Long): Long` 未覆盖 | stream 版 `VideoEncoder` 有此 `open` 方法（默认走 Java encoder 路径） | 无：底层 `HardwareVideoEncoder` 为 Java 实现，走 `initEncode/encode`，包装生效 | 无需改动，仅记录 |

### 9.5 仍未完成 / 交给下一棒

- 🔴 **编译验证**：Windows 无 Android SDK，本节仅静态核对，仍需在 Mac/Android Studio 跑 `./gradlew assembleDebug` 做最终确认。
- 🔴 **真机发热测试** 与 **PC 颜色对比**：见「六、下一步建议」2、3 项，静态审查不可替代。
- ✅ 发热/颜色/保活相关改动已 push（`f6cd955`）；**Profile 对标改动尚未 commit/push**（见「十」）。

---

## 十、2026-07-01 个人中心(Profile)对标 iOS 接力（下一棒必读）

> **用户指令**：「我的页面还需要对照 iOS 一一实现，每一行点进去看 iOS 一一实现。」  
> **iOS 对照文件**：`D:\sb\ios\srs\ProfileView.swift`（主列表）、`ChangePasswordView.swift`、`MessageView.swift`、`BindingListView.swift`、`LocalWebView.swift`、`ActivationView.swift`  
> **Android 主文件**：`app/src/main/java/com/fz/yqlandroid/ui/screen/ProfileScreen.kt`  
> **Git 状态**：本节改动均在本地未提交（`git status` 见下方文件清单）

### 10.1 逐项对照表（Profile 每一行）

| Profile 行 / 功能 | iOS 实现 | Android 现状 | 下一棒动作 |
| --- | --- | --- | --- |
| **头部：头像** | 可点击，`uploadAvatar` PUT multipart | ❌ 静态 `Person` 图标，无上传 | 加图片选择器 + `NetworkService.uploadAvatar()`（API 未写） |
| **头部：昵称** | `userProfile.nickname ?? username` | ✅ `getUserProfile` 加载后展示 | — |
| **头部：等级标签** | `activated` + `activation_level` → 试用/高清/超清/超高清/超高帧 | ✅ 从 `token_prefs` 读 `activated`/`activation_level` | — |
| **注册时间** | `formatDate(userProfile.createdAt)` | ✅ 已接 API + `formatProfileDate()` | — |
| **扫一扫** | `DeviceBindingQRScannerView` 全屏扫码 → 绑定确认 | 🟡 `QRScannerScreen` **已实现**（createBinding+verify），但 Profile **未接导航** | `AppNavigation` 加 `onNavigateToScan` → 复用 `QRScannerScreen`，`onBindSuccess` 应 `popBackStack` 回 Profile（勿跳 Streaming） |
| **已绑定控制端** | iOS 有 `BindingListView`（列表+解绑） | 🟡 Profile 已加一行 UI + 回调 `onNavigateToBindingList`，**页面与导航均未建** | 新建 `BindingListScreen.kt`，接 `getBindingList`/`unbindDevice` |
| **修改密码** | `ChangePasswordView` → PUT `/user/password/all` | ✅ `ChangePasswordScreen.kt` + 导航 | — |
| **注销账号** | POST `/user/account/delete` + 绑定码 + 完整登出 | ✅ 对话框已接 `deleteAccount` API | 文案 iOS 用「绑定码」非「管理密码」（Android 已改绑定码） |
| **版本号** | `CFBundleShortVersionString (CFBundleVersion)` | ✅ `PackageManager` 动态读取 | — |
| **关于我们** | `LocalWebView(fileName: "privacy_policy")` | ✅ `LocalWebViewScreen` + assets 三份 HTML | — |
| **问题反馈** | `MessageView` 列表/发起/详情 | ✅ `MessageScreen.kt` + 三 API | 可选：独立 Compose 页替代 AlertDialog，更接近 iOS UI |
| **退出** | 停推流通知 + 断 WS + 清 token + 回登录 | ✅ 已有确认对话框 | Profile 退出时 iOS 还发 `StopPublishBeforeLogout`；Android 若从推流页进 Profile，退出前应通知推流停止（当前仅断 WS） |
| **激活会员** | `ActivationView`（iOS Profile 内有 `activationRowView`，部分版本隐藏） | ❌ 未做 | 低优：需 `ActivationView.swift` + `/activation/*` API |
| **头像上传 API** | PUT multipart `/user/profile` field `avatar` | ❌ NetworkService 未实现 | 见 10.3 |

### 10.2 本棒已完成（代码已在本地，未 push）

#### NetworkService 新增 API（`network/NetworkService.kt`）

| 方法 | 端点 | 对标 iOS |
| --- | --- | --- |
| `getUserProfile(jwtToken)` | GET `/user/profile` | `getUserProfile` |
| `changeAllPasswords(...)` | PUT `/user/password/all` | `changeAllPasswords` |
| `deleteAccount(secondaryPassword, jwtToken)` | POST `/user/account/delete` | `deleteAccount` |
| `getBindingList(jwtToken)` | GET `/binding/list` | `getBindingList` |
| `unbindDevice(bindingId, secondaryPassword, jwtToken)` | **DELETE** `/binding/unbind/{bindingId}` body `{secondaryPassword}` | `unbindDevice`（注意路径带 id） |
| `getMessageConfig(jwtToken)` | GET `/message/config` | `getMessageConfig` |
| `getMessageList(userId, page, size, jwtToken)` | GET `/message/list?...` | `getMessageList` |
| `submitMessage(userId, content, jwtToken)` | POST `/message/submit` | `submitMessage` |

新增数据类：`UserProfileResponse`、`BindingItem`、`BindingListResponse`、`MessageItem`、`MessageListData` 等。

#### 新增/修改 UI 与资源

| 文件 | 状态 | 说明 |
| --- | --- | --- |
| `ui/screen/ProfileScreen.kt` | 修改 | 加载用户资料、等级/注册时间/版本号、注销 API、各行导航回调 |
| `ui/screen/ChangePasswordScreen.kt` | **新增** | 三字段改密，成功后完整登出 |
| `ui/screen/MessageScreen.kt` | **新增** | 问题反馈列表+发起+详情（Dialog 实现） |
| `ui/screen/LocalWebViewScreen.kt` | **新增** | WebView 加载 assets HTML |
| `navigation/AppNavigation.kt` | 修改 | 路由：`change_password`、`about_us`、`message`；**缺** scan/binding_list 接线 |
| `assets/privacy_policy.html` 等 | **新增** | 从 iOS `D:\sb\ios\srs\` 复制三份 HTML |

#### 已 push 的上一个 commit（与本节无关）

- `f6cd955` — 发热优化 + 颜色管线 + `KeepAliveManager`（已在 `origin/main`）

### 10.3 下一棒优先任务（建议顺序）

1. **编译** `./gradlew assembleDebug` — 修复可能的编译错误：
   - `ProfileScreen.kt` 使用 `Icons.Default.Link`，需确认 `material-icons-extended` 依赖是否存在；若无则改用 `Icons.Default.List` 或添加 extended 依赖。
2. **接扫一扫导航**：`AppNavigation.kt` 中 Profile 传入 `onNavigateToScan` → `navigate(Screen.QRScanner.route)`；为 Profile 来源增加参数或独立 route，绑定成功 `popBackStack()` 而非 `navigate(Streaming)`。扫码前 iOS 会 `ReleaseCameraForScanner` 释放推流相机 — Android 需从 `StreamingScreen`/`WebRTCManager` 暂停预览或 sleep 相机（见 iOS `handleDeviceBindingAction`）。
3. **新建 `BindingListScreen.kt`**（对标 `BindingListView.swift`）：
   - 列表展示 `getBindingList`
   - 点击解绑 → 输入绑定码 → `unbindDevice(bindingId, pwd, jwt)`
   - 导航：`Screen.BindingList` + Profile `onNavigateToBindingList`
4. **头像上传**（若要对齐 iOS）：`NetworkService.uploadAvatar` multipart + Profile 头像点击 ActionSheet（相册/相机）。
5. **Profile 退出/注销时停止推流**：对标 iOS `StopPublishBeforeLogout` — 可在 `WebRTCManager` 或 SharedFlow/Event 通知 `StreamingScreen.stopPublish()`。
6. **提交代码**：`git add` 本节全部文件 → commit → push（Windows push GitHub 若 reset，用 `git -c http.version=HTTP/1.1 push`）。

### 10.4 关键代码位置

```
ProfileScreen.kt
  ├── LaunchedEffect → NetworkService.getUserProfile
  ├── levelText / levelColor ← token_prefs activated + activation_level
  ├── formatProfileDate() ← 文件末尾私有函数
  ├── 注销对话框 → NetworkService.deleteAccount
  └── 导航回调：onNavigateToChangePassword / AboutUs / Message / Scan / BindingList

AppNavigation.kt
  ├── Screen.ChangePassword / AboutUs / Message
  ├── composable(ChangePasswordScreen / LocalWebViewScreen / MessageScreen)
  └── ⚠️ ProfileScreen 未传入 onNavigateToScan / onNavigateToBindingList

QRScannerScreen.kt（已有，可复用）
  └── createBinding → verifyDeviceBinding 完整流程

iOS 参考路径（Windows）
  D:\sb\ios\srs\ProfileView.swift
  D:\sb\ios\srs\BindingListView.swift
  D:\sb\ios\srs\ChangePasswordView.swift
  D:\sb\ios\srs\MessageView.swift
```

### 10.5 已知风险 / 注意事项

| 项 | 说明 |
| --- | --- |
| Message API 响应格式 | 与登录等扁平 JSON 不同，为 `{ success, data, message }` 包装；Android 已按 iOS 结构解析 |
| 解绑 API | 必须用 **DELETE** `/binding/unbind/{bindingId}`，不是 POST body 带 bindingId |
| userId | 问题反馈依赖 `token_prefs.user_id`（登录时 `LoginScreen` 已写入） |
| 扫码与推流相机冲突 | 从 Profile 进扫码前须释放 `WebRTCManager` 相机（iOS 发通知 `ReleaseCameraForScanner`） |
| 未编译验证 | 本节全部改动未跑 Gradle，下一棒务必先编译 |

---

## 十一、2026-07-01 Profile 扫一扫入口 + 已绑定列表/解绑接力（最新，下一棒必读）

> 本节承接「十」的下一棒任务清单（10.3 第 2、3 项），在 Windows 环境（无 Android SDK，未跑 Gradle）完成，采用**静态实现 + 官方 API/依赖核对**。
> **iOS 对照文件**：`D:\sb\ios\srs\BindingListView.swift`、`D:\sb\ios\srs\UnbindView.swift`、`D:\sb\ios\srs\ProfileView.swift`（`handleDeviceBindingAction` / `showingBindingList`）

### 11.1 本棒已完成 ✅

| 项 | 说明 | 文件 |
| --- | --- | --- |
| **扫一扫入口接线** | Profile「扫一扫」行此前有回调但导航未接。现 `AppNavigation` 给 `ProfileScreen` 传入 `onNavigateToScan`，导航到带来源参数的扫码路由 `qr_scanner?from=profile` | `navigation/AppNavigation.kt` |
| **扫码来源区分** | 扫码 composable 改为 `qr_scanner?from={from}`（`NavType.StringType`，默认 `login`）。`from=login`（登录流程）绑定成功→跳 `Streaming`（保持原行为）；`from=profile`→`popBackStack()` **回到 Profile**（对标 iOS `fullScreenCover` dismiss 回 Profile）。返回键同理按来源区分 | `navigation/AppNavigation.kt` |
| **已绑定列表页** | 新建 `BindingListScreen.kt`，对标 iOS `BindingListView`：加载中/错误(重试)/空/列表 四态、顶部刷新按钮、标题含数量「已绑定列表（N）」、账号脱敏(前2+**+后2)、绑定时间格式化(ISO→`yyyy-MM-dd HH:mm`) | `ui/screen/BindingListScreen.kt`（新） |
| **解绑弹窗** | `BindingListScreen.kt` 内 `UnbindDialog`，对标 iOS `UnbindView`：展示设备名/绑定时间 + 橙色警告 + 绑定码输入(密文) → `NetworkService.unbindDevice(bindingId, 绑定码, jwt)`；成功后从列表移除该项 | 同上 |
| **列表页导航** | 新增 `Screen.BindingList`("binding_list") 路由；Profile「已绑定控制端」行 `onNavigateToBindingList` → 导航到列表页 | `navigation/AppNavigation.kt` |
| **图标依赖核实** | 「十」10.3 第1项担心的 `Icons.Default.Link` —— 已确认 `app/build.gradle.kts` L54 有 `material-icons-extended`，`Link`/`LinkOff`/`DesktopWindows`/`Refresh` 均可用，**无需改动** | `app/build.gradle.kts` |

### 11.2 改动文件清单

| 文件 | 操作 | 摘要 |
| --- | --- | --- |
| `ui/screen/BindingListScreen.kt` | **新增** | 已绑定列表 + 解绑弹窗（`BindingListScreen` / `BindingRow` / `UnbindDialog` / `maskUsername` / `formatBindingTime`） |
| `navigation/AppNavigation.kt` | 修改 | import `BindingListScreen`/`NavType`/`navArgument`；新增 `Screen.BindingList`；Profile 接 `onNavigateToScan`/`onNavigateToBindingList`；扫码路由改带 `from` 参数并按来源分流；新增 binding_list composable |

> 复用未改动：`QRScannerScreen.kt`（扫码+确认+createBinding+verifyDeviceBinding 全流程已具备，本棒仅从 Profile 复用它）、`NetworkService.getBindingList`/`unbindDevice`（「十」已实现）。

### 11.3 静态核对结论

- ✅ `BindingItem` 字段（`bindingId`/`controlUsername`/`controlNickname?`/`createdAt?`）与 `NetworkService.kt` 定义一致
- ✅ `getBindingList(jwt): Result<BindingListResponse>`、`unbindDevice(bindingId, pwd, jwt): Result<String>` 调用签名匹配
- ✅ 图标全部来自已声明的 material core / extended
- ✅ `NavType`/`navArgument` 来自 navigation-compose 传递依赖 `androidx.navigation.*`
- ✅ 登录流程 `navigate("qr_scanner")` 仍能匹配 `qr_scanner?from={from}`（用默认 `from=login`），原行为不变
- ✅ Cursor lint 对两文件无报错

### 11.4 仍未完成 / 交给下一棒

| 项 | 优先级 | 说明 |
| --- | --- | --- |
| **编译验证** | 🔴 高 | Windows 无 Android SDK；需 Mac/Android Studio 跑 `./gradlew assembleDebug` 做最终确认（本棒仅静态实现） |
| **扫码时释放推流相机** | 🔴 高 | 对标 iOS `ReleaseCameraForScanner`。从 Profile 进扫码时，`StreamingScreen` 的 `WebRTCManager`（页面内 `remember` 局部实例，仍在后台栈）可能占用相机，导致 CameraX 扫码抢占失败。方案：把 `WebRTCManager` 提升为全局/共享，或用 SharedFlow/事件在进扫码前 `stopPublish()`/暂停预览，返回后恢复。本棒未做 |
| **头像上传** | 中 | `NetworkService.uploadAvatar` multipart PUT `/user/profile` field `avatar` + Profile 头像点击相册/相机（对标 iOS `uploadAvatar`）。未做 |
| **激活会员** | 低 | iOS `ActivationView` + `/activation/*`。`APIConfig.Activation` 端点已占位，页面/API 未做 |
| **真机验证** | 🔴 高 | 扫码入口跳转、绑定列表加载、解绑成功后列表移除、返回回到 Profile —— 需真机走查 |

### 11.5 关键代码位置

```
BindingListScreen.kt
  ├── loadBindings()                NetworkService.getBindingList → 四态
  ├── BindingRow                    图标+脱敏名+绑定时间+解绑按钮+箭头
  ├── UnbindDialog                  绑定码输入 → NetworkService.unbindDevice → 成功移除
  ├── maskUsername()                前2 + ** + 后2（<=4 原样）
  └── formatBindingTime()           ISO8601(多格式) → yyyy-MM-dd HH:mm

AppNavigation.kt
  ├── Screen.BindingList            "binding_list"
  ├── ProfileScreen(onNavigateToScan = navigate("qr_scanner?from=profile"),
  │                 onNavigateToBindingList = navigate(BindingList))
  ├── composable("qr_scanner?from={from}")  fromProfile ? popBackStack() : 原登录跳转
  └── composable(BindingList)       BindingListScreen(onNavigateBack = popBackStack)

iOS 参考（Windows 本地）
  D:\sb\ios\srs\BindingListView.swift   列表 + BindingRowView
  D:\sb\ios\srs\UnbindView.swift        解绑确认
  D:\sb\ios\srs\ProfileView.swift       handleDeviceBindingAction / showingBindingList
```

---

## 十二、2026-07-01 采集帧率选档策略：60fps 优先（下一棒必读）

> **用户反馈**：「Android 采集 fps 按 60fps 的标准 —— 相同分辨率的时候 60 的优先。目前都选到了 30fps；还是分辨率接近最优先，应改为先选采集 60 的，没有 60 才是 30。」

### 12.1 问题根因

此前的选档逻辑有两处缺陷：

1. **能力探测只记录整机一个 `maxFps`**：`queryCameraCapabilities()` 仅取 `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` 的整机最大帧率，**没有记录每个分辨率各自能跑多少 fps**。
2. **选分辨率时完全不看帧率**：`findBestResolution()` 只按「面积最接近目标」挑分辨率。于是当「最接近的分辨率恰好只支持 30fps、而稍有差异的近似分辨率能跑 60fps」时，会错误地选到 30fps 的那个。

结果：多档实际采集帧率掉到 30fps。

### 12.2 修复方案（已实现）

| 环节 | 改动 | 说明 |
| --- | --- | --- |
| **逐分辨率算帧率** | `queryCameraCapabilities()` 新增 `backSizeMaxFps`/`frontSizeMaxFps`（`Map<Size,Int>`） | 用 `SCALER_STREAM_CONFIGURATION_MAP.getOutputMinFrameDuration(format, size)` 计算每个分辨率的最大帧率 `fps = 1e9 / minFrameDuration`，再与整机 AE 上界取 min |
| **帧率优先就近选** | `findBestResolution()` 新增 `sizeMaxFps` + `desiredFps=60` 参数 | **两级策略**：① 先在「能达到 60fps 的分辨率」里选面积最接近的；② 只有当该比例下没有任何 60fps 分辨率时，才在全体候选里选面积最接近（此时可能 30fps）。比例正确性仍优先于帧率（不会为 60fps 把 16:9 档换成 4:3） |
| **每档取实际帧率** | `calculateLadder()` 每档 `fps = min(60, 该分辨率支持fps, 整机maxFps)` | 替换原来统一的 `safeFps60`。日志同时打印「该分辨率上限 Nfps」便于真机核对 |

### 12.3 改动文件

| 文件 | 操作 | 摘要 |
| --- | --- | --- |
| `manager/WebRTCManager.kt` | 修改 | 新增 `backSizeMaxFps`/`frontSizeMaxFps` 缓存；`queryCameraCapabilities()` 逐分辨率算 fps；`findBestResolution()` 帧率优先两级选档；`calculateLadder()` 每档按分辨率实际 fps 赋值 + 日志增强 |
| `docs/PROGRESS.md` | 修改 | 本节 + 2.4 节说明 |

### 12.4 关键代码位置

```
WebRTCManager.kt
  ├── backSizeMaxFps / frontSizeMaxFps        每分辨率→最大fps 缓存
  ├── queryCameraCapabilities()  ~L245        getOutputMinFrameDuration 逐分辨率算 fps
  ├── findBestResolution(..., sizeMaxFps, desiredFps=60)  ~L285  60fps 优先两级就近
  └── calculateLadder()          ~L346        nearest()传fps图; fpsFor(size)=min(60,分辨率fps,maxFps)
```

### 12.5 待验证（下一棒 / 真机）

- 🔴 **真机确认**：Galaxy S25 等推流，看日志 `实采WxH @Nfps(该分辨率上限Mfps)` —— 各档是否已优先落到 60fps；仅在设备该比例确无 60fps 分辨率时才为 30fps。
- ⚠️ 个别机型 `getOutputMinFrameDuration` 返回的理论帧率可能高于实际可达（已与 AE 上界取 min 兜底）；若某档申请 60 但采集实际达不到，可在真机日志进一步收紧。
- 🔴 **编译**：Windows 无 SDK，需 Mac/Android Studio `./gradlew assembleDebug` 确认。

---

## 十三、2026-07-01 运动自适应关键帧：大范围拖动花屏修复（下一棒必读）

> **用户反馈**：「Android 在大范围拖动过程中花屏，增加关键帧的发送 0.1 到 0.5 秒。」

### 13.1 问题与思路

大范围拖动 = 画面剧烈运动 → 帧间预测(P 帧)残差大，一旦丢包/参考帧受损，解码端在下一个关键帧到来前会持续花屏。此前关键帧固定 **1s 一次**，运动期间恢复太慢。

诉求：运动期间把关键帧间隔缩短到 **0.1~0.5s**。为兼顾**低发热原则**（平稳时不该白发关键帧），采用**运动自适应**：只有检测到大范围运动时才加密关键帧，平稳时仍 1s。

### 13.2 实现方案（已完成）

**运动检测**复用已有的 `ColorTaggingVideoEncoderFactory`（已包住编码回调），**零额外像素处理、低发热**：

| 环节 | 说明 | 文件 |
| --- | --- | --- |
| **P 帧字节数突增检测** | 编码回调里对 **P 帧(Delta)** 字节数维护指数滑动基线(EMA)；当某 P 帧字节数 > 基线 × `SURGE_RATIO`(2.2) 时判为大范围运动，触发 `onMotionSurge` 回调。关键帧本身字节大，不参与判断。带 120ms 节流避免每帧回调 | `ColorTaggingVideoEncoderFactory.kt` → `H264ColorTagEncoder.detectMotionSurge()` |
| **快速关键帧窗口** | `WebRTCManager` 收到突增：① 立即补一帧关键帧；② 开启 `FAST_KEYFRAME_WINDOW_MS`(1.2s) 快速窗口。窗口内关键帧节拍缩短到 **0.1~0.5s**（稳态节拍 0.3s，钳制在 [MIN=100ms, MAX=500ms])，窗口过后自动恢复 1s | `WebRTCManager.kt` → `onEncoderMotionSurge()` / `startKeyframeTimer()` |
| **线程安全** | 突增回调在编码线程，补帧切到 `scope`(IO) 执行，避免在编码线程直接改 `sender.parameters` | `WebRTCManager.kt` |

**关键帧参数**（`WebRTCManager` companion）：

```
NORMAL_KEYFRAME_MS       = 1000  平稳节拍
FAST_KEYFRAME_MIN_MS     = 100   快速下限 0.1s
FAST_KEYFRAME_MAX_MS     = 500   快速上限 0.5s
FAST_KEYFRAME_STEADY_MS  = 300   快速窗口稳态节拍(范围中值)
FAST_KEYFRAME_WINDOW_MS  = 1200  单次突增维持快速节奏时长
```

**运动检测参数**（`H264ColorTagEncoder` companion）：

```
SURGE_RATIO            = 2.2   P帧字节 > 基线×2.2 判为运动
EMA_ALPHA              = 0.2   基线平滑系数
SURGE_MIN_INTERVAL_NS  = 120ms 突增回调节流
```

### 13.3 改动文件

| 文件 | 操作 | 摘要 |
| --- | --- | --- |
| `manager/ColorTaggingVideoEncoderFactory.kt` | 修改 | 工厂新增 `onMotionSurge` 回调参数；`H264ColorTagEncoder` 加 P 帧字节数 EMA 基线突增检测(`detectMotionSurge`) + 节流 |
| `manager/WebRTCManager.kt` | 修改 | `initialize()` 构造工厂时传入 `onEncoderMotionSurge`；关键帧定时器改为动态节拍(平稳 1s / 快速 0.1~0.5s)；新增 `onEncoderMotionSurge()` 与关键帧节奏常量 |
| `docs/PROGRESS.md` | 修改 | 本节 |

### 13.4 关键代码位置

```
ColorTaggingVideoEncoderFactory.kt
  ├── ColorTaggingVideoEncoderFactory(delegate, onMotionSurge)
  └── H264ColorTagEncoder.detectMotionSurge()   P帧EMA基线 + 突增回调(节流)

WebRTCManager.kt
  ├── companion: NORMAL/FAST_KEYFRAME_* 常量
  ├── initialize()  ~L172   ColorTaggingVideoEncoderFactory{ onEncoderMotionSurge() }
  ├── startKeyframeTimer()   动态节拍：inFastWindow ? 0.1~0.5s : 1s
  └── onEncoderMotionSurge() 立即补帧 + 开 1.2s 快速窗口(scope 执行)
```

### 13.5 待验证 / 可调项（下一棒 / 真机）

- 🔴 **真机确认**：大范围拖动时看是否明显减少花屏、恢复更快；日志可加 tag 观察 `onEncoderMotionSurge` 触发频率。
- ⚠️ **灵敏度调参**：若误触发过多（普通轻微运动也进快速窗口）→ 调高 `SURGE_RATIO`；若拖动仍偶发花屏 → 调低 `SURGE_RATIO` 或延长 `FAST_KEYFRAME_WINDOW_MS`、缩短稳态节拍。
- ⚠️ **发热权衡**：快速窗口只在运动时短暂开启，稳态仍 1s，对发热影响有限；若持续大运动场景发热敏感，可上调 `FAST_KEYFRAME_STEADY_MS`。
- 🔴 **编译**：Windows 无 SDK，需 Mac/Android Studio `./gradlew assembleDebug` 确认。

---

## 十四、2026-07-01 修复：滤镜参数不生效 / fps 不反应 / cjfps 启动没挂上（下一棒必读）

> **用户反馈**：「滤镜参数没有作用；fps 也不反应；cjfps 快门启动的时候没挂上。」

### 14.1 三个根因

| 现象 | 根因 | 文件 |
| --- | --- | --- |
| **cjfps 启动没挂上 / 滤镜参数初始不生效** | 启动初始化只应用了 `type`+`direction`，**cjfps/zoom/focus/brightness/bitrate/fps 从未在启动时下发**（旧代码拼了 `profileMap` 却没用它） | `StreamingScreen.kt` 初始化 `LaunchedEffect` |
| **brightness 实时下发被忽略** | `applyRemoteConfig` 的 `when` 只匹配 `exposure`/`test_brightness`，后端/UI 用 `brightness` 时落到 `else`(未知 ptype) | `WebRTCManager.applyRemoteConfig()` |
| **fps 不反应** | `setTargetFps` ① `videoSender==null`(预览未推流) 时直接 `return`；② 只改编码器 `maxFramerate`，**从不改采集侧帧率** → 采集帧率恒定，表现为“fps 不反应” | `WebRTCManager.setTargetFps()` |

> 说明：`Camera2ControlCapturer` 本身对 zoom/cjfps/focus/wb 等有“下发即缓存、重开会话时重放”的兜底，能力正确；问题出在**上层从未把初始值下发给它**，以及 fps 只走了编码器一侧。

### 14.2 修复（已实现，对标 iOS applyThinRemoteConfigInit）

| 修复 | 说明 | 文件 |
| --- | --- | --- |
| **新增 `applyInitialConfig(config)`** | 启动时一次性把 **type/direction/zoom/cjfps/focus/brightness/bitrate/fps** 全部逐项 `applyRemoteConfig` 下发（对标 iOS `applyThinRemoteConfigInit`）。cjfps 快门在此补齐 | `WebRTCManager.kt` |
| **StreamingScreen 改用 `applyInitialConfig`** | 删除“拼了不用”的 `profileMap` 与只应用 type+direction 的旧逻辑；预览就绪(delay 800ms)后调用 `applyInitialConfig(config)` 全量应用 | `StreamingScreen.kt` |
| **`brightness` ptype 接入** | `applyRemoteConfig` 的曝光分支扩展为 `"exposure","test_brightness","brightness"`，统一走 AE 曝光补偿 | `WebRTCManager.kt` |
| **`setTargetFps` 双端同步** | ① `videoSender==null` 不再 `return`，改为仅提示并继续；② 新增 `changeCaptureFormat(w,h,targetFps)` 同步**采集侧**帧率；③ 仅在帧率确有变化时下发，避免相同值反复重开相机闪烁 | `WebRTCManager.kt` |

### 14.3 改动文件

| 文件 | 操作 | 摘要 |
| --- | --- | --- |
| `manager/WebRTCManager.kt` | 修改 | 新增 `applyInitialConfig()`；`applyRemoteConfig` 支持 `brightness`；`setTargetFps` 同步采集侧帧率+去除 null 早退+变更保护 |
| `ui/screen/StreamingScreen.kt` | 修改 | 初始化改为预览就绪后 `applyInitialConfig(config)` 全量应用 |
| `docs/PROGRESS.md` | 修改 | 本节 |

### 14.4 关键代码位置

```
WebRTCManager.kt
  ├── applyInitialConfig(config)   逐项下发 type/dir/zoom/cjfps/focus/brightness/bitrate/fps
  ├── applyRemoteConfig()  "exposure","test_brightness","brightness" → setExposure
  └── setTargetFps()       编码器 maxFramerate + changeCaptureFormat(采集侧) + 变更保护

StreamingScreen.kt
  └── 初始化 LaunchedEffect: delay(800) → webRTCManager.applyInitialConfig(config)
```

### 14.5 待验证 / 注意（下一棒 / 真机）

- 🔴 **真机确认**：启动后 cjfps(快门)/zoom/focus/亮度是否已生效；后端实时下发 brightness、fps 是否即时反应。
- ⚠️ **fps 采集侧重开代价**：`setTargetFps` 变化时会 `changeCaptureFormat`→重开相机会话（短暂黑帧）。已加“仅变化才下发”保护；若后端 fps 抖动频繁，可改为节流或只调编码器。
- ⚠️ **brightness 语义**：当前把 brightness 当 AE 曝光补偿(EV)。若后端 brightness 实为 0~100 亮度百分比而非 EV，需要在 `setExposure` 前做量纲换算（对照后端定义）。
- ⚠️ **cjfps 量纲**：后端 cjfps 直接作为快门 1/cjfps 秒(60~600)，与 iOS 一致；`setShutterSpeed` 内 `coerceIn(60,600)`。
- 🔴 **编译**：Windows 无 SDK，需 Mac/Android Studio `./gradlew assembleDebug` 确认。

---

## 十五、发热重构：自定义采集器 → 原生采集器 + 反射控制层（✅ 已实施，保留 cjfps 快门）

> **用户反馈**：「发热严重，是不是自定义采集的原因？看下同类产品怎么做的：`E:\yql\y60373\AndroidStudio\app` 不发热。」
> **用户确认**：按「原生采集器 + 反射控制层 + 保留 cjfps 快门」实施（见 15.6）。
> **状态**：✅ 已实施（代码已写，待真机验证发热与反射兼容性）。实施要点见 15.7。

### 15.1 对比结论：发热主因 = 自定义采集器

| 维度 | 本项目（发热） | 参考产品 y60373（不发热） |
| --- | --- | --- |
| 采集器 | **自定义 `Camera2ControlCapturer`**：自建 HandlerThread + SurfaceTexture 循环 + 自己的 `setRepeatingRequest`，绕过 WebRTC 优化管线 | **WebRTC 原生 `Camera2Enumerator.createCapturer()`**（`CameraActivity.createCameraCapturer` L896） |
| 参数控制(曝光/对焦/变焦) | 采集器内部**常驻**持有 `CaptureRequest.Builder`，每次会话都 `applyAllControlsLocked` | **反射**拿原生 session 的 `captureSession/cameraDevice/cameraCharacteristics/cameraThreadHandler/surface`，**按需**临时 `setRepeatingRequest` 一次（`applyCamera2Params` L660-746） |
| 分辨率/帧率 | `changeCaptureFormat` → 自定义 `reopenSession()` | `videoCapturer.changeCaptureFormat()`（原生实现，L587） |
| 手动快门(cjfps) | AE OFF + `SENSOR_EXPOSURE_TIME` + 手动 ISO（**常驻手动曝光**，ISP/传感器负载高，发热大） | **无手动快门**，只用 AE 补偿(`CONTROL_AE_EXPOSURE_COMPENSATION`) + AE_ON |

**判断**：自定义采集管线（尤其常驻手动曝光/快门）是 Android 端发热主因。参考产品用**原生采集器（WebRTC 高效纹理管线）+ 反射按需改参数**，既能曝光/对焦/变焦，又低发热。

### 15.2 参考产品关键做法（可直接借鉴）

1. **原生采集器**：`Camera2Enumerator(context).createCapturer(cameraId, null)`，`startCapture(w,h,fps)` 即可，纹理路径由 WebRTC 内部高效处理。
2. **按需参数注入**（`applyCamera2Params(exposure, focus, zoom)`）：
   - 反射链：`videoCapturer.getClass().superclass.getDeclaredField("currentSession")` → session 的 `captureSession/cameraDevice/cameraCharacteristics/cameraThreadHandler/surface`；
   - 新建 `TEMPLATE_RECORD`(3) request，`addTarget(surface)`，设置 AE 补偿/AF/CROP_REGION，`setRepeatingRequest` **一次**；
   - **只在用户拖动/后端下发时触发**，不常驻、不每帧。
3. **曝光**：`CONTROL_AE_MODE=ON` + `CONTROL_AE_EXPOSURE_COMPENSATION`（**不做手动快门**）——这是关键低发热点。
4. **对焦**：0.5=连续自动对焦(`AF_MODE=3`)，否则 `AF_MODE=OFF` + `LENS_FOCUS_DISTANCE`。
5. **变焦**：`SCALER_CROP_REGION` 裁剪。
6. **切分辨率/切摄像头**：都走原生 `changeCaptureFormat` / `switchCamera(handler, cameraId)`。

### 15.3 改造方案（下一棒实施）

| 步骤 | 改动 | 影响文件 |
| --- | --- | --- |
| 1. 采集器换原生 | `createCameraCapturer()` 改用 `Camera2Enumerator.createCapturer()`（前/后置按 `isFrontFacing/isBackFacing` 选 id）；删除/停用 `Camera2ControlCapturer` | `WebRTCManager.kt` `createCameraCapturer()`；`Camera2ControlCapturer.kt`（停用） |
| 2. 新增反射控制层 | 新建 `Camera2ParamApplier`（对标参考 `applyCamera2Params`）：反射取原生 session 字段 + 临时 `setRepeatingRequest`。曝光走 AE 补偿、对焦、变焦 | 新文件 `Camera2ParamApplier.kt` |
| 3. setter 改接反射层 | `setZoom/setFocus/setExposure` 改为调用反射层。**`setShutterSpeed(cjfps)` 保留**：反射通道同样能下发 `SENSOR_EXPOSURE_TIME`+AE_OFF+ISO，快门功能不因换原生而丢失（详见 15.6）。仅在“需要极限低发热”时才可选择弱化 | `WebRTCManager.kt` |
| 4. 白平衡 | 参考产品无白平衡控制；本项目如需保留，也改为反射层按需注入（避免常驻 AWB OFF） | `WebRTCManager.kt` / `Camera2ParamApplier.kt` |
| 5. 颜色管线保留 | `ColorTaggingVideoEncoderFactory`（VUI）与运动关键帧不受影响，继续保留 | 无 |

### 15.4 风险 / 注意

| 项 | 说明 |
| --- | --- |
| **反射字段名依赖库实现** | 参考用 `currentSession/captureSession/cameraDevice/cameraCharacteristics/cameraThreadHandler/surface`。本项目用 `io.getstream:stream-webrtc-android:1.1.1`，字段名大概率一致（同源 chromium webrtc），但**需真机反射验证**，失败要有兜底（try/catch 忽略，不崩溃） |
| **cjfps 快门可保留（澄清）** | 换原生采集器**不会让快门失效**：快门是通过 `CaptureRequest` 的 `SENSOR_EXPOSURE_TIME` 下发的，反射控制层同样能写。参考产品只是“选择不做快门”，不是原生做不了。推荐路线=原生采集器+反射层**保留 cjfps 快门**（详见 15.6） |
| **切档/切摄像头/参数重放** | 原生采集器切换后，反射注入的曝光/对焦/变焦需**重新触发一次**（参考产品在 `switchCamera` 回调、`quality` 变更后重新 `changeCaptureFormat` / 重注参数） |
| **发热验证** | 必须真机对比改前/改后温度，确认原生采集确实降温 |
| **编译** | Windows 无 SDK，需 Mac/Android Studio 验证反射 + 编译 |

### 15.5 参考文件（Windows 本地）

```
参考产品(不发热)：
  E:\yql\y60373\AndroidStudio\app\src\main\java\com\example\skylinkApp\CameraActivity.java
    ├── createCameraCapturer()  L896   原生 Camera2Enumerator.createCapturer
    ├── initWebRTC()            L591   原生采集器 startCapture
    └── applyCamera2Params()    L660   反射按需 setRepeatingRequest（曝光/对焦/变焦）

本项目(发热)：
  app/src/main/java/com/fz/yqlandroid/manager/Camera2ControlCapturer.kt  自定义采集器(拟停用)
  app/src/main/java/com/fz/yqlandroid/manager/WebRTCManager.kt           createCameraCapturer/参数setter
```

### 15.6 澄清：换原生采集器后快门(cjfps)会不会失效？—— 不会

**结论：不会失效。** 换原生采集器只是换“帧从传感器搬到编码器”的采集管线，**快门是另一回事**：

- 快门 = `CaptureRequest` 里写 `CONTROL_AE_MODE=OFF` + `SENSOR_EXPOSURE_TIME=1/cjfps` + 手动 ISO。
- 原生 `Camera2Capturer` 内部同样有 `captureSession/cameraDevice/surface`，**反射控制层照样能新建 request 写这些字段并 `setRepeatingRequest`**，快门正常生效。
- 参考产品 y60373 **只是产品上没做快门**（它只写 AE 补偿），并不是“原生采集器做不了快门”。

**两种发热来源要分开看**：

| 发热来源 | 是否去除 | 说明 |
| --- | --- | --- |
| ① 自建采集管线低效（主因） | ✅ 换原生即去除 | 这是本次降发热的**核心收益**，与快门无关 |
| ② 常驻手动快门(SENSOR_EXPOSURE_TIME) 负载 | 可留可去 | 属于**产品功能**，去掉能再省一点，但不是主因 |

**推荐路线（既降发热又保快门）**：
1. 采集器换 WebRTC 原生（拿到①的主要降温收益）；
2. 反射控制层**保留 cjfps 快门**（`SENSOR_EXPOSURE_TIME`），曝光/对焦/变焦一并走反射；
3. 反射只在“触发时下发一次”，不常驻每帧，比旧自定义采集器的“每次会话重放全套控制”更省。

即：**快门保留，发热仍显著下降**（因为主因①被消除）。只有在极端追求最低发热时，才考虑弱化②的手动快门。

### 15.7 实施记录（✅ 已完成）

| 改动 | 说明 | 文件 |
| --- | --- | --- |
| **采集器换原生** | `createCameraCapturer()` 改用 `Camera2Enumerator(context).createCapturer(name, null)`，按 `isFrontFacing/isBackFacing` 选前后置，找不到回退第一个 | `WebRTCManager.kt` |
| **删除自定义采集器** | `Camera2ControlCapturer.kt` 已删除（无引用） | 删除 |
| **新增反射控制层** | `Camera2ParamApplier`：反射原生 `Camera2Session` 的 `currentSession→captureSession/cameraDevice/cameraCharacteristics/cameraThreadHandler/surface`，新建 `TEMPLATE_RECORD` 请求一次 `setRepeatingRequest` 注入曝光/对焦/变焦/**快门(SENSOR_EXPOSURE_TIME)**/白平衡。异常全兜底不崩溃 | `Camera2ParamApplier.kt`（新） |
| **setter 改接反射层** | `setZoom/setFocus/setExposure/setShutterSpeed/setWhiteBalance` 只更新缓存字段并调用 `applyCameraParams()`（把全部缓存状态一次性注入）。`setShutterSpeed` **保留手动快门**(`_shutterEnabled=true`)；`setExposure` 切回自动AE(`_shutterEnabled=false`) | `WebRTCManager.kt` |
| **会话重建后重放** | 切摄像头(`switchCamera`回调)、切档/改fps 触发 `changeCaptureFormat` 重开会话后，`delay(300)` 再 `applyCameraParams()` 重放硬件参数 | `WebRTCManager.kt` |
| **颜色管线/运动关键帧保留** | `ColorTaggingVideoEncoderFactory`（VUI）+ 运动自适应关键帧不受影响 | 无 |

**关键代码位置**：

```
Camera2ParamApplier.kt
  └── apply(capturer, Params)   反射取原生 session → 一次 setRepeatingRequest
        ├── applyExposureAndShutter  cjfps→AE OFF+SENSOR_EXPOSURE_TIME+ISO / 否则 AE+EV
        ├── applyFocus / applyZoom / applyWhiteBalance

WebRTCManager.kt
  ├── createCameraCapturer()   Camera2Enumerator.createCapturer（原生）
  ├── applyCameraParams()      组装缓存状态 → Camera2ParamApplier.apply
  ├── setZoom/Focus/Exposure/ShutterSpeed/WhiteBalance → applyCameraParams()
  └── switchCamera / applyProfile / setTargetFps 会话重建后 delay(300)+applyCameraParams()
```

### 15.8 待真机验证（🔴 下一棒）

- 🔴 **发热对比**：改前/改后长时间推流机身温度，确认原生采集显著降温。
- 🔴 **反射兼容性**：`stream-webrtc-android:1.1.1` 的 `Camera2Session` 字段名是否与反射一致（日志看 `Camera2ParamApplier ✅ 参数已注入` 还是 `⚠️ 反射注入失败`）。失败已兜底(不崩溃)，但曝光/对焦/变焦/快门会不生效——若失败需按实际字段名调整。
- 🔴 **快门效果**：cjfps 下发后画面亮度/运动模糊是否随快门变化（验证 `SENSOR_EXPOSURE_TIME` 生效）。
- ⚠️ **参数重放时机**：会话重建后用 `delay(300)` 等新 session 就绪；个别慢机型可能需要加大延时。
- 🔴 **编译**：Windows 无 SDK，需 Mac/Android Studio `./gradlew assembleDebug` 确认（反射无编译期检查，尤需真机跑）。
