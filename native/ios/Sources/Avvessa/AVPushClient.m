#import "AVInternal.h"
#import <JPUSHService.h>
#import <JGInforCollectionAuth.h>
#import <UIKit/UIKit.h>

@implementation AVPushClient {
    AVEventStore *_store;
    NSError *_storageError;
    AVPushConfiguration *_configuration;
    NSMutableDictionary<NSUUID *, id<AVPushObserver>> *_observers;
    NSMutableDictionary<NSNumber *, AVPushCompletion> *_operations;
    NSMutableArray *_notificationTokens;
    BOOL _initialized;
    BOOL _apnsRegistered;
    NSNumber *_connected;
    NSNumber *_stopped;
    NSString *_permission;
    NSInteger _sequence;
    NSUInteger _consentGeneration;
    NSDictionary *_lastError;
    NSDictionary *_activeNotificationContext;
    BOOL _enteredBackground;
    BOOL _pendingOpenCleanup;
}

+ (AVPushClient *)sharedClient {
    AVRequireMainThread();
    static AVPushClient *client; static dispatch_once_t once;
    dispatch_once(&once, ^{ client = [[self alloc] initPrivate]; });
    return client;
}
- (instancetype)initPrivate {
    if ((self = [super init])) {
        _observers = [NSMutableDictionary dictionary]; _operations = [NSMutableDictionary dictionary];
        _notificationTokens = [NSMutableArray array]; _permission = @"unknown";
        NSError *storageError = nil;
        NSURL *directory = [NSFileManager.defaultManager URLForDirectory:NSApplicationSupportDirectory
                inDomain:NSUserDomainMask appropriateForURL:nil create:YES error:&storageError];
        if (directory) _store = [[AVEventStore alloc] initWithDirectory:[directory URLByAppendingPathComponent:@"Avvessa" isDirectory:YES] error:&storageError];
        NSDictionary *saved = _store.state[@"configuration"];
        if (saved) _configuration = [AVPushConfiguration fromDictionary:saved error:&storageError];
        _storageError = storageError;
        __weak AVPushClient *weakSelf = self;
        _notificationDelegate = [[AVNotificationDelegate alloc] initWithPrimary:self owns:^BOOL(NSDictionary *payload) {
            return [weakSelf canHandleRemoteNotification:payload];
        } failure:^(NSError *error) { [weakSelf report:error]; }];
        [JGInforCollectionAuth JCollectionAuth:^(JGInforCollectionAuthItems *items) { items.isAuth = [self consent]; }];
    }
    return self;
}
- (BOOL)consent { return _store && !_storageError && [_store.state[@"consent"] boolValue]; }
- (BOOL)checkStorage:(NSError **)error {
    if (_store && !_storageError) return YES;
    if (error) *error = AVError(@"storage_unavailable", @"Push storage is unavailable", nil, _storageError);
    return NO;
}
- (BOOL)checkInitialized:(NSError **)error {
    if (![self checkStorage:error]) return NO;
    if (![self consent]) { if (error) *error = AVError(@"consent_required", @"Explicit consent is required", nil, nil); return NO; }
    if (!_initialized) { if (error) *error = AVError(@"not_initialized", @"Initialize the push client first", nil, nil); return NO; }
    return YES;
}
- (NSDictionary *)state {
    AVRequireMainThread();
    return @{@"consentGranted": @([self consent]), @"initialized": @(_initialized),
             @"registrationId": [self consent] ? (_store.state[@"registrationId"] ?: NSNull.null) : NSNull.null,
             @"connected": [self consent] ? (_connected ?: NSNull.null) : NSNull.null,
             @"stopped": [self consent] ? (_stopped ?: NSNull.null) : NSNull.null,
             @"notificationPermission": _permission, @"apnsRegistered": @(_apnsRegistered),
             @"droppedEvents": @(_store.droppedEvents), @"lastError": _lastError ?: NSNull.null};
}
- (NSDictionary *)capabilities {
    return @{@"platform": @"ios", @"apns": @YES, @"durableEvents": @YES,
             @"notificationCategories": @YES, @"presentationPolicy": @YES, @"notificationLifecycle": @YES, @"incomingCalls": @NO};
}
- (BOOL)setConsentGranted:(BOOL)granted error:(NSError **)error {
    AVRequireMainThread();
    if (![self checkStorage:error]) return NO;
    if (granted) {
        if (![_store setStateValue:@YES forKey:@"consent" error:error]) return NO;
    } else {
        if (![_store revokeConsentWithError:error]) return NO;
        _consentGeneration++; _activeNotificationContext = nil; _pendingOpenCleanup = NO;
        if (_initialized) [JPUSHService turnOffPush:^(NSInteger code) {
            if (code != 0) [self report:AVError(@"sdk_error", @"SDK could not confirm push shutdown", @(code), nil)];
        }];
        _apnsRegistered = NO; _connected = nil; _stopped = nil;
        for (NSNumber *key in [_operations.allKeys copy])
            [self finish:key result:nil error:AVError(@"consent_required", @"Consent was revoked", nil, nil)];
    }
    [JGInforCollectionAuth JCollectionAuth:^(JGInforCollectionAuthItems *items) { items.isAuth = granted; }];
    [self emitState];
    return YES;
}
- (BOOL)initializeWithConfiguration:(AVPushConfiguration *)configuration launchOptions:(NSDictionary *)launchOptions error:(NSError **)error {
    AVRequireMainThread();
    if (![self checkStorage:error]) return NO;
    if (![self consent]) { if (error) *error = AVError(@"consent_required", @"Explicit consent is required before initialization", nil, nil); return NO; }
    if (!configuration) { if (error) *error = AVError(@"invalid_configuration", @"Configuration is required", nil, nil); return NO; }
    if (![configuration validateWithError:error]) return NO;
    if (_initialized) {
        if (![[_configuration dictionary] isEqual:[configuration dictionary]]) {
            if (error) *error = AVError(@"configuration_conflict", @"The process already uses a different configuration", nil, nil);
            return NO;
        }
        return YES;
    }
    if (_configuration && (![_configuration.appKey isEqual:configuration.appKey] || _configuration.production != configuration.production)) {
        if (error) *error = AVError(@"configuration_conflict", @"Changing application identity requires reinstalling the test application", nil, nil);
        return NO;
    }
    if (![_store setStateValue:configuration.dictionary forKey:@"configuration" error:error]) return NO;
    _configuration = [configuration copy];
    [JGInforCollectionAuth JCollectionAuth:^(JGInforCollectionAuthItems *items) { items.isAuth = YES; }];
    [self observeSDK];
    if (configuration.debug) [JPUSHService setDebugMode]; else [JPUSHService setLogOFF];
    [JPUSHService setLocationEanable:NO];
    [JPUSHService setSmartPushEnable:NO];
    [JPUSHService setDataInsightsEnable:NO];
    [JPUSHService setupWithOption:launchOptions appKey:configuration.appKey channel:configuration.channel
                apsForProduction:configuration.production advertisingIdentifier:nil];
    _initialized = YES;
    [self cleanupBadge:_pendingOpenCleanup || [_configuration.notificationLifecycle.dictionary[@"clearBadgeOnStart"] boolValue]
            delivered:[_configuration.notificationLifecycle.dictionary[@"clearDeliveredOnStart"] boolValue]];
    _pendingOpenCleanup = NO;
    [JPUSHService registrationIDCompletionHandler:^(int code, NSString *registration) {
        dispatch_async(dispatch_get_main_queue(), ^{
            if (![self consent]) return;
            if (code == 0 && registration.length) [self saveRegistration:registration];
            else if (code != 0) [self report:AVError(@"registration_failed", @"JPush registration is not available", @(code), nil)];
        });
    }];
    [JPUSHService getPushStatus:^(NSInteger code, BOOL stopped) {
        dispatch_async(dispatch_get_main_queue(), ^{
            if (![self consent]) return;
            if (code == 0) self->_stopped = @(stopped);
            else [self report:AVError(@"sdk_error", @"Cannot query push status", @(code), nil)];
            [self emitState];
        });
    }];
    [self refreshNotificationSettings];
    [self emitState];
    return YES;
}

