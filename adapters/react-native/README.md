# Avvessa React Native 接入

React Native 适配器尚未发布到 npm；源码使用方式见 [开发文档](../../DEVELOPMENT.md#react-native)。设计基线为 React Native 0.81，包含旧桥与 TurboModule 入口。最低系统取 RN、Avvessa 及所选厂商 SDK 的共同要求，当前 RN 基线为 Android API 24、iOS 15.1。Expo 需要包含原生插件的开发构建，Expo Go 不包含 Avvessa。

提供普通通知、自定义消息、点击事件、事件持久化和展示控制；不包含 PushKit、CallKit、来电界面或通话控制。业务账号、路由、隐私同意时机与服务端发送由应用负责。

## 依赖

完整 RN 包由本目录及其声明的共享输入组成；不能直接把本目录模板作为完整 npm 包安装。安装后由 RN 自动链接 Android 模块与 `AvvessaReactNative` Pod。

Android 使用 Avvessa Maven 核心，厂商模块按需选择；源码开发可使用明确准备的本地 AAR。iOS 使用同一核心的源码和隐私资源，由 CocoaPods 链接元数据锁定的官方 JPush／JCore。不要同时链接另一份 Avvessa、`jpush-react-native` 或另一套手动初始化的 JPush。

普通 RN 不依赖 ExpoModulesCore。Expo 项目额外链接 `AvvessaExpo`，自动转发应用启动及 APNs 回调。若应用用 `expo-notifications` 处理其他通知，可保留它；Avvessa 消息只应由一个业务消费者处理，避免重复执行或再创建本地通知。

## Expo 配置

在 `app.config.js` 的 `plugins` 中加入：

```js
['@sandrox/avvessa', {
  ios: { apnsEnvironment: 'development' },
  android: {
    vendors: [],
    vendorProperties: {},
    notificationIcon: './assets/push-small.xml',
  },
}]
```

`apnsEnvironment` 必须匹配签名中的 APNs entitlement：开发签名为 `development`，分发签名为 `production`。插件增加 `aps-environment` 和后台 `remote-notification`；不决定用户同意时机，也不自动申请通知权限。

`notificationIcon` 可省略。提供透明背景的单色 vector XML 或 PNG 后，生成资源名为 `drawable/avvessa_notification_custom`；初始化时在 `android.smallIcon` 中使用该名字，通过 `android.color` 指定颜色。系统或厂商通知样式仍可能展示应用图标。

`android.vendors` 只选择需要的模块，`android.vendorProperties` 只填写对应字符串参数：

| 模块 | 参数 |
| --- | --- |
| `xiaomi` | `XIAOMI_APPID`、`XIAOMI_APPKEY` |
| `huawei` | `android.agconnectServicesFile` 指向匹配包名的 JSON |
| `honor` | `HONOR_APPID` |
| `oppo` | `OPPO_APPID`、`OPPO_APPKEY`、`OPPO_APPSECRET` |
| `vivo` | `VIVO_APPID`、`VIVO_APPKEY` |
| `meizu` | `MEIZU_APPID`、`MEIZU_APPKEY` |
| `fcm` | `android.googleServicesFile` 指向匹配包名的 JSON |

文件路径相对于应用根目录或使用绝对路径。未知字段、缺少参数、重复模块及服务文件包名不匹配会报错。未选择的厂商不加入原生依赖。应用参数从本地或 CI 配置读取，服务端 Master Secret 不进入客户端。

配置插件处理标准 Groovy Gradle 工程；Kotlin DSL 需手动配置。修改原生配置后重新 prebuild 和构建，JS 刷新不会改变 Manifest、资源或 entitlement。

## 普通 RN 原生配置

Android 自动链接后无需修改 Activity 的通知点击入口。基础权限、接收器及通知入口来自原生核心 Manifest。厂商模块在 `gradle.properties` 中通过 `avvessaVendorModules=xiaomi,huawei` 选择，再按 [Android 厂商配置](../../native/android/README.md#optional-manufacturer-transports)填写占位符、仓库和服务文件。小图标放在宿主 `res/drawable`，初始化时填写资源名。

iOS AppDelegate 导入 `AvvessaReactNative`，在已有回调中加入下列调用，不要重复定义同名回调：

| 宿主回调 | Swift 转发调用 |
| --- | --- |
| 应用启动，启动 React 之前 | `AVReactNativeLifecycle.shared.launch(launchOptions)` |
| APNs 注册成功 | `AVReactNativeLifecycle.shared.registerDeviceToken(deviceToken)` |
| APNs 注册失败 | `AVReactNativeLifecycle.shared.registrationFailed(error as NSError)` |
| 后台远程通知 | `AVReactNativeLifecycle.shared.receive(userInfo)` |

后台回调的完成函数由宿主调用一次；`receive` 返回该消息是否属于 Avvessa。如有其他服务参与处理，由宿主汇总结果。启用 Push Notifications、Background Modes → Remote notifications，并配置相应签名。

启动入口安装通知代理协调器，保留其他模块后续对通知代理的赋值。Avvessa 通知由核心裁决前台展示，其他消息交给原有代理；被观察的代理需要宿主持有。该入口不授权或初始化 JPush。Expo 使用自动转发时不要再手动重复转发。

## 初始化与事件消费

完整方法与参数见 [TypeScript 声明](../uniapp/js_sdk/index.d.ts)，语义以 [公共契约](../../doc/contract.md)为准。RN 和 UNI 使用同一套 JSON 命令与错误码。

```js
import * as push from '@sandrox/avvessa';

// appConfiguration 由应用提供；同意时机与系统权限、登录分别处理。
await push.setConsentGranted(true);
const subscription = await push.subscribe(message => {
  if (message.kind === 'state') console.log(message.state.registrationId);
  else if (message.kind === 'error') console.error(message.error);
  else console.log(message.event); // 业务处理成功后再 acknowledge(event.id)。
}, { replayPending: true });

await push.initialize({
  appKey: appConfiguration.appKey,
  channel: appConfiguration.channel,
  ios: { apnsEnvironment: appConfiguration.apnsEnvironment },
  android: {
    notificationChannelId: 'messages',
    notificationChannelName: '消息',
    presentInForeground: true,
  },
});
await push.requestNotificationPermission();
```

需要自定义 Android 渠道声音或振动时，在初始化前调用 `createNotificationChannel`；已创建渠道受系统设置管理。Android 权限请求可能在用户选择前返回，`granted: null` 时通过状态事件或回前台后的 `refreshState` 确认结果。iOS 初始化不能替代 APNs 转发与签名配置。

React 卸载可能早于订阅 Promise 完成，清理时要覆盖这段竞争：

```js
useEffect(() => {
  let disposed = false;
  let id;
  push.subscribe(message => {
    if (!disposed) handleObservation(message);
  }).then(value => {
    if (disposed) return push.unsubscribe(value);
    id = value;
  }).catch(reportError);
  return () => {
    disposed = true;
    if (id) void push.unsubscribe(id).catch(reportError);
  };
}, [handleObservation, reportError]);
```

`handleObservation` 和 `reportError` 由宿主提供。取消订阅只释放观察者，不停止 SDK 或删除待消费事件。通知点击可早于 React 启动，之后通过重放恢复；`event.id` 保持不变，`replayed` 标示重放。业务成功后显式 `acknowledge`，并按事件 ID 保证业务副作用幂等。

## 展示与生命周期

初始化的 `presentationPolicy` 声明静态规则和活动上下文字段映射；页面通过 `setActiveNotificationContext`／`clearActiveNotificationContext` 更新上下文。账号、路由及字段含义由宿主定义；前台横幅被抑制时接收事件仍保留。离开相应页面应清除上下文。

`notificationLifecycle` 可选控制初始化、回前台、点击通知时的角标或通知清理，默认关闭。运行时的 `clearNotifications`、`clearPendingEvents`、`acknowledge` 分别操作已展示通知、全部待消费事件及单条事件消费状态。

`stopPush`／`resumePush` 控制 SDK 推送，`unsubscribe` 只控制观察者。撤回同意使用 `setConsentGranted(false)`；重新同意后显式初始化或恢复。别名、标签及后端设备绑定由宿主按业务管理。SDK 原始消息类型按公共契约保留，不增加语聊处理。
