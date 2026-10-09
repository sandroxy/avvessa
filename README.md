# Avvessa

[English](README-EN.md)

Avvessa 是基于极光官方原生 SDK 的通用推送组件，提供 Android 和 iOS 原生接口。应用通过 AppKey 初始化，按需配置 APNs 或 Android 厂商通道，接收通知、自定义消息和点击事件。

核心不依赖 React Native、Expo 或 DCloud，不处理登录、账号绑定、页面路由或服务端发送。RN、UNI 和来电接口当前未提供。

## 原生能力

- 显式隐私同意与初始化，独立的系统通知授权操作。
- RegistrationID、连接、停止状态、通知授权及结构化错误。
- 原生事件持久化、订阅、重放、去重和显式确认消费。
- Android 通知小图标、颜色、渠道及前台展示策略。
- iOS APNs、前台呈现、通知动作、角标和已有通知代理协作。
- 别名与标签管理、停止与恢复、已展示通知清理。
- 按需依赖的小米、华为、OPPO、vivo、荣耀、魅族和 FCM 模块。

在线连接、APNs 和厂商通道各有到达条件。系统展示通知不代表应用已收到回调；应用内存储只能恢复系统或 SDK 已交给应用的事件。

## 接入

| 平台 | 最低系统 | 入口 |
| --- | --- | --- |
| Android | Android 5.0 / API 21 | [Java / Kotlin 接入](native/android/README.md)，AAR 与 Maven 元数据 |
| iOS | iOS 13 | [Swift / Objective-C 接入](native/ios/README.md)，Swift Package Manager 或 CocoaPods 源码依赖 |

AppKey 不替代平台配置：iOS 需要匹配 Bundle ID 的 APNs 配置和签名能力；Android 厂商通道需要对应厂商应用参数。服务端 Master Secret 不进入客户端。

本仓的源码接入和本机构建入口可直接使用。包版本与 SDK 依赖由 [plugin.json](plugin.json) 声明；不要把源码版本号当作已发布到公共包仓库的证明。

- [事件、状态与生命周期契约](doc/contract.md)
- [原生架构](doc/architecture.md)
- [本地构建与检查](BUILDING.md)
- [第三方依赖与隐私资源](THIRD_PARTY_NOTICES.md)

## 许可证

Avvessa 代码使用 [Apache-2.0](LICENSE)。极光和厂商 SDK 遵循各自条款。
