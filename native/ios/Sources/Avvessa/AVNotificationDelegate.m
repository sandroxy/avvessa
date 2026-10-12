#import "AVInternal.h"

/** Each delegate gets an independent completion token; only this group completes the OS callback. */
@interface AVNotificationCompletion : NSObject
@property(nonatomic) NSUInteger remaining;
@property(nonatomic) UNNotificationPresentationOptions options;
@property(nonatomic) BOOL finished;
@property(nonatomic, copy) void (^completion)(UNNotificationPresentationOptions);
@end
@implementation AVNotificationCompletion
- (void)finish {
    if (_finished) return; _finished = YES;
    void (^completion)(UNNotificationPresentationOptions) = _completion; _completion = nil;
    if (completion) completion(_options);
}
- (void (^)(UNNotificationPresentationOptions))token {
    __block BOOL called = NO;
    return ^(UNNotificationPresentationOptions options) {
        void (^work)(void) = ^{
            if (called || self.finished) return; called = YES;
            self.options |= options; if (--self.remaining == 0) [self finish];
        };
        if (NSThread.isMainThread) work(); else dispatch_async(dispatch_get_main_queue(), work);
    };
}
@end

@implementation AVNotificationDelegate {
    __weak id<UNUserNotificationCenterDelegate> _primary;
    BOOL (^_owns)(NSDictionary *);
    void (^_failure)(NSError *);
    NSHashTable<id<UNUserNotificationCenterDelegate>> *_delegates;
    NSHashTable *_presented;
    NSHashTable *_responded;
    NSHashTable *_settings;
    BOOL _openingGeneralSettings;
}
- (instancetype)initWithPrimary:(id<UNUserNotificationCenterDelegate>)primary
                        owns:(BOOL (^)(NSDictionary *))owns failure:(void (^)(NSError *))failure {
    if ((self = [super init])) {
        _primary = primary; _owns = [owns copy]; _failure = [failure copy];
        NSPointerFunctionsOptions options = NSPointerFunctionsWeakMemory | NSPointerFunctionsObjectPointerPersonality;
        _delegates = [NSHashTable hashTableWithOptions:options];
        _presented = [NSHashTable hashTableWithOptions:options]; _responded = [NSHashTable hashTableWithOptions:options];
        _settings = [NSHashTable hashTableWithOptions:options];
    }
    return self;
}
- (void)addDelegate:(id<UNUserNotificationCenterDelegate>)delegate {
    AVRequireMainThread(); if (delegate && delegate != self && delegate != _primary) [_delegates addObject:delegate];
}
- (void)removeDelegate:(id<UNUserNotificationCenterDelegate>)delegate { AVRequireMainThread(); [_delegates removeObject:delegate]; }
- (AVNotificationCompletion *)group:(NSUInteger)count completion:(void (^)(UNNotificationPresentationOptions))completion {
    AVNotificationCompletion *group = [AVNotificationCompletion new]; group.remaining = count; group.completion = completion;
    if (!count) [group finish];
    else dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC), dispatch_get_main_queue(), ^{
        if (!group.finished) {
            if (self->_failure) self->_failure(AVError(@"notification_delegate_timeout", @"A notification delegate did not complete within two seconds", nil, nil));
            [group finish];
        }
    });
    return group;
}
- (void)userNotificationCenter:(UNUserNotificationCenter *)center willPresentNotification:(UNNotification *)notification
        withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completion {
    if (!NSThread.isMainThread) { dispatch_async(dispatch_get_main_queue(), ^{ [self userNotificationCenter:center willPresentNotification:notification withCompletionHandler:completion]; }); return; }
    // A wrapping delegate may forward the same object back to the coordinator, synchronously or later.
    if ([_presented containsObject:notification]) { completion(UNNotificationPresentationOptionNone); return; }
    [_presented addObject:notification];
    NSArray *delegates = _delegates.allObjects;
    if (_owns(notification.request.content.userInfo) && _primary) {
        AVNotificationCompletion *group = [self group:1 completion:completion];
        void (^done)(UNNotificationPresentationOptions) = [group token];
        __block BOOL observed = NO;
        [_primary userNotificationCenter:center willPresentNotification:notification withCompletionHandler:^(UNNotificationPresentationOptions options) {
            void (^work)(void) = ^{
                if (observed || group.finished) return; observed = YES; done(options);
                // A suppressed notification must not be re-presented by an observing SDK.
                if (!(options & ~UNNotificationPresentationOptionBadge)) return;
                for (id<UNUserNotificationCenterDelegate> delegate in delegates)
                    if ([delegate respondsToSelector:@selector(userNotificationCenter:willPresentNotification:withCompletionHandler:)])
                        [delegate userNotificationCenter:center willPresentNotification:notification withCompletionHandler:^(UNNotificationPresentationOptions ignored) {}];
            };
            if (NSThread.isMainThread) work(); else dispatch_async(dispatch_get_main_queue(), work);
        }];
        return;
    }
    NSMutableArray *receivers = [NSMutableArray array];
    for (id delegate in delegates) if ([delegate respondsToSelector:_cmd]) [receivers addObject:delegate];
    AVNotificationCompletion *group = [self group:receivers.count completion:completion];
    for (id<UNUserNotificationCenterDelegate> delegate in receivers) [delegate userNotificationCenter:center willPresentNotification:notification withCompletionHandler:[group token]];
}
- (void)userNotificationCenter:(UNUserNotificationCenter *)center didReceiveNotificationResponse:(UNNotificationResponse *)response
        withCompletionHandler:(void (^)(void))completion {
    if (!NSThread.isMainThread) { dispatch_async(dispatch_get_main_queue(), ^{ [self userNotificationCenter:center didReceiveNotificationResponse:response withCompletionHandler:completion]; }); return; }
    if ([_responded containsObject:response]) { completion(); return; } [_responded addObject:response];
    NSArray *delegates = _delegates.allObjects;
    if (_owns(response.notification.request.content.userInfo) && _primary) {
        AVNotificationCompletion *group = [self group:1 completion:^(UNNotificationPresentationOptions ignored) { completion(); }];
        void (^done)(UNNotificationPresentationOptions) = [group token];
        [_primary userNotificationCenter:center didReceiveNotificationResponse:response withCompletionHandler:^{ done(0); }];
        for (id<UNUserNotificationCenterDelegate> delegate in delegates)
            if ([delegate respondsToSelector:_cmd]) [delegate userNotificationCenter:center didReceiveNotificationResponse:response withCompletionHandler:^{}];
        return;
    }
    NSMutableArray *receivers = [NSMutableArray array];
    for (id delegate in delegates) if ([delegate respondsToSelector:_cmd]) [receivers addObject:delegate];
    AVNotificationCompletion *group = [self group:receivers.count completion:^(UNNotificationPresentationOptions ignored) { completion(); }];
    for (id<UNUserNotificationCenterDelegate> delegate in receivers) {
        void (^done)(UNNotificationPresentationOptions) = [group token];
        [delegate userNotificationCenter:center didReceiveNotificationResponse:response withCompletionHandler:^{ done(0); }];
    }
}
- (void)userNotificationCenter:(UNUserNotificationCenter *)center openSettingsForNotification:(UNNotification *)notification {
    if (!NSThread.isMainThread) { dispatch_async(dispatch_get_main_queue(), ^{ [self userNotificationCenter:center openSettingsForNotification:notification]; }); return; }
    if (notification) { if ([_settings containsObject:notification]) return; [_settings addObject:notification]; }
    else { if (_openingGeneralSettings) return; _openingGeneralSettings = YES; }
    for (id<UNUserNotificationCenterDelegate> delegate in _delegates.allObjects)
        if ([delegate respondsToSelector:_cmd]) [delegate userNotificationCenter:center openSettingsForNotification:notification];
    _openingGeneralSettings = NO;
}
@end
