#import "include/AvvessaBridge.h"
#import <Avvessa/Avvessa.h>

@implementation AVUniLifecycle {
    __weak id<UNUserNotificationCenterDelegate> _previous;
    NSDictionary *_launchOptions;
}
+ (instancetype)shared {
    static AVUniLifecycle *instance; static dispatch_once_t once;
    dispatch_once(&once, ^{ instance = [self new]; }); return instance;
}
- (NSDictionary *)launchOptions { return _launchOptions; }
- (void)install {
    NSAssert(NSThread.isMainThread, @"Notification delegate changes require the main thread");
    UNUserNotificationCenter *center = UNUserNotificationCenter.currentNotificationCenter;
    if (center.delegate != self) { _previous = center.delegate; center.delegate = self; }
}
- (void)launch:(NSDictionary *)options { _launchOptions = [options copy]; [self install]; }
- (void)activate { [self install]; [AVPushClient.sharedClient refreshNotificationSettings]; }
- (void)registerDeviceToken:(NSData *)token { [AVPushClient.sharedClient registerDeviceToken:token]; }
- (void)registrationFailed:(NSError *)error { [AVPushClient.sharedClient didFailToRegisterForRemoteNotificationsWithError:error]; }
- (void)receive:(NSDictionary *)payload { [AVPushClient.sharedClient handleRemoteNotification:payload]; }
- (void)userNotificationCenter:(UNUserNotificationCenter *)center willPresentNotification:(UNNotification *)notification
        withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completion {
    dispatch_async(dispatch_get_main_queue(), ^{
        AVPushClient *client = AVPushClient.sharedClient;
        if ([client canHandleRemoteNotification:notification.request.content.userInfo]) completion([client handleForegroundNotification:notification]);
        else if ([self->_previous respondsToSelector:_cmd]) [self->_previous userNotificationCenter:center willPresentNotification:notification withCompletionHandler:completion];
        else completion(UNNotificationPresentationOptionNone);
    });
}
- (void)userNotificationCenter:(UNUserNotificationCenter *)center didReceiveNotificationResponse:(UNNotificationResponse *)response
        withCompletionHandler:(void (^)(void))completion {
    dispatch_async(dispatch_get_main_queue(), ^{
        AVPushClient *client = AVPushClient.sharedClient;
        if ([client canHandleRemoteNotification:response.notification.request.content.userInfo]) { [client handleNotificationResponse:response]; completion(); }
        else if ([self->_previous respondsToSelector:_cmd]) [self->_previous userNotificationCenter:center didReceiveNotificationResponse:response withCompletionHandler:completion];
        else completion();
    });
}
- (void)userNotificationCenter:(UNUserNotificationCenter *)center openSettingsForNotification:(UNNotification *)notification {
    if ([_previous respondsToSelector:_cmd]) [_previous userNotificationCenter:center openSettingsForNotification:notification];
}
@end
