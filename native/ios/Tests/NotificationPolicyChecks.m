#import "../Sources/Avvessa/AVInternal.h"

static void Check(BOOL condition, NSString *message) { if (!condition) { NSLog(@"FAIL: %@", message); exit(1); } }
int main(int argc, const char *argv[]) { @autoreleasepool {
    Check(argc == 2, @"Fixture path required");
    NSDictionary *fixtures = [NSJSONSerialization JSONObjectWithData:[NSData dataWithContentsOfFile:@(argv[1])] options:0 error:nil];
    Check(fixtures != nil, @"Readable fixtures");
    for (NSDictionary *entry in fixtures[@"cases"]) {
        NSError *error; AVNotificationPolicy *policy = [[AVNotificationPolicy alloc] initWithDictionary:entry[@"policy"] error:&error];
        Check(policy != nil, error.description);
        NSDictionary *context = [entry[@"context"] isKindOfClass:NSDictionary.class] ? entry[@"context"] : nil;
        NSString *actual = [policy suppressionReasonForData:entry[@"data"] context:context platform:entry[@"platform"]];
        Check([(actual ?: NSNull.null) isEqual:entry[@"reason"]], entry[@"name"]);
    }
    for (NSDictionary *value in fixtures[@"invalidPolicies"]) {
        NSError *error; Check(![[AVNotificationPolicy alloc] initWithDictionary:value error:&error] && error != nil, @"Invalid rules must fail");
    }
    NSError *error;
    Check(!AVNotificationContext(@{},&error), @"Empty context rejected");
    Check(![[AVNotificationLifecycle alloc] initWithDictionary:@{@"clearBadgeOnStart":@"true"} error:&error], @"Lifecycle rejects string booleans");
    AVPushConfiguration *config = [[AVPushConfiguration alloc] initWithAppKey:@"000000000000000000000000" production:NO];
    config.presentationPolicy = [[AVNotificationPolicy alloc] initWithDictionary:fixtures[@"cases"][0][@"policy"] error:&error];
    config.notificationLifecycle = [[AVNotificationLifecycle alloc] initWithDictionary:@{@"clearBadgeOnOpen":@YES} error:&error];
    AVPushConfiguration *copy = [AVPushConfiguration fromDictionary:config.dictionary error:&error];
    Check([copy.dictionary isEqual:config.dictionary], @"Configuration roundtrip");
    puts("Notification policy contract checks passed without loading JPush.");
} return 0; }
