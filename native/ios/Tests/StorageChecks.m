#import "../Sources/Avvessa/AVInternal.h"

static void check(BOOL condition, NSString *message) {
    if (!condition) { fprintf(stderr, "%s\n", message.UTF8String); exit(1); }
}
static NSDictionary *event(NSString *identifier, NSString *type, double time) {
    return @{@"id": identifier, @"type": type, @"messageId": @"9007199254740993",
        @"source": @"apns", @"platform": @"ios", @"receivedAt": @(time),
        @"data": @{@"nested": @[@1, @YES, NSNull.null]}, @"raw": @{@"_j_msgid": @"9007199254740993"}};
}
int main(void) {
    @autoreleasepool {
        NSURL *directory = [NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:NSUUID.UUID.UUIDString] isDirectory:YES];
        NSError *error = nil;
        AVPushConfiguration *config = [[AVPushConfiguration alloc] initWithAppKey:@"000000000000000000000000" production:NO];
        AVEventStore *store = [[AVEventStore alloc] initWithDirectory:directory error:&error];
        check(store != nil && !error, @"Create isolated store");
        double now = NSDate.date.timeIntervalSince1970 * 1000;
        AVPushEvent *first = [store append:event(@"one", @"notificationReceived", now) deduplicationKey:@"received:message" configuration:config error:&error];
        check(first && !first.replayed, @"Persist an event");
        store = [[AVEventStore alloc] initWithDirectory:directory error:&error];
        NSArray<AVPushEvent *> *replay = [store pending:config error:&error];
        check(replay.count == 1 && [replay[0] replayed] && [[replay[0] eventID] isEqual:@"one"], @"Replay survives reopening with stable identity");
        check([store acknowledge:@"one" error:&error], @"Acknowledge the stored event");
        check(![store append:event(@"two", @"notificationReceived", now) deduplicationKey:@"received:message" configuration:config error:&error] && !error,
              @"Acknowledgement does not reset duplicate suppression");
        check([store append:event(@"three", @"notificationOpened", now) deduplicationKey:@"opened:message" configuration:config error:&error] != nil,
              @"Receive and open have independent identities");
        [store append:event(@"four", @"customMessage", now) deduplicationKey:nil configuration:config error:&error];
        [store append:event(@"five", @"customMessage", now) deduplicationKey:nil configuration:config error:&error];
        check([store pending:config error:&error].count == 3, @"Unidentified events are not deduplicated by content");
        config.maxPendingEvents = 2;
        check([store pending:config error:&error].count == 2 && store.droppedEvents == 1, @"Queue capacity evicts oldest and reports loss");
        [store clearWithError:&error];
        [store append:event(@"expired", @"notificationReceived", now - 80000) deduplicationKey:nil configuration:config error:&error];
        config.eventRetention = 60;
        check([store pending:config error:&error].count == 0 && store.droppedEvents == 2, @"Retention expiration reports loss");
        [store setStateValue:@YES forKey:@"consent" error:&error];
        [store setStateValue:@"device" forKey:@"registrationId" error:&error];
        [store append:event(@"private", @"customMessage", now) deduplicationKey:nil configuration:config error:&error];
        check([store revokeConsentWithError:&error], @"Revoke consent atomically");
        store = [[AVEventStore alloc] initWithDirectory:directory error:&error];
        check(![store.state[@"consent"] boolValue] && !store.state[@"registrationId"] && [store pending:config error:&error].count == 0,
              @"Revocation is persisted together with event and identity removal");
        NSDictionary *invalid = @{@"id": @"bad", @"raw": NSData.data};
        check(![store append:invalid deduplicationKey:nil configuration:config error:&error]
              && [error.userInfo[AVPushErrorCodeKey] isEqual:@"invalid_data"], @"Non-JSON payload is rejected explicitly");
        error = nil;
        NSURL *file = [directory URLByAppendingPathComponent:@"events.json"];
        NSData *corrupt = [NSJSONSerialization dataWithJSONObject:@{@"schema": @1, @"state": @{}, @"events": @[@"invalid"], @"dedup": @{}, @"dropped": @0} options:0 error:&error];
        [corrupt writeToURL:file options:NSDataWritingAtomic error:&error];
        check(![[AVEventStore alloc] initWithDirectory:directory error:&error]
              && [error.userInfo[AVPushErrorCodeKey] isEqual:@"storage_corrupt"], @"Corrupt storage is not silently replaced");
        check([[NSData dataWithContentsOfURL:file] isEqual:corrupt], @"Corrupt evidence remains unchanged");
        [NSFileManager.defaultManager removeItemAtURL:directory error:NULL];
        puts("Native storage checks passed; no SDK, device or push service was started.");
    }
    return 0;
}
