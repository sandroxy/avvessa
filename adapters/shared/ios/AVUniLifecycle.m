#import "include/AvvessaBridge.h"
#import "AVUniNotificationDelegate.h"

@implementation AVUniLifecycle {
    NSDictionary *_launchOptions;
}
+ (instancetype)shared {
    static AVUniLifecycle *instance; static dispatch_once_t once;
    dispatch_once(&once, ^{ instance = [self new]; }); return instance;
}
- (NSDictionary *)launchOptions { return _launchOptions; }
- (void)install {
    AVUniInstallNotificationDelegate(UNUserNotificationCenter.currentNotificationCenter, AVPushClient.sharedClient.notificationDelegate);
}
- (void)launch:(NSDictionary *)options { _launchOptions = [options copy]; [self install]; }
- (void)activate { [self install]; [AVPushClient.sharedClient refreshNotificationSettings]; }
- (void)registerDeviceToken:(NSData *)token { [AVPushClient.sharedClient registerDeviceToken:token]; }
- (void)registrationFailed:(NSError *)error { [AVPushClient.sharedClient didFailToRegisterForRemoteNotificationsWithError:error]; }
- (void)receive:(NSDictionary *)payload { [AVPushClient.sharedClient handleRemoteNotification:payload]; }
@end
