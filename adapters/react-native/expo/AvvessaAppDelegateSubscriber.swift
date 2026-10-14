import ExpoModulesCore
import AvvessaReactNative

public final class AvvessaAppDelegateSubscriber: ExpoAppDelegateSubscriber {
  public func application(_ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
    AVReactNativeLifecycle.shared.launch(launchOptions)
    return true
  }

  public func application(_ application: UIApplication,
    didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
    AVReactNativeLifecycle.shared.registerDeviceToken(deviceToken)
  }

  public func application(_ application: UIApplication,
    didFailToRegisterForRemoteNotificationsWithError error: Error) {
    AVReactNativeLifecycle.shared.registrationFailed(error as NSError)
  }

  public func application(_ application: UIApplication,
    didReceiveRemoteNotification userInfo: [AnyHashable: Any],
    fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
    let handled = AVReactNativeLifecycle.shared.receive(userInfo)
    completionHandler(handled ? .newData : .noData)
  }
}