- (void)observeSDK {
    NSNotificationCenter *center = NSNotificationCenter.defaultCenter;
    NSArray *names = @[kJPFNetworkDidLoginNotification, kJPFNetworkDidCloseNotification,
                       kJPFNetworkDidRegisterNotification, kJPFNetworkFailedRegisterNotification,
                       kJPFNetworkDidReceiveMessageNotification, kJPFServiceErrorNotification];
    __weak AVPushClient *weakClient = self;
    [_notificationTokens addObject:[center addObserverForName:UIApplicationDidEnterBackgroundNotification object:nil queue:NSOperationQueue.mainQueue usingBlock:^(NSNotification *note) {
        AVPushClient *client = weakClient; if (client) client->_enteredBackground = YES;
    }]];
    [_notificationTokens addObject:[center addObserverForName:UIApplicationDidBecomeActiveNotification object:nil queue:NSOperationQueue.mainQueue usingBlock:^(NSNotification *note) {
        AVPushClient *client = weakClient; if (!client) return;
        if (client->_enteredBackground && [client consent]) {
            [client cleanupBadge:[client->_configuration.notificationLifecycle.dictionary[@"clearBadgeOnForeground"] boolValue]
                       delivered:[client->_configuration.notificationLifecycle.dictionary[@"clearDeliveredOnForeground"] boolValue]];
        }
        client->_enteredBackground = NO; [client refreshNotificationSettings];
    }]];
    for (NSString *name in names) {
        __weak AVPushClient *weakSelf = self;
        id token = [center addObserverForName:name object:nil queue:NSOperationQueue.mainQueue usingBlock:^(NSNotification *notification) {
            [weakSelf sdkNotification:notification];
        }];
        [_notificationTokens addObject:token];
    }
}
- (void)sdkNotification:(NSNotification *)notification {
    if (![self consent]) return;
    NSString *name = notification.name;
    if ([name isEqual:kJPFNetworkDidLoginNotification]) { _connected = @YES; [self saveRegistration:JPUSHService.registrationID]; }
    else if ([name isEqual:kJPFNetworkDidCloseNotification]) _connected = @NO;
    else if ([name isEqual:kJPFNetworkDidRegisterNotification]) [self saveRegistration:JPUSHService.registrationID];
    else if ([name isEqual:kJPFNetworkDidReceiveMessageNotification]) {
        NSDictionary *raw = notification.userInfo ?: @{};
        id identifier = raw[@"msgid"];
        NSString *messageID = [identifier isKindOfClass:NSString.class] ? identifier
            : ([identifier isKindOfClass:NSNumber.class] ? [identifier stringValue] : nil);
        NSDictionary *extras = raw[@"extras"] ?: @{};
        if (![extras isKindOfClass:NSDictionary.class]) {
            extras = nil;
            [self report:AVError(@"invalid_payload", @"SDK extras are not a dictionary; the original value remains in raw", nil, nil)];
        }
        [self record:@"customMessage" messageID:messageID transportID:nil source:@"jpush"
                data:extras raw:raw title:[raw[@"title"] isKindOfClass:NSString.class] ? raw[@"title"] : nil
                body:[raw[@"content"] isKindOfClass:NSString.class] ? raw[@"content"] : nil];
    } else [self report:AVError(@"sdk_error", [name isEqual:kJPFNetworkFailedRegisterNotification] ? @"JPush registration failed" : @"JPush reported an error", nil, nil)];
    [self emitState];
}
- (void)saveRegistration:(NSString *)registration {
    if (!registration.length || ![self consent]) return;
    NSError *error;
    if (![_store setStateValue:registration forKey:@"registrationId" error:&error]) [self report:error];
    [self emitState];
}

