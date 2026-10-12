#import "../Sources/Avvessa/AVInternal.h"

static void Check(BOOL value, NSString *message) { if (!value) { NSLog(@"FAIL: %@",message); exit(1); } }
@interface NotificationFixture : NSObject
@property(nonatomic, strong) UNNotificationRequest *request;
@end
@implementation NotificationFixture @end
@interface ResponseFixture : NSObject
@property(nonatomic, strong) UNNotification *notification;
@end
@implementation ResponseFixture @end
@interface DelegateFixture : NSObject <UNUserNotificationCenterDelegate>
@property(nonatomic) NSUInteger received, opened;
@property(nonatomic) UNNotificationPresentationOptions options;
@property(nonatomic) BOOL duplicate, silent;
@property(nonatomic, weak) AVNotificationDelegate *forward;
@end
@implementation DelegateFixture
- (void)userNotificationCenter:(UNUserNotificationCenter *)center willPresentNotification:(UNNotification *)notification withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completion {
    _received++;
    if (_forward) { [_forward userNotificationCenter:center willPresentNotification:notification withCompletionHandler:completion]; return; }
    if (_silent) return;
    completion(_options); if (_duplicate) completion(_options);
}
- (void)userNotificationCenter:(UNUserNotificationCenter *)center didReceiveNotificationResponse:(UNNotificationResponse *)response withCompletionHandler:(void (^)(void))completion {
    _opened++; completion(); if (_duplicate) completion();
}
@end
static UNNotification *Notification(BOOL owned) {
    NotificationFixture *notification = [NotificationFixture new];
    UNMutableNotificationContent *content = [UNMutableNotificationContent new]; content.userInfo = @{@"owned":@(owned)};
    notification.request = [UNNotificationRequest requestWithIdentifier:NSUUID.UUID.UUIDString content:content trigger:nil];
    return (UNNotification *)notification;
}
int main(void) { @autoreleasepool {
    UNUserNotificationCenter *center = (UNUserNotificationCenter *)[NSObject new];
    DelegateFixture *primary = [DelegateFixture new], *one = [DelegateFixture new], *two = [DelegateFixture new];
    __block NSUInteger failures = 0;
    AVNotificationDelegate *coordinator = [[AVNotificationDelegate alloc] initWithPrimary:primary owns:^BOOL(NSDictionary *payload) { return [payload[@"owned"] boolValue]; } failure:^(NSError *error) { failures++; }];
    [coordinator addDelegate:one]; [coordinator addDelegate:two]; [coordinator addDelegate:one]; [coordinator addDelegate:coordinator];
    one.options = UNNotificationPresentationOptionBadge; one.duplicate = YES; two.options = UNNotificationPresentationOptionSound;
    __block NSUInteger completions = 0;
    [coordinator userNotificationCenter:center willPresentNotification:Notification(NO) withCompletionHandler:^(UNNotificationPresentationOptions options) {
        completions++; Check(options == (UNNotificationPresentationOptionBadge|UNNotificationPresentationOptionSound), @"Foreign notification combines observers");
    }];
    Check(completions == 1 && one.received == 1 && two.received == 1 && primary.received == 0, @"Duplicate completions and registrations do not repeat work");
    primary.options = UNNotificationPresentationOptionBadge; primary.duplicate = YES;
    [coordinator userNotificationCenter:center willPresentNotification:Notification(YES) withCompletionHandler:^(UNNotificationPresentationOptions options) {
        completions++; Check(options == UNNotificationPresentationOptionBadge, @"Observers cannot override suppression");
    }];
    Check(completions == 2 && primary.received == 1 && one.received == 1 && two.received == 1, @"Suppression avoids observer re-presentation");
    primary.options = UNNotificationPresentationOptionSound;
    [coordinator userNotificationCenter:center willPresentNotification:Notification(YES) withCompletionHandler:^(UNNotificationPresentationOptions options) { completions++; Check(options == UNNotificationPresentationOptionSound,@"Primary owns presentation"); }];
    Check(completions == 3 && one.received == 2 && two.received == 2, @"Owned notifications reach observing delegates");
    // The primary is deliberately faulty: it calls its completion twice. Observers must still run once.
    ResponseFixture *response = [ResponseFixture new]; response.notification = Notification(YES);
    [coordinator userNotificationCenter:center didReceiveNotificationResponse:(UNNotificationResponse *)response withCompletionHandler:^{ completions++; }];
    Check(completions == 4 && primary.opened == 1 && one.opened == 1 && two.opened == 1,@"Click completes once");
    [coordinator removeDelegate:two]; one.forward = coordinator;
    [coordinator userNotificationCenter:center willPresentNotification:Notification(NO) withCompletionHandler:^(UNNotificationPresentationOptions options) { completions++; }];
    Check(completions == 5, @"A wrapping observer cannot recurse into the coordinator"); one.forward = nil;
    one.silent = YES;
    [coordinator userNotificationCenter:center willPresentNotification:Notification(NO) withCompletionHandler:^(UNNotificationPresentationOptions options) { completions++; }];
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:2.5];
    while (completions < 6 && deadline.timeIntervalSinceNow > 0) [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    Check(completions == 6 && failures == 1, @"A missing observer completion reports an error and releases the system callback");
    puts("Notification delegate coexistence checks passed without a device or push service.");
} return 0; }
