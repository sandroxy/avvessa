#import "include/AvvessaBridge.h"
#import <Avvessa/Avvessa.h>
#import <CoreFoundation/CoreFoundation.h>
#import <math.h>

typedef void (^AVJSONCallback)(NSString *);
static NSException *Invalid(NSString *message) { return [NSException exceptionWithName:@"AvvessaArgument" reason:message userInfo:nil]; }
static void Fields(NSDictionary *object, NSArray *keys) {
    if (![object isKindOfClass:NSDictionary.class]) @throw Invalid(@"Arguments must be an object");
    for (NSString *key in object) if (![keys containsObject:key]) @throw Invalid([@"Unknown argument: " stringByAppendingString:key]);
}
static NSString *Text(NSDictionary *object, NSString *key, NSString *fallback) {
    id value = object[key]; if (!value && fallback) return fallback;
    if (![value isKindOfClass:NSString.class] || ![value stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet].length) @throw Invalid([key stringByAppendingString:@" must be a nonempty string"]);
    return value;
}
static NSString *OptionalText(NSDictionary *object, NSString *key) {
    id value = object[key];
    if (!value) return @"";
    if (![value isKindOfClass:NSString.class]) @throw Invalid([key stringByAppendingString:@" must be a string"]);
    return value;
}
static BOOL BoolValue(NSDictionary *object, NSString *key, BOOL fallback) {
    id value = object[key]; if (!value) return fallback;
    if (![value isKindOfClass:NSNumber.class] || CFGetTypeID((__bridge CFTypeRef)value) != CFBooleanGetTypeID()) @throw Invalid([key stringByAppendingString:@" must be a boolean"]);
    return [value boolValue];
}
static double Integer(NSDictionary *object, NSString *key, double fallback, double min, double max) {
    id value = object[key]; if (!value) return fallback;
    if (![value isKindOfClass:NSNumber.class] || CFGetTypeID((__bridge CFTypeRef)value) == CFBooleanGetTypeID()) @throw Invalid([key stringByAppendingString:@" must be an integer"]);
    double number = [value doubleValue];
    if (!isfinite(number) || trunc(number) != number || number < min || number > max) @throw Invalid([key stringByAppendingString:@" is outside its integer range"]);
    return number;
}
static NSDictionary *Object(NSDictionary *object, NSString *key) {
    id value = object[key]; if (!value) return @{};
    if (![value isKindOfClass:NSDictionary.class]) @throw Invalid([key stringByAppendingString:@" must be an object"]); return value;
}
static NSArray *Array(NSDictionary *object, NSString *key) {
    id value = object[key]; if (![value isKindOfClass:NSArray.class]) @throw Invalid([key stringByAppendingString:@" must be an array"]); return value;
}
static NSArray<NSString *> *Strings(NSDictionary *object, NSString *key, BOOL empty) {
    NSArray *values = Array(object, key); NSMutableSet *seen = [NSMutableSet set];
    for (id value in values) {
        if (![value isKindOfClass:NSString.class] || ![value length] || [seen containsObject:value]) @throw Invalid([key stringByAppendingString:@" must contain unique nonempty strings"]);
        [seen addObject:value];
    }
    if (!empty && values.count == 0) @throw Invalid([key stringByAppendingString:@" must not be empty"]); return values;
}
static NSDictionary *ErrorPayload(NSError *error) {
    return @{@"code": error.userInfo[AVPushErrorCodeKey] ?: @"native_error", @"message": error.localizedDescription,
        @"nativeCode": error.userInfo[AVPushNativeErrorCodeKey] ?: NSNull.null, @"nativeDomain": error.domain};
}
static NSError *BridgeError(NSString *code, NSString *message) {
    return [NSError errorWithDomain:AVPushErrorDomain code:1 userInfo:@{AVPushErrorCodeKey:code, NSLocalizedDescriptionKey:message}];
}
static void Send(AVJSONCallback callback, id data, NSError *error) {
    NSDictionary *envelope = error ? @{@"ok":@NO, @"error":ErrorPayload(error)} : @{@"ok":@YES, @"data":data ?: NSNull.null};
    NSError *encodingError; NSData *encoded = [NSJSONSerialization dataWithJSONObject:envelope options:0 error:&encodingError];
    if (!encoded) {
        NSDictionary *failure = @{@"ok":@NO,@"error":ErrorPayload(BridgeError(@"invalid_payload", @"Cannot encode native bridge response"))};
        encoded = [NSJSONSerialization dataWithJSONObject:failure options:0 error:nil];
    }
    callback([[NSString alloc] initWithData:encoded encoding:NSUTF8StringEncoding]);
}

