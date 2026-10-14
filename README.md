# Avvessa

[English](README-EN.md)

Avvessa 是基于极光官方原生 SDK 的通用推送组件，提供 Android 和 iOS 原生接口。应用通过 AppKey 初始化，按需配置 APNs 或 Android 厂商通道，接收通知、自定义消息和点击事件。

当前发布版本支持 Android、iOS 原生，以及 UNI legacy、经典 UTS、uni-app x；RN、Flutter 包尚未发布。登录、账号绑定、页面路由和服务端发送由应用负责。来电信令、PushKit／CallKit、接听挂断和通话界面不在 Avvessa 的范围内。

## 能力

- 显式隐私同意与初始化，独立的系统通知授权操作。
- RegistrationID、连接、停止状态、通知授权及结构化错误。
- 原生事件持久化、订阅、重放、去重和显式确认消费。
- Android 通知小图标、颜色、渠道、声音、振动及灯光配置。
- 双端按消息字段和当前上下文控制前台横幅；被抑制的通知仍产生接收事件。
- iOS APNs、前台呈现、通知动作、角标和多个通知代理协作。
- 可选的启动、回到前台及点击通知时清理策略。
- 别名与标签管理、停止与恢复、已展示通知清理。
- 按需依赖的小米、华为、OPPO、vivo、荣耀、魅族和 FCM 模块。

在线连接、APNs 和厂商通道各有到达条件。系统展示通知不代表应用已收到回调；应用内存储只能恢复系统或 SDK 已交给应用的事件。

## 接入

| 平台 | 接入入口 |
| --- | --- |
| Android | [Java / Kotlin](native/android/README.md)：Maven 依赖、通知配置、可选厂商通道 |
| iOS | [Swift / Objective-C](native/ios/README.md)：SPM、XCFramework、APNs 与生命周期 |
| UNI legacy / 经典 UTS / uni-app x | [UNI 接入](adapters/uniapp/README.md)：安装、消费配置与 JavaScript / UTS API |

AppKey 不替代平台配置：iOS 需要匹配 Bundle ID 的 APNs 配置和签名能力；Android 厂商通道需要对应厂商应用参数。服务端 Master Secret 不进入客户端。

从 [GitHub Releases](https://github.com/sandroxy/avvessa/releases) 选择发布版本。默认分支维护已发布功能的接入指南与文档更正；历史版本的接口和依赖以对应 tag 为准。包版本与 SDK 依赖由 [plugin.json](plugin.json) 维护，最低系统和宿主要求见各平台接入指南。

- [事件、状态与生命周期契约](doc/contract.md)
- [第三方依赖与隐私资源](THIRD_PARTY_NOTICES.md)
- [版本变化](CHANGELOG.md)

开发与维护：[架构、构建和检查](DEVELOPMENT.md) · [发布流程](RELEASING.md)。

## 许可证

Avvessa 代码使用 [Apache-2.0](LICENSE)。极光和厂商 SDK 遵循各自条款。