- (NSUUID *)addObserver:(id<AVPushObserver>)observer replayPending:(BOOL)replay {
    AVRequireMainThread(); NSParameterAssert(observer);
    NSUUID *identifier = NSUUID.UUID; _observers[identifier] = observer;
    if (replay) {
        NSError *error;
        for (AVPushEvent *event in [self pendingEventsWithError:&error]) [self deliver:event to:identifier];
        if (error) [self report:error];
    }
    NSDictionary *state = self.state;
    dispatch_async(dispatch_get_main_queue(), ^{
        id<AVPushObserver> current = self->_observers[identifier];
        if ([current respondsToSelector:@selector(pushClient:didChangeState:)]) [current pushClient:self didChangeState:state];
    });
    return identifier;
}
- (void)removeObserver:(NSUUID *)subscription { AVRequireMainThread(); [_observers removeObjectForKey:subscription]; }
- (NSArray<AVPushEvent *> *)pendingEventsWithError:(NSError **)error {
    AVRequireMainThread();
    if (![self checkStorage:error]) return @[];
    if (![self consent] || !_configuration) return @[];
    return [_store pending:_configuration error:error] ?: @[];
}
- (BOOL)acknowledgeEvent:(NSString *)eventID error:(NSError **)error {
    AVRequireMainThread(); return [self checkStorage:error] && [_store acknowledge:eventID error:error];
}
- (BOOL)clearPendingEventsWithError:(NSError **)error {
    AVRequireMainThread(); return [self checkStorage:error] && [_store clearWithError:error];
}
- (void)record:(NSString *)type messageID:(NSString *)messageID transportID:(NSString *)transportID
        source:(NSString *)source data:(NSDictionary *)data raw:(NSDictionary *)raw title:(NSString *)title body:(NSString *)body {
    [self record:type messageID:messageID transportID:transportID source:source data:data raw:raw title:title body:body action:nil];
}
- (void)record:(NSString *)type messageID:(NSString *)messageID transportID:(NSString *)transportID
        source:(NSString *)source data:(NSDictionary *)data raw:(NSDictionary *)raw title:(NSString *)title body:(NSString *)body action:(NSDictionary *)action {
    [self record:type messageID:messageID transportID:transportID source:source data:data raw:raw title:title body:body action:action presentation:nil];
}
- (void)record:(NSString *)type messageID:(NSString *)messageID transportID:(NSString *)transportID
        source:(NSString *)source data:(NSDictionary *)data raw:(NSDictionary *)raw title:(NSString *)title body:(NSString *)body action:(NSDictionary *)action presentation:(NSDictionary *)presentation {
    if (![self consent] || !_configuration) return;
    NSDictionary *record = @{@"id": NSUUID.UUID.UUIDString, @"type": type, @"messageId": messageID ?: NSNull.null,
        @"transportId": transportID ?: NSNull.null, @"source": source, @"platform": @"ios",
        @"receivedAt": @(NSDate.date.timeIntervalSince1970 * 1000), @"data": data ?: NSNull.null, @"raw": raw,
        @"title": title ?: NSNull.null, @"body": body ?: NSNull.null, @"action": action ?: NSNull.null, @"presentation": presentation ?: NSNull.null};
    NSMutableArray *keys = [NSMutableArray array];
    NSArray *phase = @[type, action[@"identifier"] ?: @""];
    for (NSArray *identity in @[@[@"message",messageID ?: @""], @[@"request",transportID ?: @""]]) {
        if (![identity[1] length]) continue;
        NSData *encoded = [NSJSONSerialization dataWithJSONObject:[phase arrayByAddingObjectsFromArray:identity] options:0 error:nil];
        [keys addObject:[[NSString alloc] initWithData:encoded encoding:NSUTF8StringEncoding]];
    }
    NSError *error;
    AVPushEvent *event = [_store append:record deduplicationKeys:keys messageID:messageID configuration:_configuration error:&error];
    if (error) { [self report:AVError(@"storage_write", @"Could not persist push event", nil, error)]; return; }
    if (event && [type isEqual:@"notificationOpened"] && [_configuration.notificationLifecycle.dictionary[@"clearBadgeOnOpen"] boolValue]) {
        if (_initialized) [self cleanupBadge:YES delivered:NO]; else _pendingOpenCleanup = YES;
    }
    if (event) for (NSUUID *identifier in [_observers.allKeys copy]) [self deliver:event to:identifier];
}
- (void)deliver:(AVPushEvent *)event to:(NSUUID *)identifier {
    NSUInteger generation = _consentGeneration;
    dispatch_async(dispatch_get_main_queue(), ^{
        id<AVPushObserver> observer = self->_observers[identifier];
        if (observer && [self consent] && generation == self->_consentGeneration) [observer pushClient:self didReceiveEvent:event];
    });
}
- (void)emitState {
    NSDictionary *state = self.state;
    for (NSUUID *identifier in [_observers.allKeys copy]) dispatch_async(dispatch_get_main_queue(), ^{
        id<AVPushObserver> observer = self->_observers[identifier];
        if ([observer respondsToSelector:@selector(pushClient:didChangeState:)]) [observer pushClient:self didChangeState:state];
    });
}
- (void)report:(NSError *)error {
    if (!error) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        self->_lastError = @{@"code": error.userInfo[AVPushErrorCodeKey] ?: @"native_error",
                             @"nativeCode": error.userInfo[AVPushNativeErrorCodeKey] ?: NSNull.null};
        NSLog(@"Avvessa: %@", error.userInfo[AVPushErrorCodeKey] ?: @"native_error");
        for (id<AVPushObserver> observer in [self->_observers.allValues copy])
            if ([observer respondsToSelector:@selector(pushClient:didFailWithError:)]) [observer pushClient:self didFailWithError:error];
    });
}

