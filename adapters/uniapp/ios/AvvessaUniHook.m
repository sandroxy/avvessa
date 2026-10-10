#import <AvvessaBridge/AvvessaBridge.h>
#import "UniPluginProtocol.h"

@interface AvvessaUniHook : NSObject <UniPluginProtocol>
@end
@implementation AvvessaUniHook
- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    [AVUniLifecycle.shared launch:launchOptions]; return YES;
}
- (void)didRegisterForRemoteNotificationsWithDeviceToken:(NSData *)token { if (token) [AVUniLifecycle.shared registerDeviceToken:token]; }
- (void)didFailToRegisterForRemoteNotificationsWithError:(NSError *)error { if (error) [AVUniLifecycle.shared registrationFailed:error]; }
- (void)didReceiveRemoteNotification:(NSDictionary *)payload { if (payload) [AVUniLifecycle.shared receive:payload]; }
- (void)applicationDidBecomeActive:(UIApplication *)application { [AVUniLifecycle.shared activate]; }
@end
