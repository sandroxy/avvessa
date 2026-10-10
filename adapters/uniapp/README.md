# Avvessa · UNI

Avvessa 为经典 uni-app 原生插件（legacy）、经典 uni-app UTS 插件和 uni-app x 提供 Android、iOS 推送接口。三种接入方式共用 Avvessa 原生核心及官方 JPush/JCore SDK；同一个应用只能选择其中一种，不能同时安装其他 JPush 包装器。

此目录包含源代码和本地打包工具。版本、原生 SDK 锁定和平台要求见根目录 `plugin.json`；生成 ZIP 不等于已经上架插件市场。Android 最低 API 21，iOS 最低 13；实际宿主还需满足所用 DCloud SDK 的最低系统版本。UTS 工具链最低 HBuilderX 5.24。

## 安装

| 模式 | 包 | 放置目录 | 业务入口 |
| --- | --- | --- | --- |
| legacy | `avvessa-uniapp-legacy-<version>.zip` | `nativeplugins/Sandrox-Avvessa` | `@/nativeplugins/Sandrox-Avvessa/js_sdk/index.js` |
| 经典 UTS | `avvessa-uniapp-uts-<version>.zip` | `uni_modules/Sandrox-Avvessa` | `@/uni_modules/Sandrox-Avvessa/js_sdk/index.js` |
| uni-app x | 同一个 UTS 包 | `uni_modules/Sandrox-Avvessa` | `@/uni_modules/Sandrox-Avvessa/uts_sdk/index.uts` |

解压后 `package.json` 位于上述目录根部。原生组件改变后重新构建应用或自定义基座；标准基座不包含 Avvessa。经典 UTS 的 JavaScript 必须使用 `js_sdk` 入口，它为持续事件建立单独的长连接回调，避免被 DCloud 的一次性回调规则释放。uni-app x 使用 `uts_sdk` 中的 UTS 消费端 API，同样通过单一持续回调分发订阅。两种入口提供同名方法，UTS 的结构化参数和结果使用 `UTSJSONObject`。

legacy 在 `manifest.json` 的 `app-plus.nativePlugins` 选择本地 `Sandrox-Avvessa`。普通 UTS 和 uni-app x 通过源码导入引用模块。宿主负责合法的 DCloud AppID、离线 AppKey、包名、签名和系统能力；这些与极光 AppKey 是独立配置。

## iOS 生命周期

legacy 通过包内 `hooksClass` 接收应用及 APNs 回调；UTS 通过导出的 `AvvessaAppHook` 接收 `UTSiOSHookProxy` 回调。手工维护离线宿主时，必须保留 DCloud 导出的 hook 注册，不能只链接 framework。