- (void)requestNotificationAuthorization:(UNAuthorizationOptions)options completion:(AVPushCompletion)completion {
    AVRequireMainThread(); NSParameterAssert(completion);
    [UNUserNotificationCenter.currentNotificationCenter requestAuthorizationWithOptions:options completionHandler:^(BOOL granted, NSError *requestError) {
        dispatch_async(dispatch_get_main_queue(), ^{
            if (granted && self->_initialized && [self consent] && ![self->_stopped boolValue])
                [UIApplication.sharedApplication registerForRemoteNotifications];
            [self refreshNotificationSettings];
            completion(requestError ? nil : @{@"granted": @(granted)}, requestError);
        });
    }];
}
- (void)refreshNotificationSettings {
    AVRequireMainThread();
    [UNUserNotificationCenter.currentNotificationCenter getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *settings) {
        dispatch_async(dispatch_get_main_queue(), ^{
            switch (settings.authorizationStatus) {
                case UNAuthorizationStatusNotDetermined: self->_permission = @"notDetermined"; break;
                case UNAuthorizationStatusDenied: self->_permission = @"denied"; break;
                case UNAuthorizationStatusAuthorized: self->_permission = @"authorized"; break;
                case UNAuthorizationStatusProvisional: self->_permission = @"provisional"; break;
                default: self->_permission = @"unknown"; break;
            }
            if (self->_initialized && [self consent] && ![self->_stopped boolValue]
                && (settings.authorizationStatus == UNAuthorizationStatusAuthorized || settings.authorizationStatus == UNAuthorizationStatusProvisional))
                [UIApplication.sharedApplication registerForRemoteNotifications];
            [self emitState];
        });
    }];
}
- (void)openNotificationSettingsWithCompletion:(void (^)(BOOL))completion {
    AVRequireMainThread();
    NSURL *url = [NSURL URLWithString:UIApplicationOpenSettingsURLString];
    [UIApplication.sharedApplication openURL:url options:@{} completionHandler:completion];
}
- (void)registerDeviceToken:(NSData *)deviceToken {
    AVRequireMainThread(); NSError *error;
    if (![self checkInitialized:&error]) { [self report:error]; return; }
    if (!deviceToken.length) { [self report:AVError(@"invalid_argument", @"APNs token is empty", nil, nil)]; return; }
    [JPUSHService registerDeviceToken:deviceToken]; _apnsRegistered = YES; [self emitState];
}
- (void)didFailToRegisterForRemoteNotificationsWithError:(NSError *)error {
    AVRequireMainThread(); _apnsRegistered = NO;
    [self report:AVError(@"apns_registration_failed", @"APNs registration failed", nil, error)]; [self emitState];
}

