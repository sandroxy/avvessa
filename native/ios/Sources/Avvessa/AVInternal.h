#import "include/Avvessa.h"

NS_ASSUME_NONNULL_BEGIN
NSError *AVError(NSString *code, NSString *message, NSNumber *_Nullable nativeCode, NSError *_Nullable underlying);
void AVRequireMainThread(void);
UNNotificationPresentationOptions AVDefaultPresentationOptions(void);

@interface AVPushConfiguration (Internal)
- (BOOL)validateWithError:(NSError *_Nullable *_Nullable)error;
- (NSDictionary *)dictionary;
+ (nullable instancetype)fromDictionary:(NSDictionary *)dictionary error:(NSError *_Nullable *_Nullable)error;
@end
@interface AVPushEvent (Internal)
- (instancetype)initWithDictionary:(NSDictionary *)dictionary replayed:(BOOL)replayed;
@end

@interface AVEventStore : NSObject
@property(nonatomic, readonly) NSDictionary *state;
@property(nonatomic, readonly) NSUInteger droppedEvents;
- (nullable instancetype)initWithDirectory:(NSURL *)directory error:(NSError *_Nullable *_Nullable)error;
- (BOOL)setStateValue:(nullable id)value forKey:(NSString *)key error:(NSError *_Nullable *_Nullable)error;
- (BOOL)revokeConsentWithError:(NSError *_Nullable *_Nullable)error;
- (nullable AVPushEvent *)append:(NSDictionary *)event deduplicationKey:(nullable NSString *)key
                  configuration:(AVPushConfiguration *)configuration error:(NSError *_Nullable *_Nullable)error;
- (nullable NSArray<AVPushEvent *> *)pending:(AVPushConfiguration *)configuration error:(NSError *_Nullable *_Nullable)error;
- (BOOL)acknowledge:(NSString *)eventID error:(NSError *_Nullable *_Nullable)error;
- (BOOL)clearWithError:(NSError *_Nullable *_Nullable)error;
@end
NS_ASSUME_NONNULL_END