经典 uni-app 需在 `app-plus.modules` 启用空的 `Push: {}` 模块，并设置 `app-plus.distribute.ios.pushRegisterMode: "manual"`，供 APNs 回调转发使用；不选择 UniPush 服务商，不调用其获取 CID 接口。见 [DCloud 生命周期说明](https://doc.dcloud.net.cn/uni-app-x/plugin/uts-plugin.html)。uni-app x 使用自己的运行时 hook 转发，离线宿主必须把 UIApplication 的 token、注册错误、远程通知回调交给 `UniAppXSDK`。

三种模式均需签名中的 `aps-environment` 与初始化参数、极光发送环境一致，并启用 Background Modes → Remote notifications。开发和生产 APNs 凭据由极光控制台管理，客户端不接收 p8、证书私钥或极光 Master Secret。

桥接安装协作式 `UNUserNotificationCenterDelegate`，只消费核心识别出的极光通知，其他通知转交先前的 delegate。宿主若在桥接之后替换 delegate，必须向 Avvessa 转发或保留此代理关系。应用激活时桥接会恢复代理。无需在业务页面重复转发通知。

## Android 厂商通道与图标

打包时用 `--vendors` 显式选择 `xiaomi,huawei,oppo,vivo,honor,meizu,fcm` 的子集；省略表示仅使用极光基础通道。包中只包含选中的官方厂商组件。不要同时再安装同厂商的另一套推送适配器。

厂商参数和服务 JSON 属于消费应用，不进入可公开分发的插件 ZIP。`scripts/prepare-uniapp-android.py` 将本地配置生成为仅供该应用使用的资源 AAR 和宿主清单，华为／FCM 资源分别由官方 AGConnect／Google Services 插件生成。

1. 把生成的 `configuration-release.aar` 放在 legacy 包的 `android/avvessa-consumer.aar`，或 UTS 包的 `utssdk/app-android/libs/avvessa-consumer.aar`。
2. 将同一输出目录的 `AndroidManifest.xml` 放在 UNI 项目根目录。已有该文件时，合并其中的权限规则和 `application` 子节点，保留原有宿主配置。离线 Android 工程则合并到应用模块的 `src/main/AndroidManifest.xml`。

清单规则必须位于应用层，才能可靠覆盖厂商 AAR 的默认占位参数，并保留 Avvessa 的隐私初始化边界。仅复制资源 AAR 不完整。文件位置遵循 [DCloud 原生 Android 资源约定](https://doc.dcloud.net.cn/uni-app-x/collocation/app-nativeresource-android.html)。加上消费配置后的插件目录含应用参数，只供本地打包，不能重新作为公共插件发布。

本地 JSON 示例（未选择厂商时保留空值）：

```json
{
  "android": {
    "applicationId": "com.example.pushdemo",
    "vendorModules": [],
    "vendorProperties": {},
    "googleServicesFile": null,
    "agconnectServicesFile": null
  }
}
```

| 通道 | `vendorProperties` 字段 |
| --- | --- |
| 小米 | `XIAOMI_APPID`、`XIAOMI_APPKEY` |
| OPPO | `OPPO_APPID`、`OPPO_APPKEY`、`OPPO_APPSECRET` |
| vivo | `VIVO_APPID`、`VIVO_APPKEY` |
| 荣耀 | `HONOR_APPID` |
| 魅族 | `MEIZU_APPID`、`MEIZU_APPKEY` |
| 华为 | `agconnectServicesFile` 指向匹配包名的官方 JSON |
| FCM | `googleServicesFile` 指向匹配包名的官方 JSON |

所有参数值使用字符串。资源 AAR 必须与 ZIP 中选择的厂商集合及宿主 applicationId 一致。仍需在极光／厂商后台登记正确包名、签名指纹、通道及消息分类。

`android.smallIcon` 使用 `drawable/name` 或 `mipmap/name`，不存在的资源会拒绝初始化。省略时使用插件默认图标。资源 AAR 生成器的 `--small-icon` 可接收宿主白色透明背景 vector XML，生成 `drawable/avvessa_consumer_notification`。通知色值为 `#RRGGBB` 或 `#AARRGGBB`。iOS 使用应用图标。厂商离线通知的图标／分类还受厂商规则约束。

## 使用

经典 UNI 示例：

```js
import * as push from '@/uni_modules/Sandrox-Avvessa/js_sdk/index.js';

// 用户明确同意隐私政策后执行；不能在页面加载时无条件授予。
await push.setConsentGranted(true);
await push.initialize({
  appKey: '<Jiguang AppKey>',
  channel: 'default',
  android: {
    notificationChannelId: 'messages',
    notificationChannelName: 'Messages',
    presentInForeground: true,
  },
  ios: {
    apnsEnvironment: 'development',
    foregroundPresentation: ['banner', 'list', 'sound', 'badge'],
  },
});
const subscription = await push.subscribe(message => {
  if (message.kind === 'event') {
    // 按业务需求处理 message.event；成功后明确 acknowledge(event.id)。
  }
}, { replayPending: true });
await push.requestNotificationPermission();
// 页面销毁时执行：await push.unsubscribe(subscription);
```

uni-app x 从 `@/uni_modules/Sandrox-Avvessa/uts_sdk/index.uts` 导入相同函数，传入 `UTSJSONObject`；观察消息用 `message.getString('kind')`、`message.getJSON('event')` 读取。UTS 错误对象具有 `name/code/message/nativeCode/nativeDomain`，不要求继承平台 `Error`。

初始化、隐私同意、通知权限、SDK 连接、订阅、事件消费各自独立。冷启动时只在 `getState().consentGranted` 仍为真、且应用此前完成过初始化时恢复初始化。不要为了恢复事件而自动授予隐私同意。

Android 13+ 的权限请求返回 `{requested:true, granted:null}`，表示已经发起系统请求；用户选择结果通过状态订阅或恢复前台后的 `refreshState()` 获取。iOS 等待系统授权结果返回；其设置刷新仍是异步的，随后有状态观察消息。

## 接口与事件

完整 JavaScript 类型见 [`js_sdk/index.d.ts`](js_sdk/index.d.ts)。

- 状态与权限：`getState`、`getCapabilities`、`setConsentGranted`、`initialize`、`refreshState`、`requestNotificationPermission`、`openNotificationSettings`。
- 事件：`subscribe` 返回订阅 ID；消息分为 `event/state/error`。`getPendingEvents`、`acknowledge(eventId)`、`clearPendingEvents` 显式管理持久化事件；`unsubscribe` 只移除订阅。
- 推送控制：`stopPush`、`resumePush`、`setBadge`、`clearNotifications`。空参数清除本应用全部已展示通知；指定本平台 ID 列表只清除选中通知。仅传另一平台的 ID 会报错。
- 设备关联：`setAlias/getAlias/deleteAlias`、`setTags/addTags/deleteTags/getTags/clearTags/checkTag`。插件不绑定业务账号，不在退出登录时自行修改别名。
- 展示：Android `createNotificationChannel`；iOS `setNotificationCategories` 支持普通／文本输入动作和清除事件。不支持的平台明确报 `unsupported`。

事件包含 `id/type/messageId/platform/source/receivedAt/data/raw/replayed` 等原生字段；不解释业务 payload，不跳业务页面，不将 messageId 转为数字，也不在回调后自动确认。`id` 是消费确认标识，`messageId` 是服务端消息标识，两者不可混用。挂起、取消订阅或页面销毁均不等于停止 SDK。

参数错误、原生错误和 SDK 失败以稳定 `code` 及原始 `nativeCode/nativeDomain` 返回。未知字段、类型错误和不支持的调用不会悄悄忽略。别名标签等异步操作沿用原生核心的关联及超时机制。

## 从源码构建

需要 JDK 17、Android SDK 36、Xcode，以及下载到本地的官方 DCloud Android AAR／iOS SDK。Swift Package Manager 从根目录 `Package.resolved` 解析官方极光 SDK：

```sh
xcodebuild -resolvePackageDependencies -scheme Avvessa -derivedDataPath .build/uniapp-sdk
python3 scripts/package-uniapp.py \
  --source-packages .build/uniapp-sdk/SourcePackages \
  --dcloud-android-aar /absolute/path/to/uniapp-v8-release.aar \
  --dcloud-ios-sdk /absolute/path/to/classic-ios-sdk \
  --vendors huawei,honor,xiaomi,meizu,oppo,vivo
python3 scripts/prepare-uniapp-android.py --config /absolute/path/to/consumer.local.json
```

默认 ZIP 输出在忽略的 `dist/uniapp/`，包含 SHA-256 和文件来源清单。iOS ZIP 包含 arm64 真机静态 frameworks；`build-uniapp-ios.py --sdk iphonesimulator` 可单独检查模拟器组件，不能将真机 framework 直接用于模拟器。

本地验证：

```sh
node --test adapters/uniapp/tests/*.test.mjs
python3 scripts/check-uniapp-uts.py dist/uniapp/avvessa-uniapp-uts-0.1.0.zip \
  --hbuilder-contents /absolute/path/to/HBuilderX \
  --classic-ios-sdk /absolute/path/to/classic-ios-sdk \
  --x-android-sdk /absolute/path/to/uniapp-x-android-sdk \
  --x-ios-sdk /absolute/path/to/uniapp-x-ios-sdk
```

编译检查覆盖经典／X 的 Kotlin 和 Swift。它不安装应用、不发送推送，也不替代真机上的前后台、通知点击冷启动、事件重放和厂商通道验证。