- (BOOL)canHandleRemoteNotification:(NSDictionary *)userInfo {
    id identifier = userInfo[@"_j_msgid"];
    return ([identifier isKindOfClass:NSString.class] && [identifier length] > 0) || [identifier isKindOfClass:NSNumber.class];
}
- (void)recordRemote:(NSDictionary *)userInfo type:(NSString *)type requestID:(NSString *)requestID action:(NSDictionary *)action {
    [self recordRemote:userInfo type:type requestID:requestID action:action presentation:nil];
}
- (void)recordRemote:(NSDictionary *)userInfo type:(NSString *)type requestID:(NSString *)requestID action:(NSDictionary *)action presentation:(NSDictionary *)presentation {
    if (![self consent] || ![self canHandleRemoteNotification:userInfo]) return;
    id identifier = userInfo[@"_j_msgid"];
    NSString *messageID = [identifier isKindOfClass:NSString.class] ? identifier
        : ([identifier isKindOfClass:NSNumber.class] ? [identifier stringValue] : nil);
    NSDictionary *aps = [userInfo[@"aps"] isKindOfClass:NSDictionary.class] ? userInfo[@"aps"] : @{};
    id alert = aps[@"alert"]; NSString *title, *body;
    if ([alert isKindOfClass:NSString.class]) body = alert;
    else if ([alert isKindOfClass:NSDictionary.class]) {
        if ([alert[@"title"] isKindOfClass:NSString.class]) title = alert[@"title"];
        if ([alert[@"body"] isKindOfClass:NSString.class]) body = alert[@"body"];
    }
    NSMutableDictionary *data = [userInfo mutableCopy]; [data removeObjectForKey:@"aps"]; [data removeObjectForKey:@"_j_msgid"];
    [self record:type messageID:messageID transportID:requestID source:@"apns" data:data raw:userInfo title:title body:body action:action presentation:presentation];
    if (_initialized && messageID.length) [JPUSHService handleRemoteNotification:userInfo];
}
- (void)handleRemoteNotification:(NSDictionary *)userInfo { AVRequireMainThread(); [self recordRemote:userInfo type:@"notificationReceived" requestID:nil action:nil]; }
- (void)handleNotificationResponse:(UNNotificationResponse *)response {
    AVRequireMainThread();
    NSString *type = [response.actionIdentifier isEqual:UNNotificationDismissActionIdentifier] ? @"notificationDismissed" : @"notificationOpened";
    NSMutableDictionary *action = [@{@"identifier": response.actionIdentifier} mutableCopy];
    if ([response isKindOfClass:UNTextInputNotificationResponse.class]) action[@"userText"] = ((UNTextInputNotificationResponse *)response).userText;
    [self recordRemote:response.notification.request.content.userInfo type:type requestID:response.notification.request.identifier action:action];
}
- (UNNotificationPresentationOptions)handleForegroundNotification:(UNNotification *)notification {
    AVRequireMainThread();
    if (![self canHandleRemoteNotification:notification.request.content.userInfo])
        return AVDefaultPresentationOptions();
    if (![self consent] || !_configuration) return UNNotificationPresentationOptionNone;
    NSMutableDictionary *data = [notification.request.content.userInfo mutableCopy]; [data removeObjectForKey:@"aps"]; [data removeObjectForKey:@"_j_msgid"];
    UNNotificationPresentationOptions options = _configuration.foregroundPresentationOptions;
    NSString *reason = (options & ~UNNotificationPresentationOptionBadge)
        ? [_configuration.presentationPolicy suppressionReasonForData:data context:_activeNotificationContext platform:@"ios"]
        : @"foregroundDisabled";
    if (reason) options &= UNNotificationPresentationOptionBadge;
    [self recordRemote:notification.request.content.userInfo type:@"notificationReceived" requestID:notification.request.identifier action:nil
            presentation:@{@"suppressed": @(reason != nil), @"reason": reason ?: NSNull.null}];
    return options;
}
- (void)userNotificationCenter:(UNUserNotificationCenter *)center willPresentNotification:(UNNotification *)notification
        withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler {
    dispatch_async(dispatch_get_main_queue(), ^{ completionHandler([self handleForegroundNotification:notification]); });
}
- (void)userNotificationCenter:(UNUserNotificationCenter *)center didReceiveNotificationResponse:(UNNotificationResponse *)response
        withCompletionHandler:(void (^)(void))completionHandler {
    dispatch_async(dispatch_get_main_queue(), ^{ [self handleNotificationResponse:response]; completionHandler(); });
}

