#import "AVReactNativeLifecycle.h"
#import "Avvessa.h"
#import "AVNotificationDelegateInstallation.h"

@implementation AVReactNativeLifecycle {
    NSDictionary *_launchOptions;
    BOOL _launched;
}
+ (instancetype)shared {
    NSAssert(NSThread.isMainThread, @"Avvessa lifecycle requires the main thread");
    static AVReactNativeLifecycle *instance;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ instance = [self new]; });
    return instance;
}
- (BOOL)launched { return _launched; }
- (NSDictionary *)launchOptions { return _launchOptions; }
- (void)launch:(NSDictionary *)options {
    if (!_launched) {
        _launched = YES;
        _launchOptions = [options copy];
        [NSNotificationCenter.defaultCenter addObserver:self selector:@selector(becameActive:)
            name:UIApplicationDidBecomeActiveNotification object:nil];
    }
    [self activate];
}
- (void)becameActive:(NSNotification *)notification { [self activate]; }
- (void)activate {
    NSAssert(NSThread.isMainThread, @"Avvessa lifecycle requires the main thread");
    AVPushClient *client = AVPushClient.sharedClient;
    UNUserNotificationCenter *center = UNUserNotificationCenter.currentNotificationCenter;
    AVInstallNotificationDelegate(center, client.notificationDelegate);
    [client refreshNotificationSettings];
}
- (void)registerDeviceToken:(NSData *)token { [AVPushClient.sharedClient registerDeviceToken:token]; }
- (void)registrationFailed:(NSError *)error { [AVPushClient.sharedClient didFailToRegisterForRemoteNotificationsWithError:error]; }
- (BOOL)receive:(NSDictionary *)payload {
    AVPushClient *client = AVPushClient.sharedClient;
    if (![client canHandleRemoteNotification:payload]) return NO;
    [client handleRemoteNotification:payload];
    return YES;
}
@end
