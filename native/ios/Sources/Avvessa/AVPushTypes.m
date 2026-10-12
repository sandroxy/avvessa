#import "AVInternal.h"
#import <math.h>

NSErrorDomain const AVPushErrorDomain = @"com.sandrox.avvessa";
NSString *const AVPushErrorCodeKey = @"avvessaCode";
NSString *const AVPushNativeErrorCodeKey = @"nativeCode";

NSError *AVError(NSString *code, NSString *message, NSNumber *nativeCode, NSError *underlying) {
    NSMutableDictionary *info = [@{NSLocalizedDescriptionKey: message, AVPushErrorCodeKey: code} mutableCopy];
    if (nativeCode) info[AVPushNativeErrorCodeKey] = nativeCode;
    if (underlying) info[NSUnderlyingErrorKey] = underlying;
    return [NSError errorWithDomain:AVPushErrorDomain code:1 userInfo:info];
}

void AVRequireMainThread(void) {
    if (![NSThread isMainThread])
        [NSException raise:NSInternalInconsistencyException format:@"Avvessa operations require the main thread"];
}

UNNotificationPresentationOptions AVDefaultPresentationOptions(void) {
    if (@available(iOS 14.0, macOS 11.0, *))
        return UNNotificationPresentationOptionList | UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionSound | UNNotificationPresentationOptionBadge;
    // iOS 13 uses alert; later systems use list and banner above.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    return UNNotificationPresentationOptionAlert | UNNotificationPresentationOptionSound | UNNotificationPresentationOptionBadge;
#pragma clang diagnostic pop
}

@implementation AVPushConfiguration
- (instancetype)initWithAppKey:(NSString *)appKey production:(BOOL)production {
    if ((self = [super init])) {
        _appKey = [appKey copy]; _production = production; _channel = @"default";
        _foregroundPresentationOptions = AVDefaultPresentationOptions();
        _presentationPolicy = [[AVNotificationPolicy alloc] initWithDictionary:@{} error:nil];
        _notificationLifecycle = [[AVNotificationLifecycle alloc] initWithDictionary:@{} error:nil];
        _maxPendingEvents = 256; _eventRetention = 72 * 3600;
    }
    return self;
}
- (id)copyWithZone:(NSZone *)zone {
    AVPushConfiguration *copy = [[[self class] allocWithZone:zone] initWithAppKey:self.appKey production:self.production];
    copy.channel = self.channel; copy.debug = self.debug;
    copy.foregroundPresentationOptions = self.foregroundPresentationOptions;
    copy.presentationPolicy = self.presentationPolicy; copy.notificationLifecycle = self.notificationLifecycle;
    copy.maxPendingEvents = self.maxPendingEvents; copy.eventRetention = self.eventRetention;
    return copy;
}
- (BOOL)validateWithError:(NSError **)error {
    NSRegularExpression *pattern = [NSRegularExpression regularExpressionWithPattern:@"^[0-9a-fA-F]{24}$" options:0 error:nil];
    BOOL valid = self.appKey && [pattern numberOfMatchesInString:self.appKey options:0 range:NSMakeRange(0, self.appKey.length)] == 1
        && [self.channel stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet].length > 0
        && [self.presentationPolicy isKindOfClass:AVNotificationPolicy.class] && [self.notificationLifecycle isKindOfClass:AVNotificationLifecycle.class]
        && self.maxPendingEvents >= 1 && self.maxPendingEvents <= 1000
        && isfinite(self.eventRetention) && self.eventRetention >= 60 && self.eventRetention <= 30 * 24 * 3600;
    if (!valid && error) *error = AVError(@"invalid_configuration", @"Invalid AppKey, channel or event retention", nil, nil);
    return valid;
}
- (NSDictionary *)dictionary {
    return @{@"appKey": self.appKey, @"channel": self.channel, @"production": @(self.production), @"debug": @(self.debug),
             @"presentationPolicy": self.presentationPolicy.dictionary, @"notificationLifecycle": self.notificationLifecycle.dictionary,
             @"presentation": @(self.foregroundPresentationOptions), @"maxEvents": @(self.maxPendingEvents), @"ttl": @(self.eventRetention)};
}
+ (instancetype)fromDictionary:(NSDictionary *)dictionary error:(NSError **)error {
    if (![dictionary isKindOfClass:NSDictionary.class] || ![dictionary[@"appKey"] isKindOfClass:NSString.class] || ![dictionary[@"channel"] isKindOfClass:NSString.class]) {
        if (error) *error = AVError(@"storage_corrupt", @"Stored configuration is invalid", nil, nil);
        return nil;
    }
    for (NSString *key in @[@"production", @"debug", @"presentation", @"maxEvents", @"ttl"]) {
        if (![dictionary[key] isKindOfClass:NSNumber.class]) {
            if (error) *error = AVError(@"storage_corrupt", @"Stored configuration is invalid", nil, nil);
            return nil;
        }
    }
    AVPushConfiguration *config = [[self alloc] initWithAppKey:dictionary[@"appKey"] production:[dictionary[@"production"] boolValue]];
    config.channel = dictionary[@"channel"]; config.debug = [dictionary[@"debug"] boolValue];
    config.foregroundPresentationOptions = [dictionary[@"presentation"] unsignedIntegerValue];
    config.presentationPolicy = [[AVNotificationPolicy alloc] initWithDictionary:dictionary[@"presentationPolicy"] ?: @{} error:error];
    config.notificationLifecycle = [[AVNotificationLifecycle alloc] initWithDictionary:dictionary[@"notificationLifecycle"] ?: @{} error:error];
    if (!config.presentationPolicy || !config.notificationLifecycle) return nil;
    config.maxPendingEvents = [dictionary[@"maxEvents"] unsignedIntegerValue]; config.eventRetention = [dictionary[@"ttl"] doubleValue];
    return [config validateWithError:error] ? config : nil;
}
@end

@implementation AVPushEvent {
    NSDictionary *_dictionary;
}
- (instancetype)initWithDictionary:(NSDictionary *)dictionary replayed:(BOOL)replayed {
    if ((self = [super init])) {
        NSMutableDictionary *copy = [dictionary mutableCopy]; copy[@"replayed"] = @(replayed);
        // Immutable deep copy prevents a consumer from mutating queued events.
        NSData *data = [NSJSONSerialization dataWithJSONObject:copy options:0 error:nil];
        _dictionary = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
    }
    return self;
}
- (NSDictionary *)dictionary { return _dictionary; }
- (NSString *)eventID { return _dictionary[@"id"]; }
- (NSString *)type { return _dictionary[@"type"]; }
- (NSString *)messageID { return [_dictionary[@"messageId"] isKindOfClass:NSString.class] ? _dictionary[@"messageId"] : nil; }
- (NSDictionary *)data { return [_dictionary[@"data"] isKindOfClass:NSDictionary.class] ? _dictionary[@"data"] : nil; }
- (NSDictionary *)raw { return _dictionary[@"raw"]; }
- (BOOL)replayed { return [_dictionary[@"replayed"] boolValue]; }
@end
