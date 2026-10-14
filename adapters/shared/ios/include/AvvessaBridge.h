#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <UserNotifications/UserNotifications.h>

NS_ASSUME_NONNULL_BEGIN
/** Framework-owned JSON transport. All SDK work is serialized on the main queue. */
@interface AVBridgeSession : NSObject
/** Host hooks run on the main queue immediately before initialization. */
- (instancetype)initWithLaunchOptionsProvider:(NSDictionary *_Nullable (^_Nullable)(void))provider
                                  activation:(void (^_Nullable)(void))activation;
- (void)invoke:(NSString *)method argumentsJSON:(NSString *)arguments completion:(void (^)(NSString *json))completion NS_SWIFT_NAME(invoke(_:argumentsJSON:completion:));
- (void)close;
@end

/** UNI host callbacks, independent of either DCloud binary runtime. */
@interface AVUniLifecycle : NSObject
@property(class, nonatomic, readonly) AVUniLifecycle *shared;
@property(nonatomic, copy, readonly, nullable) NSDictionary *launchOptions;
- (void)launch:(nullable NSDictionary *)options NS_SWIFT_NAME(launch(_:));
- (void)activate;
- (AVBridgeSession *)makeSession;
- (void)registerDeviceToken:(NSData *)token NS_SWIFT_NAME(registerDeviceToken(_:));
- (void)registrationFailed:(NSError *)error NS_SWIFT_NAME(registrationFailed(_:));
- (void)receive:(NSDictionary *)payload NS_SWIFT_NAME(receive(_:));
@end
NS_ASSUME_NONNULL_END
