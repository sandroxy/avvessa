# Avvessa 极光推送

为 uni-app 和 uni-app x 的 Android、iOS 应用接入极光推送，提供通知接收、通知点击、自定义消息和设备状态接口。

- 通过极光连接、iOS APNs 和按需接入的 Android 厂商通道接收推送。
- 保存未消费事件，在重新订阅或点击通知启动应用后恢复处理，由应用明确确认消费。
- 根据消息字段和当前页面上下文控制前台提醒，例如正在阅读同一会话时免打扰。
- 配置 Android 通知小图标、颜色和渠道，以及 iOS 前台展示方式、通知操作按钮和文本输入。
- 管理别名、标签、角标、通知清理与推送停止／恢复；隐私同意和系统通知授权分别由应用发起。

插件市场提供的 `Sandrox-Avvessa` UTS 包同时适用于经典 uni-app 和 uni-app x。经典原生插件（legacy）另提供 ZIP；原生 Android／iOS 接入见[项目主页](https://github.com/sandroxy/avvessa)。三种 UNI 方式共用 Avvessa 原生核心及极光官方 JPush/JCore SDK。

通知发送服务、登录与账号绑定、消息内容和点击后的业务页面由接入应用负责。Avvessa 不包含通话信令、PushKit／CallKit 或通话界面。

## 支持范围

| 接入方式 | Android 最低版本 | iOS 最低版本 |
| --- | --- | --- |
| 经典 uni-app 原生插件（legacy） | Android 5.0 / API 21 | iOS 13 |
| UTS 市场包：经典 uni-app、uni-app x | Android 6.0 / API 23 | iOS 15 |

UTS 市场包对经典 uni-app 和 uni-app x 使用相同的最低系统声明；legacy 包独立维护。UTS 支持 Vue 2／Vue 3 的经典 App-Vue 页面及 uni-app x，不支持 Web、小程序、快应用、HarmonyOS 或经典 App-nvue。

HBuilderX 最低版本由[包元数据](../../uni_modules/Sandrox-Avvessa/package.json)声明；编译器、原生 SDK 和基座须匹配，宿主还需满足所用 DCloud SDK 的系统要求。插件版本与极光 SDK 依赖见 [plugin.json](../../plugin.json)。

## 安装插件

使用经典 UTS 或 uni-app x 时，从 [UNI 插件市场](https://ext.dcloud.net.cn/plugin?name=Sandrox-Avvessa)点击「下载插件并导入 HBuilder」，选择目标工程。也可以从 [GitHub Releases](https://github.com/sandroxy/avvessa/releases) 下载对应 ZIP，解压到下表目录。legacy 使用单独的 legacy ZIP。

| 模式 | 包 | 放置目录 | 业务入口 |
| --- | --- | --- | --- |
| legacy | `avvessa-uniapp-legacy-<version>.zip` | `nativeplugins/Sandrox-Avvessa` | `@/nativeplugins/Sandrox-Avvessa/js_sdk/index.js` |
| 经典 UTS | `avvessa-uniapp-<version>.zip` | `uni_modules/Sandrox-Avvessa` | `@/uni_modules/Sandrox-Avvessa/js_sdk/index.js` |
| uni-app x | 同一个 UTS 包 | `uni_modules/Sandrox-Avvessa` | `@/uni_modules/Sandrox-Avvessa/uts_sdk/index.uts` |

解压后 `package.json` 应直接位于插件目录根部，不要多套一层同名文件夹。业务代码使用表中的入口，它们负责持续事件订阅；不要直接调用底层 `utssdk` 传输接口。各入口提供同名方法，uni-app x 的结构化参数和结果使用 `UTSJSONObject`。

legacy 还需在 `manifest.json` 的 `app-plus.nativePlugins` 选择本地 `Sandrox-Avvessa`。经典 UTS 和 uni-app x 通过代码导入引用模块。

同一应用只安装一种 Avvessa 接入方式，不能同时安装其他 JPush 包装器。安装或更新原生组件后，重新构建应用或自定义基座；标准基座不包含 Avvessa。宿主负责 DCloud AppID、离线打包授权、包名与签名，这些配置与极光 AppKey 相互独立。

## 配置 iOS

在极光控制台为应用配置有效的 APNs 凭据；应用 Bundle ID 必须与该极光应用及 Apple 配置一致。签名需包含 Push Notifications 能力，并启用 Background Modes → Remote notifications。

初始化时提供 `ios.apnsEnvironment`：开发签名使用 `development`，生产签名使用 `production`。该值、签名中的 `aps-environment` 和极光发送环境必须一致。APNs p8、证书私钥及极光 Master Secret 只用于服务端配置，不放入客户端。

legacy 通过包内 `hooksClass` 接收应用及 APNs 回调；UTS 通过导出的 `AvvessaAppHook` 接收 `UTSiOSHookProxy` 回调。手工维护离线宿主时，必须保留 DCloud 导出的 hook 注册，不能只链接 framework。

经典 uni-app 需在 `app-plus.modules` 启用空的 `Push: {}` 模块，并设置 `app-plus.distribute.ios.pushRegisterMode: "manual"`，供 APNs 回调转发使用；不选择 UniPush 服务商，不调用其获取 CID 接口。见 [DCloud 生命周期说明](https://doc.dcloud.net.cn/uni-app-x/plugin/uts-plugin.html)。uni-app x 使用自己的运行时 hook 转发，离线宿主必须把 UIApplication 的 token、注册错误、远程通知回调交给 `UniAppXSDK`。

适配层自动协调当前通知中心的 delegate；业务页面无需重复转发。若宿主还有其他通知组件，须由该组件持有自己的 delegate 并完成回调。通知归属、前台抑制与超时行为见 [iOS 代理协作](../../native/ios/README.md#cooperate-with-an-existing-notification-delegate)。

## 配置 Android

使用在极光控制台登记的应用包名和 AppKey。应用未运行时的推送还依赖厂商通道、设备系统及后台限制，不能只配置极光 AppKey 就认为厂商通道已经启用。

### 选择厂商通道

插件包默认只加载基础通道。小米、华为、OPPO、vivo、荣耀、魅族和 FCM 的可选组件随包存放于 `tools/android-vendors.zip`，按需选择后才参与打包。例如启用六家厂商、排除 FCM：

```sh
python3 /path/to/Sandrox-Avvessa/tools/configure-android-vendors.py \
  --module /path/to/Sandrox-Avvessa \
  --vendors xiaomi,huawei,oppo,vivo,honor,meizu
```

留空 `--vendors ''` 可移除该工具安装的组件；FCM 需明确加入 `fcm`。工具拒绝覆盖手工改动，只调整当前消费副本。

### 生成应用配置

厂商参数和服务 JSON 属于消费应用，不进入可公开分发的插件 ZIP。检出插件版本对应的 Git tag，或解压该 Release 自动提供的 **Source code** 归档，使用其中的 `scripts/prepare-uniapp-android.py` 将本地配置生成为仅供该应用使用的资源 AAR 和宿主清单；华为／FCM 资源分别由官方 AGConnect／Google Services 插件生成。

本地 JSON 示例（未选择厂商时保留空值）：

```json
{
  "android": {
    "applicationId": "com.example.pushdemo",
    "vendorModules": [],
    "vendorProperties": {},
    "googleServicesFile": null,
    "agconnectServicesFile": null,
    "retainedPermissions": []
  }
}
```

`vendorModules` 与安装工具选择同一组通道。`vendorProperties` 的键使用 [Android 厂商参数表](../../native/android/README.md#optional-manufacturer-transports) 中的占位参数名，值全部为字符串；华为／FCM 分别通过 `agconnectServicesFile`／`googleServicesFile` 提供匹配包名的服务 JSON 绝对路径。

在对应版本的源码根目录执行，环境需具备 JDK 17 与 Android SDK 36：

```sh
python3 scripts/prepare-uniapp-android.py \
  --config /absolute/path/to/consumer.local.json
```

命令打印资源 AAR 和宿主清单路径：

1. 将 `configuration-release.aar` 复制到 legacy 包的 `android/avvessa-consumer.aar`，或 UTS 包的 `utssdk/app-android/libs/avvessa-consumer.aar`。
2. 将生成的 `AndroidManifest.xml` 放在 UNI 项目根目录；已有文件时合并权限规则和 `application` 子节点。离线 Android 工程则合并到应用模块的 `src/main/AndroidManifest.xml`。

清单规则必须位于应用层，才能覆盖厂商 AAR 的占位参数。仅复制资源 AAR 不完整。文件位置见 [DCloud Android 资源约定](https://doc.dcloud.net.cn/uni-app-x/collocation/app-nativeresource-android.html)。仍需在极光／厂商后台登记相同包名、签名指纹、通道和消息分类。配置后的插件目录含应用参数，只供该应用打包。

生成清单默认移除 SDK 不需要的定位、电话状态、应用列表及外部存储权限。若宿主其他功能确实需要保留其中某项，可在本地 JSON 的 `android.retainedPermissions` 列出完整权限名，例如 `android.permission.READ_EXTERNAL_STORAGE`；生成器只对明确列出的已有移除项写入 `tools:node="merge"`。该配置不授予运行时权限。已有宿主清单中的声明仍需核对，合并后以最终清单为准。

### 设置通知小图标

初始化选项 `android.smallIcon` 使用 `drawable/name` 或 `mipmap/name`，不存在的资源会拒绝初始化。省略时使用插件默认图标。

图标应为白色轮廓、透明背景。资源生成命令可加 `--small-icon /absolute/path/to/icon.xml`：支持 vector XML、单张正方形 PNG，或只包含 `mdpi.png`／`hdpi.png`／`xhdpi.png`／`xxhdpi.png`／`xxxhdpi.png` 的目录。多密度尺寸依次为 24／36／48／72／96 像素；单张 PNG 最大 1024 像素，按 nodpi 打包。

使用生成的图标时，初始化须设置 `android.smallIcon: 'drawable/avvessa_consumer_notification'`。`android.color` 接受 `#RRGGBB` 或 `#AARRGGBB`。选择 FCM 时，生成器同时配置其默认图标，`--notification-color` 可设置 FCM 默认颜色。系统样式、厂商离线图标和权限合并边界见 [Android 通知配置](../../native/android/README.md#permission-channels-and-presentation)；iOS 使用应用图标。

## 初始化与订阅

在应用自己的隐私同意流程完成后调用 `setConsentGranted(true)`，再初始化和请求系统通知权限。同意隐私政策、允许通知、连接极光与取得 RegistrationID 是不同状态，不应相互替代。

### 经典 uni-app

以下 JavaScript 示例适用于经典 UTS；legacy 将导入路径替换为安装表中的 `js_sdk` 入口。

```js
import * as push from '@/uni_modules/Sandrox-Avvessa/js_sdk/index.js';

// 仅从用户明确同意的操作调用；appKey 来自本应用配置。
export async function enablePush(appKey) {
  await push.setConsentGranted(true);
  await push.initialize({
    appKey,
    channel: 'default',
    android: {
      notificationChannelId: 'messages',
      notificationChannelName: 'Messages',
      presentInForeground: true,
    },
    ios: {
      apnsEnvironment: 'development', // 必须匹配签名环境。
      foregroundPresentation: ['banner', 'list', 'sound', 'badge'],
    },
  });
  await push.requestNotificationPermission();
  return await push.subscribe(message => {
    if (message.kind === 'event') {
      // 处理 message.event；成功后调用 push.acknowledge(message.event.id)。
    }
  }, { replayPending: true });
}
```

调用方保存返回的订阅 ID，在所属页面或应用生命周期结束时调用 `await push.unsubscribe(subscriptionId)`，并处理 `enablePush` 的拒绝结果。`subscribe` 回调中的异步业务处理也需自行捕获错误。

### uni-app x

以下 UTS 示例区分首次启用与后续启动恢复；将 AppKey 替换为本应用配置，并使 APNs 环境匹配实际签名：

```uts
import * as push from '@/uni_modules/Sandrox-Avvessa/uts_sdk/index.uts'

const configuration: UTSJSONObject = {
  appKey: '<Jiguang AppKey>',
  ios: { apnsEnvironment: 'development' },
}

async function observePush(): Promise<string> {
  return await push.subscribe((message: UTSJSONObject) => {
    if (message.getString('kind') == 'event') {
      const event = message.getJSON('event')
      if (event != null) {
        console.log(event)
        // 完成业务处理后，读取非空的 event.getString('id')，再调用 acknowledge。
      }
    } else {
      // state 包含 registrationId 等状态；error 包含原生诊断。
      console.log(message)
    }
  }, { replayPending: true } as UTSJSONObject)
}

// 仅从用户明确同意的操作调用。
export async function enablePush(): Promise<string> {
  await push.setConsentGranted(true)
  await push.initialize(configuration)
  await push.requestNotificationPermission()
  return await observePush()
}

// 应用启动时调用；enabledByApp 来自宿主保存的推送启用选择。
export async function restorePush(enabledByApp: boolean): Promise<string | null> {
  const state = await push.getState()
  if (!enabledByApp || state.getBoolean('consentGranted') != true) return null
  await push.initialize(configuration)
  return await observePush()
}
```

首次启用调用 `enablePush()`，后续启动调用 `restorePush(enabledByApp)`；恢复函数返回 `null` 时不创建订阅。调用方处理 Promise 拒绝，保存非空订阅 ID，并在所属生命周期结束时调用 `await push.unsubscribe(subscriptionId)`。

UTS 的结构化参数和结果使用 `UTSJSONObject`。错误对象具有 `name/code/message/nativeCode/nativeDomain`，不要求继承平台 `Error`。示例仅输出事件，不自动确认消费；业务处理成功后再调用 `acknowledge`。

### 后续启动与权限刷新

冷启动恢复时，先等待 `getState()`，读取其 `consentGranted`（UTS 使用 `getBoolean('consentGranted')`）。仅在同意仍有效、且宿主记录为需要启用推送时调用 `initialize` 并恢复订阅；不要重新调用授予同意或申请权限的流程。`initialized` 只描述本次进程状态，不能用于判断以前是否启用过推送。

三种模式均应在同一生命周期只保留一个业务订阅，避免反复点击启用按钮或页面显示时重复订阅。取消订阅不停止推送，也不改变宿主保存的启用选择。

Android 13+ 的权限请求返回 `{requested:true, granted:null}`，表示已经发起系统请求；用户选择结果通过状态订阅或恢复前台后的 `refreshState()` 获取。iOS 等待系统授权结果返回；其设置刷新仍是异步的，随后有状态观察消息。

### 获取 RegistrationID

初始化完成不代表设备已经注册。通过 `kind: 'state'` 的订阅消息观察 `registrationId`，也可以主动读取：

```js
const state = await push.refreshState();
console.log(state.registrationId);
```

uni-app x 使用 `state.getString('registrationId')`。首次注册完成前该值可以为 `null`，等待后续状态消息即可。将取得的 RegistrationID 提交给自己的服务端绑定设备；它不是业务账号，也不是推送已送达的凭据。

## 处理事件

订阅消息通过 `kind` 区分：`state` 为状态更新，`error` 为错误，`event` 为推送事件。推送事件的 `type` 区分通知接收、点击、清除和自定义消息；根据事件处理业务，成功后调用 `acknowledge(event.id)`。

`subscribe(..., { replayPending: true })` 会重放未确认事件，适合恢复点击通知后的冷启动处理。应用需能够处理同一事件再次到达；取消订阅只移除监听，不会停止推送或确认事件。`getPendingEvents()` 返回待消费列表，`clearPendingEvents()` 则放弃其中全部事件。

`id` 用于消费确认，`messageId` 是原始 SDK 消息标识，两者不可混用。字段、持久化限制、去重和重放语义见[事件契约](../../doc/contract.md#events)。

系统可以直接展示通知而不唤醒应用。例如 iOS 后台普通 APNs 通知可能没有接收回调，点击后才产生打开事件。事件恢复只覆盖系统或 SDK 已交给应用的事件；自定义消息也不会自动变成系统通知。APNs／厂商凭据、通知权限、系统后台限制和网络状态仍会影响投递与展示。

## API 参考

完整 JavaScript 类型见 [`js_sdk/index.d.ts`](js_sdk/index.d.ts)，UTS 签名见 [`uts_sdk/index.uts`](../../uni_modules/Sandrox-Avvessa/uts_sdk/index.uts)。

- 状态与权限：`getState`、`getCapabilities`、`setConsentGranted`、`initialize`、`refreshState`、`requestNotificationPermission`、`openNotificationSettings`。
- 事件：`subscribe` 返回订阅 ID；消息分为 `event/state/error`。`getPendingEvents`、`acknowledge(eventId)`、`clearPendingEvents` 显式管理持久化事件；`unsubscribe` 只移除订阅。
- 推送控制：`stopPush`、`resumePush`、`setBadge`、`clearNotifications`。空参数清除本应用全部已展示通知；指定本平台 ID 列表只清除选中通知。仅传另一平台的 ID 会报错。
- 设备关联：`setAlias/getAlias/deleteAlias`、`setTags/addTags/deleteTags/getTags/clearTags/checkTag`。插件不绑定业务账号，不在退出登录时自行修改别名。
- 展示：Android `createNotificationChannel`；iOS `setNotificationCategories` 支持普通／文本输入动作和清除事件。不支持的平台明确报 `unsupported`。

参数错误、原生错误和 SDK 失败以稳定 `code` 及原始 `nativeCode/nativeDomain` 返回。未知字段、类型错误和不支持的调用不会悄悄忽略。别名标签等异步操作沿用原生核心的关联及超时机制。

## 控制前台提醒

需要在当前聊天页免打扰时，由应用设置消息匹配规则，并在页面显示／隐藏时更新上下文。初始化参数中的 `presentationPolicy` 只读取通知附加字段，不依赖业务路由。以下 `kind`、`conversationId` 和 `conversationType` 均为应用自定义字段：

```js
const presentationPolicy = {
  suppressForeground: [{ match: { kind: 'quiet' }, reason: 'quietMessage' }],
  activeContext: {
    match: { kind: 'chat' }, idField: 'conversationId', categoryField: 'conversationType',
    reason: 'activeConversation',
  },
};
// 将 presentationPolicy 传给 initialize；完成初始化后，页面显示时设置：
await push.setActiveNotificationContext({ id: 'conversation-a', category: 'direct' });
// 页面隐藏／离开时清除，包括被缓存而未销毁的页面：
await push.clearActiveNotificationContext();
```

规则只影响前台本地展示，被抑制的消息仍产生 `notificationReceived`。在页面 `onShow` 时设置上下文，`onHide`／离开时清除，不能只等销毁回调。匹配规则、iOS badge 行为、Android `blocked`／`silentRemoval` 区别和厂商限制以[共享展示契约](../../doc/contract.md#foreground-policy)为准。

`notificationLifecycle` 的可选清理字段及其触发时机见[生命周期契约](../../doc/contract.md#optional-lifecycle-cleanup)。规则与清理选项在初始化时固定，改变后需重启应用；运行中的页面切换通过上下文接口处理。

## 通知渠道与清理

Android 8+ 可在初始化前创建渠道：

```js
if ((await push.getCapabilities()).notificationChannels) {
  await push.createNotificationChannel({
    id: 'messages', name: 'Messages', importance: 4,
    vibrationPattern: [0, 250, 250, 250], lights: true, lightColor: '#FF231F7C',
    sound: true, showBadge: true,
  });
}
```

`soundUri` 支持宿主提供的 `android.resource://` 或 `content://` URI；系统必须能读取该声音。振动数组最多 32 项，每项 0–60,000 毫秒，总时长须在 1–60,000 毫秒内。默认重要性为 4（HIGH），已存在渠道保留用户设置；不能靠再次创建同名渠道强制恢复横幅或声音。定向清理使用 Android 的 `event.notificationId`（允许负数）或 iOS 的 `event.transportId`。

清除已展示通知与确认消费事件是两个独立操作。`clearNotifications()` 不传参数会清除本应用全部已展示通知，包括其他功能发出的通知；需要保留其他通知时，传入对应平台的 ID 列表。自动清理默认关闭，按需设置 `notificationLifecycle`。

## 依赖、隐私与开发

应用须按所选极光及厂商 SDK 的要求完成隐私告知，在取得用户同意后初始化。插件在应用本地保存待消费事件，不向插件作者发送数据；SDK 的数据处理、服务提供方及隐私说明见[第三方依赖说明](../../THIRD_PARTY_NOTICES.md)。

UNI 发布 ZIP 的 iOS frameworks 仅包含 arm64 真机组件，不能直接用于模拟器。修改插件或构建模拟器组件时，见 [开发指南](../../DEVELOPMENT.md#uni-package-builds-and-checks)。版本变化见[更新日志](../../CHANGELOG.md)。Avvessa 使用 [Apache-2.0 许可证](../../LICENSE)，极光与厂商 SDK 遵循各自条款。
