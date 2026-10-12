#import <Foundation/Foundation.h>
#import <UserNotifications/UserNotifications.h>

NS_ASSUME_NONNULL_BEGIN

FOUNDATION_EXPORT NSErrorDomain const AVPushErrorDomain;
FOUNDATION_EXPORT NSString *const AVPushErrorCodeKey;
FOUNDATION_EXPORT NSString *const AVPushNativeErrorCodeKey;

/** Validated, immutable rules. Values are opaque application fields, never routes or call descriptions. */
@interface AVNotificationPolicy : NSObject
@property(nonatomic, copy, readonly) NSDictionary *dictionary;
- (nullable instancetype)initWithDictionary:(NSDictionary *)dictionary error:(NSError *_Nullable *_Nullable)error NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;
@end

/** Explicit opt-in cleanup at application start, foreground entry and notification open. */
@interface AVNotificationLifecycle : NSObject
@property(nonatomic, copy, readonly) NSDictionary<NSString *, NSNumber *> *dictionary;
- (nullable instancetype)initWithDictionary:(NSDictionary *)dictionary error:(NSError *_Nullable *_Nullable)error NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;
@end

/** Copyable identity and notification defaults. The client retains a validated copy. */
@interface AVPushConfiguration : NSObject <NSCopying>
@property(nonatomic, copy, readonly) NSString *appKey;
@property(nonatomic, copy) NSString *channel;
@property(nonatomic) BOOL production;
@property(nonatomic) BOOL debug;
@property(nonatomic) UNNotificationPresentationOptions foregroundPresentationOptions;
@property(nonatomic, strong) AVNotificationPolicy *presentationPolicy;
@property(nonatomic, strong) AVNotificationLifecycle *notificationLifecycle;
@property(nonatomic) NSUInteger maxPendingEvents;
@property(nonatomic) NSTimeInterval eventRetention;
- (instancetype)initWithAppKey:(NSString *)appKey production:(BOOL)production NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;
@end

/** An immutable native record; eventID is stable across replay. */
@interface AVPushEvent : NSObject
@property(nonatomic, copy, readonly) NSString *eventID;
@property(nonatomic, copy, readonly) NSString *type;
@property(nonatomic, copy, readonly, nullable) NSString *messageID;
@property(nonatomic, copy, readonly, nullable) NSDictionary *data;
@property(nonatomic, copy, readonly) NSDictionary *raw;
@property(nonatomic, readonly) BOOL replayed;
@property(nonatomic, copy, readonly) NSDictionary *dictionary;
- (instancetype)init NS_UNAVAILABLE;
@end

@class AVPushClient;
@protocol AVPushObserver <NSObject>
- (void)pushClient:(AVPushClient *)client didReceiveEvent:(AVPushEvent *)event;
@optional
- (void)pushClient:(AVPushClient *)client didChangeState:(NSDictionary *)state;
- (void)pushClient:(AVPushClient *)client didFailWithError:(NSError *)error;
@end

typedef void (^AVPushCompletion)(NSDictionary *_Nullable result, NSError *_Nullable error);

/** Cooperative notification owner. Observing delegates are weak and must be retained by their hosts. */
@interface AVNotificationDelegate : NSObject <UNUserNotificationCenterDelegate>
- (void)addDelegate:(id<UNUserNotificationCenterDelegate>)delegate;
- (void)removeDelegate:(id<UNUserNotificationCenterDelegate>)delegate;
- (instancetype)init NS_UNAVAILABLE;
@end

/** All public operations and observer callbacks run on the main thread. */
@interface AVPushClient : NSObject <UNUserNotificationCenterDelegate>
@property(class, nonatomic, readonly) AVPushClient *sharedClient;
@property(nonatomic, copy, readonly) NSDictionary *state;
@property(nonatomic, copy, readonly) NSDictionary *capabilities;
@property(nonatomic, strong, readonly) AVNotificationDelegate *notificationDelegate;
- (instancetype)init NS_UNAVAILABLE;

- (BOOL)setConsentGranted:(BOOL)granted error:(NSError *_Nullable *_Nullable)error;
- (BOOL)initializeWithConfiguration:(AVPushConfiguration *)configuration
                     launchOptions:(nullable NSDictionary *)launchOptions
                             error:(NSError *_Nullable *_Nullable)error;
- (NSUUID *)addObserver:(id<AVPushObserver>)observer replayPending:(BOOL)replay NS_SWIFT_NAME(addObserver(_:replayPending:));
- (void)removeObserver:(NSUUID *)subscription;
- (NSArray<AVPushEvent *> *)pendingEventsWithError:(NSError *_Nullable *_Nullable)error;
- (BOOL)acknowledgeEvent:(NSString *)eventID error:(NSError *_Nullable *_Nullable)error;
- (BOOL)clearPendingEventsWithError:(NSError *_Nullable *_Nullable)error;

- (void)requestNotificationAuthorization:(UNAuthorizationOptions)options completion:(AVPushCompletion)completion NS_SWIFT_NAME(requestNotificationAuthorization(_:completion:));
- (void)refreshNotificationSettings;
- (void)openNotificationSettingsWithCompletion:(void (^)(BOOL opened))completion;
- (void)registerDeviceToken:(NSData *)deviceToken NS_SWIFT_NAME(registerDeviceToken(_:));
- (void)didFailToRegisterForRemoteNotificationsWithError:(NSError *)error NS_SWIFT_NAME(didFailToRegisterForRemoteNotifications(withError:));

/** Forward from the host's delegate, or explicitly install notificationDelegate for cooperation. */
- (BOOL)canHandleRemoteNotification:(NSDictionary *)userInfo NS_SWIFT_NAME(canHandleRemoteNotification(_:));
- (void)handleRemoteNotification:(NSDictionary *)userInfo NS_SWIFT_NAME(handleRemoteNotification(_:));
- (void)handleNotificationResponse:(UNNotificationResponse *)response NS_SWIFT_NAME(handleNotificationResponse(_:));
- (UNNotificationPresentationOptions)handleForegroundNotification:(UNNotification *)notification NS_SWIFT_NAME(handleForegroundNotification(_:));

- (void)stopPushWithCompletion:(AVPushCompletion)completion;
- (void)resumePushWithCompletion:(AVPushCompletion)completion;
- (BOOL)setActiveNotificationContext:(NSDictionary *)context error:(NSError *_Nullable *_Nullable)error;
- (void)clearActiveNotificationContext;
- (void)setBadge:(NSInteger)badge completion:(AVPushCompletion)completion;
- (void)removeDeliveredNotificationsWithIdentifiers:(NSArray<NSString *> *)identifiers;
- (void)removeAllDeliveredNotifications;
- (void)setNotificationCategories:(NSSet<UNNotificationCategory *> *)categories;

- (void)setAlias:(NSString *)alias completion:(AVPushCompletion)completion;
- (void)getAliasWithCompletion:(AVPushCompletion)completion;
- (void)deleteAliasWithCompletion:(AVPushCompletion)completion;
- (void)setTags:(NSSet<NSString *> *)tags completion:(AVPushCompletion)completion;
- (void)addTags:(NSSet<NSString *> *)tags completion:(AVPushCompletion)completion;
- (void)deleteTags:(NSSet<NSString *> *)tags completion:(AVPushCompletion)completion;
- (void)getTagsWithCompletion:(AVPushCompletion)completion;
- (void)clearTagsWithCompletion:(AVPushCompletion)completion;
- (void)checkTag:(NSString *)tag completion:(AVPushCompletion)completion;
@end

NS_ASSUME_NONNULL_END