- (NSNumber *)begin:(AVPushCompletion)completion {
    AVRequireMainThread(); NSParameterAssert(completion);
    NSError *error;
    if (![self checkInitialized:&error]) { completion(nil, error); return nil; }
    do { _sequence = _sequence == INT_MAX ? 1 : _sequence + 1; } while (_operations[@(_sequence)]);
    NSNumber *identifier = @(_sequence); _operations[identifier] = [completion copy];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 30 * NSEC_PER_SEC), dispatch_get_main_queue(), ^{
        [self finish:identifier result:nil error:AVError(@"timeout", @"SDK operation did not complete within 30 seconds", nil, nil)];
    });
    return identifier;
}
- (void)finish:(NSNumber *)identifier result:(NSDictionary *)result error:(NSError *)error {
    if (!NSThread.isMainThread) { dispatch_async(dispatch_get_main_queue(), ^{ [self finish:identifier result:result error:error]; }); return; }
    AVPushCompletion completion = _operations[identifier];
    if (!completion) return;
    if (error) _lastError = @{@"code": error.userInfo[AVPushErrorCodeKey] ?: @"native_error",
                              @"nativeCode": error.userInfo[AVPushNativeErrorCodeKey] ?: NSNull.null};
    [_operations removeObjectForKey:identifier]; completion(result, error);
}
- (void)finish:(NSNumber *)identifier code:(NSInteger)code result:(NSDictionary *)result {
    [self finish:identifier result:code == 0 ? result : nil
           error:code == 0 ? nil : AVError(@"sdk_error", @"JPush operation failed", @(code), nil)];
}
- (void)stopPushWithCompletion:(AVPushCompletion)completion {
    NSNumber *operation = [self begin:completion]; if (!operation) return;
    [JPUSHService turnOffPush:^(NSInteger code) { dispatch_async(dispatch_get_main_queue(), ^{
        if (!self->_operations[operation] || ![self consent]) return;
        if (code == 0) { self->_stopped = @YES; self->_connected = @NO; self->_apnsRegistered = NO; [self emitState]; }
        [self finish:operation code:code result:@{@"stopped": @YES}];
    }); }];
}
- (void)resumePushWithCompletion:(AVPushCompletion)completion {
    NSNumber *operation = [self begin:completion]; if (!operation) return;
    [JPUSHService turnOnPush];
    [JPUSHService getPushStatus:^(NSInteger code, BOOL stopped) { dispatch_async(dispatch_get_main_queue(), ^{
        if (!self->_operations[operation] || ![self consent]) return;
        if (code == 0) { self->_stopped = @(stopped); [self saveRegistration:JPUSHService.registrationID]; [self refreshNotificationSettings]; [self emitState]; }
        [self finish:operation code:code result:@{@"stopped": @(stopped)}];
    }); }];
}
- (BOOL)setActiveNotificationContext:(NSDictionary *)context error:(NSError **)error {
    AVRequireMainThread(); if (![self checkInitialized:error]) return NO;
    NSDictionary *validated = AVNotificationContext(context,error); if (!validated) return NO;
    _activeNotificationContext = validated; return YES;
}
- (void)clearActiveNotificationContext { AVRequireMainThread(); _activeNotificationContext = nil; }
- (void)cleanupBadge:(BOOL)badge delivered:(BOOL)delivered {
    if (!_initialized || ![self consent]) return;
    if (delivered) [self removeAllDeliveredNotifications];
    __weak AVPushClient *weakSelf = self;
    if (badge) [self setBadge:0 completion:^(NSDictionary *result, NSError *error) { if (error) [weakSelf report:error]; }];
}
- (void)setBadge:(NSInteger)badge completion:(AVPushCompletion)completion {
    AVRequireMainThread(); NSParameterAssert(completion);
    if (badge < 0) { completion(nil, AVError(@"invalid_argument", @"Badge must be non-negative", nil, nil)); return; }
    NSNumber *operation = [self begin:completion]; if (!operation) return;
    if (![JPUSHService setBadge:badge]) { [self finish:operation result:nil error:AVError(@"sdk_error", @"JPush could not update its badge value", nil, nil)]; return; }
    if (@available(iOS 16.0, *)) {
        [UNUserNotificationCenter.currentNotificationCenter setBadgeCount:badge withCompletionHandler:^(NSError *error) {
            dispatch_async(dispatch_get_main_queue(), ^{ [self finish:operation result:error ? nil : @{@"badge": @(badge)} error:error]; });
        }];
    } else { UIApplication.sharedApplication.applicationIconBadgeNumber = badge; [self finish:operation result:@{@"badge": @(badge)} error:nil]; }
}
- (void)removeDeliveredNotificationsWithIdentifiers:(NSArray<NSString *> *)identifiers {
    AVRequireMainThread(); [UNUserNotificationCenter.currentNotificationCenter removeDeliveredNotificationsWithIdentifiers:identifiers];
}
- (void)removeAllDeliveredNotifications { AVRequireMainThread(); [UNUserNotificationCenter.currentNotificationCenter removeAllDeliveredNotifications]; }
- (void)setNotificationCategories:(NSSet<UNNotificationCategory *> *)categories {
    AVRequireMainThread();
    [UNUserNotificationCenter.currentNotificationCenter getNotificationCategoriesWithCompletionHandler:^(NSSet<UNNotificationCategory *> *existing) {
        NSMutableDictionary *merged = [NSMutableDictionary dictionary];
        for (UNNotificationCategory *category in existing) merged[category.identifier] = category;
        for (UNNotificationCategory *category in categories) merged[category.identifier] = category;
        [UNUserNotificationCenter.currentNotificationCenter setNotificationCategories:[NSSet setWithArray:merged.allValues]];
    }];
}

