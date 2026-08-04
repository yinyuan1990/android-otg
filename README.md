# yql-android

Android 端推流客户端，与 iOS 端 [`srs_yql`](https://github.com/yinyuan1990/srs_yql) 对齐同一套后端 API 与 SRS WebRTC 推流协议。

- **包名**：`com.fz.yqlandroid`
- **技术栈**：Kotlin · Jetpack Compose · WebRTC · Camera2
- **iOS 对照仓库**：https://github.com/yinyuan1990/srs_yql.git
- **当前阶段**：SRS 推流优先（P2P / SRT 暂缓）

## 本地路径

```
/Users/chengyuan/aiqipai/ios/sysandroid
```

## 构建

```bash
./gradlew assembleDebug
```

## 文档

| 文件 | 说明 |
| --- | --- |
| [docs/PROGRESS.md](docs/PROGRESS.md) | 开发进度、与 iOS 功能对照 |
| [docs/项目路径说明.md](docs/项目路径说明.md) | 全系统各端仓库索引 |

## 关键模块

| 文件 | 职责 |
| --- | --- |
| `manager/WebRTCManager.kt` | WebRTC 总控：采集、编码、SRS 推流、档位切换 |
| `manager/Camera2ControlCapturer.kt` | 自定义 Camera2 采集 + 硬件控制（变焦/对焦/曝光/白平衡） |
| `manager/WebSocketManager.kt` | STOMP 长连接、CONFIG_STATE 心跳 |
| `network/NetworkService.kt` | 登录、注册、推流 Token |
| `ui/screen/StreamingScreen.kt` | 预览与自动推流 |
