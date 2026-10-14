#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
NS_ASSUME_NONNULL_BEGIN

/** Install at application launch, before starting React. Never initializes the SDK or grants consent. */
@interface AVReactNativeLifecycle : NSObject
@property(class, nonatomic, readonly) AVReactNativeLifecycle *shared;
@property(nonatomic, readonly) BOOL launched;
@property(nonatomic, copy, readonly, nullable) NSDictionary *launchOptions;
- (void)launch:(nullable NSDictionary *)options NS_SWIFT_NAME(launch(_:));
/** Ensure delegate coordination is installed and refresh system notification settings. */
- (void)activate;
- (void)registerDeviceToken:(NSData *)token NS_SWIFT_NAME(registerDeviceToken(_:));
- (void)registrationFailed:(NSError *)error NS_SWIFT_NAME(registrationFailed(_:));
/** Returns NO for another provider's payload. The host owns the OS completion callback. */
- (BOOL)receive:(NSDictionary *)payload NS_SWIFT_NAME(receive(_:));
@end
NS_ASSUME_NONNULL_END