- (BOOL)validateLabel:(NSString *)label completion:(AVPushCompletion)completion {
    AVRequireMainThread(); NSParameterAssert(completion);
    if (![label isKindOfClass:NSString.class] || !label.length || [label lengthOfBytesUsingEncoding:NSUTF8StringEncoding] > 40) {
        completion(nil, AVError(@"invalid_argument", @"Alias and tags require 1 to 40 UTF-8 bytes", nil, nil)); return NO;
    }
    return YES;
}
- (void)setAlias:(NSString *)alias completion:(AVPushCompletion)completion {
    if (![self validateLabel:alias completion:completion]) return;
    NSNumber *operation = [self begin:completion]; if (!operation) return;
    [JPUSHService setAlias:alias completion:^(NSInteger code, NSString *value, NSInteger sequence) {
        [self finish:operation code:code result:@{@"alias": value ?: NSNull.null}];
    } seq:operation.integerValue];
}
- (void)getAliasWithCompletion:(AVPushCompletion)completion {
    NSNumber *operation = [self begin:completion]; if (!operation) return;
    [JPUSHService getAlias:^(NSInteger code, NSString *value, NSInteger sequence) {
        [self finish:operation code:code result:@{@"alias": value ?: NSNull.null}];
    } seq:operation.integerValue];
}
- (void)deleteAliasWithCompletion:(AVPushCompletion)completion {
    NSNumber *operation = [self begin:completion]; if (!operation) return;
    [JPUSHService deleteAlias:^(NSInteger code, NSString *value, NSInteger sequence) {
        [self finish:operation code:code result:@{@"alias": value ?: NSNull.null}];
    } seq:operation.integerValue];
}
- (void)tags:(NSSet<NSString *> *)tags operation:(NSString *)action completion:(AVPushCompletion)completion {
    AVRequireMainThread(); NSParameterAssert(completion);
    tags = [tags copy];
    if (!tags.count || tags.count > 1000) { completion(nil, AVError(@"invalid_argument", @"Provide between 1 and 1000 tags", nil, nil)); return; }
    for (NSString *tag in tags) if (![self validateLabel:tag completion:completion]) return;
    NSNumber *operation = [self begin:completion]; if (!operation) return;
    JPUSHTagsOperationCompletion callback = ^(NSInteger code, NSSet *values, NSInteger sequence) {
        [self finish:operation code:code result:@{@"tags": values.allObjects ?: @[]}];
    };
    if ([action isEqual:@"set"]) [JPUSHService setTags:tags completion:callback seq:operation.integerValue];
    else if ([action isEqual:@"add"]) [JPUSHService addTags:tags completion:callback seq:operation.integerValue];
    else [JPUSHService deleteTags:tags completion:callback seq:operation.integerValue];
}
- (void)setTags:(NSSet<NSString *> *)tags completion:(AVPushCompletion)completion { [self tags:tags operation:@"set" completion:completion]; }
- (void)addTags:(NSSet<NSString *> *)tags completion:(AVPushCompletion)completion { [self tags:tags operation:@"add" completion:completion]; }
- (void)deleteTags:(NSSet<NSString *> *)tags completion:(AVPushCompletion)completion { [self tags:tags operation:@"delete" completion:completion]; }
- (void)getTagsWithCompletion:(AVPushCompletion)completion {
    NSNumber *operation = [self begin:completion]; if (!operation) return;
    [JPUSHService getAllTags:^(NSInteger code, NSSet *tags, NSInteger sequence) {
        [self finish:operation code:code result:@{@"tags": tags.allObjects ?: @[]}];
    } seq:operation.integerValue];
}
- (void)clearTagsWithCompletion:(AVPushCompletion)completion {
    NSNumber *operation = [self begin:completion]; if (!operation) return;
    [JPUSHService cleanTags:^(NSInteger code, NSSet *tags, NSInteger sequence) {
        [self finish:operation code:code result:@{@"tags": tags.allObjects ?: @[]}];
    } seq:operation.integerValue];
}
- (void)checkTag:(NSString *)tag completion:(AVPushCompletion)completion {
    if (![self validateLabel:tag completion:completion]) return;
    NSNumber *operation = [self begin:completion]; if (!operation) return;
    [JPUSHService validTag:tag completion:^(NSInteger code, NSSet *tags, NSInteger sequence, BOOL bound) {
        [self finish:operation code:code result:@{@"tag": tag, @"bound": @(bound)}];
    } seq:operation.integerValue];
}
@end
