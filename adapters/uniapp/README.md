# Avvessa · UNI

Avvessa 为经典 uni-app 原生插件（legacy）、经典 uni-app UTS 插件和 uni-app x 提供 Android、iOS 推送接口。三种接入方式共用 Avvessa 原生核心及官方 JPush/JCore SDK；同一个应用只能选择其中一种，不能同时安装其他 JPush 包装器。

从 [GitHub Releases](https://github.com/sandroxy/avvessa/releases) 获取发布 ZIP。UTS 包同时支持经典 UTS 与 uni-app x；legacy ZIP 用于经典原生插件／离线接入。通过 UNI 插件市场安装时，选择已发布的 `Sandrox-Avvessa` UTS 包。版本与 SDK 依赖见 [plugin.json](../../plugin.json)，UTS 支持声明见[包元数据](../../uni_modules/Sandrox-Avvessa/package.json)。

| 接入方式 | Android 最低版本 | iOS 最低版本 |
| --- | --- | --- |
| legacy / 经典 UTS | API 21 | iOS 13 |
| uni-app x | API 23 | iOS 15 |

实际宿主还需满足所用 DCloud SDK 的要求。HBuilderX 最低版本由上述元数据声明；编译器、原生 SDK 和基座须匹配。UTS 包支持经典 App-Vue 页面及 uni-app x，不支持 Web、小程序、HarmonyOS 或经典 App-nvue。

## 安装

| 模式 | 包 | 放置目录 | 业务入口 |
| --- | --- | --- | --- |
| legacy | `avvessa-uniapp-legacy-<version>.zip` | `nativeplugins/Sandrox-Avvessa` | `@/nativeplugins/Sandrox-Avvessa/js_sdk/index.js` |
| 经典 UTS | `avvessa-uniapp-<version>.zip` | `uni_modules/Sandrox-Avvessa` | `@/uni_modules/Sandrox-Avvessa/js_sdk/index.js` |
| uni-app x | 同一个 UTS 包 | `uni_modules/Sandrox-Avvessa` | `@/uni_modules/Sandrox-Avvessa/uts_sdk/index.uts` |

解压后 `package.json` 位于上述目录根部。原生组件改变后重新构建应用或自定义基座；标准基座不包含 Avvessa。业务代码使用表中的消费入口，它们负责持续事件订阅；不要直接调用底层 `utssdk` 传输接口。各入口提供同名方法，UTS 的结构化参数和结果使用 `UTSJSONObject`。

legacy 在 `manifest.json` 的 `app-plus.nativePlugins` 选择本地 `Sandrox-Avvessa`。普通 UTS 和 uni-app x 通过源码导入引用模块。宿主负责合法的 DCloud AppID、离线 AppKey、包名、签名和系统能力；这些与极光 AppKey 是独立配置。

## iOS 生命周期

legacy 通过包内 `hooksClass` 接收应用及 APNs 回调；UTS 通过导出的 `AvvessaAppHook` 接收 `UTSiOSHookProxy` 回调。手工维护离线宿主时，必须保留 DCloud 导出的 hook 注册，不能只链接 framework。

经典 uni-app 需在 `app-plus.modules` 启用空的 `Push: {}` 模块，并设置 `app-plus.distribute.ios.pushRegisterMode: "manual"`，供 APNs 回调转发使用；不选择 UniPush 服务商，不调用其获取 CID 接口。见 [DCloud 生命周期说明](https://doc.dcloud.net.cn/uni-app-x/plugin/uts-plugin.html)。uni-app x 使用自己的运行时 hook 转发，离线宿主必须把 UIApplication 的 token、注册错误、远程通知回调交给 `UniAppXSDK`。

三种模式均需签名中的 `aps-environment` 与初始化参数、极光发送环境一致，并启用 Background Modes → Remote notifications。开发和生产 APNs 凭据由极光控制台管理，客户端不接收 p8、证书私钥或极光 Master Secret。

适配层自动协调当前通知中心的 delegate；业务页面无需重复转发。若宿主还有其他通知组件，须由该组件持有自己的 delegate 并完成回调。通知归属、前台抑制与超时行为见 [iOS 代理协作](../../native/ios/README.md#cooperate-with-an-existing-notification-delegate)。

## Android 厂商通道与图标

正式 ZIP 默认只加载基础通道。七种可选 Android 厂商组件随包放在 `tools/android-vendors.zip`，不在 DCloud 自动加载的 libs 目录。消费应用可显式选择子集，无需重新编译 Avvessa：

```sh
python3 /path/to/Sandrox-Avvessa/tools/configure-android-vendors.py \
  --module /path/to/Sandrox-Avvessa \
  --vendors xiaomi,huawei,oppo,vivo,honor,meizu
```

留空 `--vendors ''` 可移除该工具安装的组件；FCM 需明确加入 `fcm`。工具拒绝覆盖手工改动，只调整当前消费副本。

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

初始化选项 `android.smallIcon` 使用 `drawable/name` 或 `mipmap/name`，不存在的资源会拒绝初始化。省略时使用插件默认图标。资源生成命令可加 `--small-icon /absolute/path/to/icon.xml`：支持白色轮廓、透明背景的 vector XML、单张正方形 PNG，或只包含 `mdpi.png`／`hdpi.png`／`xhdpi.png`／`xxhdpi.png`／`xxxhdpi.png` 的目录。多密度尺寸依次为 24／36／48／72／96 像素；单张 PNG 最大 1024 像素，按 nodpi 打包。

使用生成的图标时，初始化须设置 `android.smallIcon: 'drawable/avvessa_consumer_notification'`。`android.color` 接受 `#RRGGBB` 或 `#AARRGGBB`。选择 FCM 时，生成器同时配置其默认图标，`--notification-color` 可设置 FCM 默认颜色。系统样式、厂商离线图标和权限合并边界见 [Android 通知配置](../../native/android/README.md#permission-channels-and-presentation)；iOS 使用应用图标。

生成清单默认移除 SDK 不需要的定位、电话状态、应用列表及外部存储权限。若宿主其他功能确实需要保留其中某项，可在本地 JSON 的 `android.retainedPermissions` 列出完整权限名，例如 `android.permission.READ_EXTERNAL_STORAGE`；生成器只对明确列出的已有移除项写入 `tools:node="merge"`。该配置不授予运行时权限。已有宿主清单中的声明仍需核对，合并后以最终清单为准。

## 使用

经典 UNI 示例：

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

调用方保存返回的订阅 ID，在所属页面或应用生命周期结束时调用 `await push.unsubscribe(subscriptionId)`，并处理 `enablePush` 的拒绝结果。`subscribe` 回调中的异步业务处理也需自行捕获错误。legacy 使用安装表中的另一条导入路径。

uni-app x 使用 UTS 消费入口，例如已初始化后的订阅：

```uts
import * as push from '@/uni_modules/Sandrox-Avvessa/uts_sdk/index.uts'

async function observePush(): Promise<string> {
  return await push.subscribe((message: UTSJSONObject) => {
    if (message.getString('kind') == 'event') {
      const event = message.getJSON('event')
      console.log(event)
      // 完成业务处理后，用 event.getString('id') 调用 acknowledge。
    }
  }, { replayPending: true } as UTSJSONObject)
}
```

UTS 初始化也使用同名方法，结构化参数传入 `UTSJSONObject`。错误对象具有 `name/code/message/nativeCode/nativeDomain`，不要求继承平台 `Error`。订阅 ID 应保存到所属页面或应用的生命周期中，结束时调用 `unsubscribe`。

冷启动恢复时，先等待 `getState()`，读取其 `consentGranted`（UTS 使用 `getBoolean('consentGranted')`）。仅在同意仍有效、且宿主记录为需要启用推送时调用 `initialize` 并恢复订阅；不要重新调用授予同意或申请权限的流程。`initialized` 只描述本次进程状态，不能用于判断以前是否启用过推送。

Android 13+ 的权限请求返回 `{requested:true, granted:null}`，表示已经发起系统请求；用户选择结果通过状态订阅或恢复前台后的 `refreshState()` 获取。iOS 等待系统授权结果返回；其设置刷新仍是异步的，随后有状态观察消息。

## 接口与事件

完整 JavaScript 类型见 [`js_sdk/index.d.ts`](js_sdk/index.d.ts)，UTS 签名见 [`uts_sdk/index.uts`](../../uni_modules/Sandrox-Avvessa/uts_sdk/index.uts)。

- 状态与权限：`getState`、`getCapabilities`、`setConsentGranted`、`initialize`、`refreshState`、`requestNotificationPermission`、`openNotificationSettings`。
- 事件：`subscribe` 返回订阅 ID；消息分为 `event/state/error`。`getPendingEvents`、`acknowledge(eventId)`、`clearPendingEvents` 显式管理持久化事件；`unsubscribe` 只移除订阅。
- 推送控制：`stopPush`、`resumePush`、`setBadge`、`clearNotifications`。空参数清除本应用全部已展示通知；指定本平台 ID 列表只清除选中通知。仅传另一平台的 ID 会报错。
- 设备关联：`setAlias/getAlias/deleteAlias`、`setTags/addTags/deleteTags/getTags/clearTags/checkTag`。插件不绑定业务账号，不在退出登录时自行修改别名。
- 展示：Android `createNotificationChannel`；iOS `setNotificationCategories` 支持普通／文本输入动作和清除事件。不支持的平台明确报 `unsupported`。

事件字段、持久化限制和重放语义以[共享契约](../../doc/contract.md#events)为准。`id` 用于消费确认，`messageId` 是原始 SDK 消息标识，两者不可混用。业务路由和账号绑定由应用处理。

参数错误、原生错误和 SDK 失败以稳定 `code` 及原始 `nativeCode/nativeDomain` 返回。未知字段、类型错误和不支持的调用不会悄悄忽略。别名标签等异步操作沿用原生核心的关联及超时机制。

## 前台展示与生命周期

在初始化参数中声明规则，三种模式共用原生实现。以下字段是应用自定义示例，插件不认识聊天业务：

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

UNI 发布 ZIP 的 iOS frameworks 仅包含 arm64 真机组件，不能直接用于模拟器。修改插件或构建模拟器组件时，见 [开发指南](../../DEVELOPMENT.md#uni-package-builds-and-checks)。