@interface AVBridgeObservation : NSObject <AVPushObserver>
@property(nonatomic, copy) AVJSONCallback callback;
@property(nonatomic, strong) NSUUID *token;
@property(nonatomic) BOOL active;
@end
@implementation AVBridgeObservation
- (void)pushClient:(AVPushClient *)client didReceiveEvent:(AVPushEvent *)event { if (_active) Send(_callback, @{@"kind":@"event",@"event":event.dictionary}, nil); }
- (void)pushClient:(AVPushClient *)client didChangeState:(NSDictionary *)state { if (_active) Send(_callback, @{@"kind":@"state",@"state":state}, nil); }
- (void)pushClient:(AVPushClient *)client didFailWithError:(NSError *)error { if (_active) Send(_callback, @{@"kind":@"error",@"error":ErrorPayload(error)}, nil); }
@end

@implementation AVBridgeSession {
    NSMutableDictionary<NSString *, AVBridgeObservation *> *_observations;
    BOOL _closed;
}
- (instancetype)init { if ((self = [super init])) _observations = [NSMutableDictionary dictionary]; return self; }
- (void)invoke:(NSString *)method argumentsJSON:(NSString *)arguments completion:(AVJSONCallback)completion {
    void (^work)(void) = ^{
        if (self->_closed) { Send(completion,nil,BridgeError(@"bridge_closed",@"This bridge owner has been disposed")); return; }
        NSError *parseError; id args = [NSJSONSerialization JSONObjectWithData:[arguments dataUsingEncoding:NSUTF8StringEncoding] options:0 error:&parseError];
        if (![args isKindOfClass:NSDictionary.class]) { Send(completion,nil,BridgeError(@"invalid_argument",@"Arguments must be a JSON object")); return; }
        @try { [self dispatch:method arguments:args completion:completion]; }
        @catch (NSException *exception) {
            if (![exception.name isEqual:@"AvvessaArgument"]) @throw;
            Send(completion,nil,BridgeError(@"invalid_argument",exception.reason));
        }
    };
    if (NSThread.isMainThread) work(); else dispatch_async(dispatch_get_main_queue(), work);
}
- (AVPushConfiguration *)configuration:(NSDictionary *)args error:(NSError **)error {
    Fields(args,@[@"appKey",@"channel",@"debug",@"maxPendingEvents",@"eventRetentionMilliseconds",@"android",@"ios",@"presentationPolicy",@"notificationLifecycle"]);
    NSDictionary *ios = Object(args,@"ios"), *android = Object(args,@"android");
    Fields(ios,@[@"apnsEnvironment",@"foregroundPresentation"]);
    Fields(android,@[@"smallIcon",@"color",@"notificationChannelId",@"notificationChannelName",@"presentInForeground",@"openApplicationOnClick"]);
    for (NSString *key in @[@"smallIcon",@"color",@"notificationChannelId",@"notificationChannelName"]) if (android[key]) Text(android,key,nil);
    BoolValue(android,@"presentInForeground",YES); BoolValue(android,@"openApplicationOnClick",YES);
    if (android[@"smallIcon"] && ![[NSPredicate predicateWithFormat:@"SELF MATCHES %@",@"(drawable|mipmap)/[a-z][a-z0-9_]*"] evaluateWithObject:android[@"smallIcon"]]) @throw Invalid(@"smallIcon must be drawable/name or mipmap/name");
    if (android[@"color"] && ![[NSPredicate predicateWithFormat:@"SELF MATCHES %@",@"#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"] evaluateWithObject:android[@"color"]]) @throw Invalid(@"color must be #RRGGBB or #AARRGGBB");
    NSString *environment = Text(ios,@"apnsEnvironment",nil);
    if (![@[@"development",@"production"] containsObject:environment]) @throw Invalid(@"ios.apnsEnvironment must be development or production");
    AVPushConfiguration *config = [[AVPushConfiguration alloc] initWithAppKey:Text(args,@"appKey",nil) production:[environment isEqual:@"production"]];
    config.presentationPolicy = [[AVNotificationPolicy alloc] initWithDictionary:Object(args,@"presentationPolicy") error:error];
    if (!config.presentationPolicy) return nil;
    config.notificationLifecycle = [[AVNotificationLifecycle alloc] initWithDictionary:Object(args,@"notificationLifecycle") error:error];
    if (!config.notificationLifecycle) return nil;
    config.channel = Text(args,@"channel",@"default"); config.debug = BoolValue(args,@"debug",NO);
    config.maxPendingEvents = Integer(args,@"maxPendingEvents",256,1,1000);
    config.eventRetention = Integer(args,@"eventRetentionMilliseconds",259200000,60000,2592000000) / 1000.0;
    if (ios[@"foregroundPresentation"]) {
        UNNotificationPresentationOptions options = UNNotificationPresentationOptionNone;
        for (NSString *name in Strings(ios,@"foregroundPresentation",YES)) {
            if ([name isEqual:@"sound"]) options |= UNNotificationPresentationOptionSound;
            else if ([name isEqual:@"badge"]) options |= UNNotificationPresentationOptionBadge;
            else if ([name isEqual:@"banner"]) { if (@available(iOS 14.0,*)) options |= UNNotificationPresentationOptionBanner; else options |= UNNotificationPresentationOptionAlert; }
            else if ([name isEqual:@"list"]) { if (@available(iOS 14.0,*)) options |= UNNotificationPresentationOptionList; else options |= UNNotificationPresentationOptionAlert; }
            else @throw Invalid(@"Invalid foreground presentation option");
        }
        config.foregroundPresentationOptions = options;
    }
    return config;
}
- (void)dispatch:(NSString *)method arguments:(NSDictionary *)args completion:(AVJSONCallback)completion {
    AVPushClient *client = AVPushClient.sharedClient; NSError *error = nil;
    AVPushCompletion result = ^(NSDictionary *value,NSError *failure) { Send(completion,value,failure); };
    if ([method isEqual:@"getState"]) { Fields(args,@[]); Send(completion,client.state,nil); return; }
    if ([method isEqual:@"getCapabilities"]) { Fields(args,@[]); Send(completion,client.capabilities,nil); return; }
    if ([method isEqual:@"initialize"]) {
        AVPushConfiguration *config = [self configuration:args error:&error];
        if (!config) { Send(completion,nil,error); return; }
        [AVUniLifecycle.shared activate];
        [client initializeWithConfiguration:config launchOptions:AVUniLifecycle.shared.launchOptions error:&error];
    } else if ([method isEqual:@"setConsentGranted"]) {
        Fields(args,@[@"granted"]); if (!args[@"granted"]) @throw Invalid(@"granted is required");
        [client setConsentGranted:BoolValue(args,@"granted",NO) error:&error];
    } else if ([method isEqual:@"refreshState"]) { Fields(args,@[]); [client refreshNotificationSettings]; Send(completion,client.state,nil); return;
    } else if ([method isEqual:@"requestNotificationPermission"]) {
        Fields(args,@[]); [client requestNotificationAuthorization:UNAuthorizationOptionAlert|UNAuthorizationOptionSound|UNAuthorizationOptionBadge completion:^(NSDictionary *value,NSError *failure) {
            Send(completion,value ? @{@"requested":@YES,@"granted":value[@"granted"]} : nil,failure);
        }]; return;
    } else if ([method isEqual:@"openNotificationSettings"]) {
        Fields(args,@[]); [client openNotificationSettingsWithCompletion:^(BOOL opened) { Send(completion,nil,opened ? nil : BridgeError(@"host_unavailable",@"Cannot open notification settings")); }]; return;
    } else if ([method isEqual:@"getPendingEvents"]) {
        Fields(args,@[]); NSMutableArray *values = [NSMutableArray array];
        for (AVPushEvent *event in [client pendingEventsWithError:&error]) [values addObject:event.dictionary]; Send(completion,values,error); return;
    } else if ([method isEqual:@"acknowledge"]) {
        Fields(args,@[@"eventId"]); BOOL acknowledged = [client acknowledgeEvent:Text(args,@"eventId",nil) error:&error]; Send(completion,@(acknowledged),error); return;
    } else if ([method isEqual:@"clearPendingEvents"]) { Fields(args,@[]); [client clearPendingEventsWithError:&error];
    } else if ([method isEqual:@"subscribe"]) {
        Fields(args,@[@"subscriptionId",@"replayPending"]); NSString *identifier = Text(args,@"subscriptionId",nil);
        if (identifier.length > 128 || _observations[identifier] || _observations.count >= 128) @throw Invalid(@"Duplicate, oversized or too many subscriptions");
        BOOL replay = BoolValue(args,@"replayPending",YES);
        AVBridgeObservation *observer = [AVBridgeObservation new]; observer.active = YES; observer.callback = completion;
        observer.token = [client addObserver:observer replayPending:replay]; _observations[identifier] = observer;
        Send(completion,@{@"kind":@"subscribed",@"subscriptionId":identifier},nil); return;
    } else if ([method isEqual:@"unsubscribe"]) { Fields(args,@[@"subscriptionId"]); [self remove:Text(args,@"subscriptionId",nil) notify:YES];
    } else if ([method isEqual:@"stopPush"]) { Fields(args,@[]); [client stopPushWithCompletion:result]; return;
    } else if ([method isEqual:@"resumePush"]) { Fields(args,@[]); [client resumePushWithCompletion:result]; return;
    } else if ([method isEqual:@"setActiveNotificationContext"]) { Fields(args,@[@"id",@"category"]); [client setActiveNotificationContext:args error:&error];
    } else if ([method isEqual:@"clearActiveNotificationContext"]) { Fields(args,@[]); [client clearActiveNotificationContext];
    } else if ([method isEqual:@"setBadge"]) { Fields(args,@[@"badge"]); if (!args[@"badge"]) @throw Invalid(@"badge is required"); [client setBadge:Integer(args,@"badge",0,0,INT_MAX) completion:result]; return;
    } else if ([method isEqual:@"clearNotifications"]) {
        Fields(args,@[@"androidIds",@"iosIdentifiers"]);
        if (args[@"androidIds"]) for (id value in Array(args,@"androidIds")) Integer(@{@"id":value},@"id",0,INT_MIN,INT_MAX);
        if (args[@"androidIds"] && !args[@"iosIdentifiers"]) { Send(completion,nil,BridgeError(@"unsupported",@"androidIds cannot select iOS notifications")); return; }
        if (args[@"iosIdentifiers"]) [client removeDeliveredNotificationsWithIdentifiers:Strings(args,@"iosIdentifiers",YES)]; else [client removeAllDeliveredNotifications];
    } else if ([method isEqual:@"createNotificationChannel"]) { error = BridgeError(@"unsupported",@"Notification channels require Android");
    } else if ([method isEqual:@"setNotificationCategories"]) { [client setNotificationCategories:[self categories:args]];
    } else if ([method isEqual:@"setAlias"]) { Fields(args,@[@"alias"]); [client setAlias:Text(args,@"alias",nil) completion:result]; return;
    } else if ([method isEqual:@"getAlias"]) { Fields(args,@[]); [client getAliasWithCompletion:result]; return;
    } else if ([method isEqual:@"deleteAlias"]) { Fields(args,@[]); [client deleteAliasWithCompletion:result]; return;
    } else if ([method isEqual:@"setTags"]) { Fields(args,@[@"tags"]); [client setTags:[NSSet setWithArray:Strings(args,@"tags",NO)] completion:result]; return;
    } else if ([method isEqual:@"addTags"]) { Fields(args,@[@"tags"]); [client addTags:[NSSet setWithArray:Strings(args,@"tags",NO)] completion:result]; return;
    } else if ([method isEqual:@"deleteTags"]) { Fields(args,@[@"tags"]); [client deleteTags:[NSSet setWithArray:Strings(args,@"tags",NO)] completion:result]; return;
    } else if ([method isEqual:@"getTags"]) { Fields(args,@[]); [client getTagsWithCompletion:result]; return;
    } else if ([method isEqual:@"clearTags"]) { Fields(args,@[]); [client clearTagsWithCompletion:result]; return;
    } else if ([method isEqual:@"checkTag"]) { Fields(args,@[@"tag"]); [client checkTag:Text(args,@"tag",nil) completion:result]; return;
    } else error = BridgeError(@"unsupported",[@"Unknown bridge method: " stringByAppendingString:method]);
    Send(completion,nil,error);
}
- (NSSet<UNNotificationCategory *> *)categories:(NSDictionary *)args {
    Fields(args,@[@"categories"]); NSMutableSet *categories = [NSMutableSet set], *identifiers = [NSMutableSet set];
    for (NSDictionary *category in Array(args,@"categories")) {
        Fields(category,@[@"identifier",@"actions",@"customDismissAction"]); NSString *identifier = Text(category,@"identifier",nil);
        if ([identifiers containsObject:identifier]) @throw Invalid(@"Duplicate category identifier"); [identifiers addObject:identifier];
        NSMutableArray *actions = [NSMutableArray array]; NSMutableSet *actionIDs = [NSMutableSet set];
        for (NSDictionary *action in Array(category,@"actions")) {
            Fields(action,@[@"identifier",@"title",@"foreground",@"destructive",@"authenticationRequired",@"textInput"]);
            NSString *actionID = Text(action,@"identifier",nil), *title = Text(action,@"title",nil);
            if ([actionIDs containsObject:actionID]) @throw Invalid(@"Duplicate action identifier"); [actionIDs addObject:actionID];
            UNNotificationActionOptions options = 0;
            if (BoolValue(action,@"foreground",NO)) options |= UNNotificationActionOptionForeground;
            if (BoolValue(action,@"destructive",NO)) options |= UNNotificationActionOptionDestructive;
            if (BoolValue(action,@"authenticationRequired",NO)) options |= UNNotificationActionOptionAuthenticationRequired;
            if (action[@"textInput"]) {
                NSDictionary *input = Object(action,@"textInput"); Fields(input,@[@"buttonTitle",@"placeholder"]);
                [actions addObject:[UNTextInputNotificationAction actionWithIdentifier:actionID title:title options:options textInputButtonTitle:Text(input,@"buttonTitle",nil) textInputPlaceholder:OptionalText(input,@"placeholder")]];
            } else [actions addObject:[UNNotificationAction actionWithIdentifier:actionID title:title options:options]];
        }
        if (actions.count > 4) @throw Invalid(@"A notification category supports at most four actions");
        [categories addObject:[UNNotificationCategory categoryWithIdentifier:identifier actions:actions intentIdentifiers:@[] options:BoolValue(category,@"customDismissAction",NO) ? UNNotificationCategoryOptionCustomDismissAction : 0]];
    }
    return categories;
}
- (void)remove:(NSString *)identifier notify:(BOOL)notify {
    AVBridgeObservation *observer = _observations[identifier]; if (!observer) return;
    observer.active = NO; [AVPushClient.sharedClient removeObserver:observer.token]; [_observations removeObjectForKey:identifier];
    if (notify) Send(observer.callback,@{@"kind":@"closed"},nil);
}
- (void)close {
    void (^work)(void) = ^{ self->_closed = YES; for (NSString *identifier in self->_observations.allKeys) [self remove:identifier notify:YES]; };
    if (NSThread.isMainThread) work(); else dispatch_async(dispatch_get_main_queue(), work);
}
- (void)dealloc {
    NSArray *observers = _observations.allValues;
    void (^work)(void) = ^{ for (AVBridgeObservation *observer in observers) { observer.active = NO; [AVPushClient.sharedClient removeObserver:observer.token]; } };
    if (NSThread.isMainThread) work(); else dispatch_async(dispatch_get_main_queue(), work);
}
@end
