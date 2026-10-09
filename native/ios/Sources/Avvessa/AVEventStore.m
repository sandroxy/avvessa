#import "AVInternal.h"
#import <math.h>

static BOOL AVValidStoredRoot(id root) {
    if (![root isKindOfClass:NSDictionary.class] || ![root[@"schema"] isKindOfClass:NSNumber.class]
        || [root[@"schema"] integerValue] != 1 || ![root[@"state"] isKindOfClass:NSDictionary.class]
        || ![root[@"events"] isKindOfClass:NSArray.class] || ![root[@"dedup"] isKindOfClass:NSDictionary.class]
        || ![root[@"dropped"] isKindOfClass:NSNumber.class] || [root[@"dropped"] doubleValue] < 0) return NO;
    NSDictionary *state = root[@"state"];
    if (state[@"consent"] && ![state[@"consent"] isKindOfClass:NSNumber.class]) return NO;
    if (state[@"registrationId"] && ![state[@"registrationId"] isKindOfClass:NSString.class]) return NO;
    if (state[@"configuration"] && ![state[@"configuration"] isKindOfClass:NSDictionary.class]) return NO;
    for (id event in root[@"events"]) {
        if (![event isKindOfClass:NSDictionary.class] || ![event[@"id"] isKindOfClass:NSString.class]
            || ![event[@"id"] length] || ![event[@"type"] isKindOfClass:NSString.class]
            || (![event[@"data"] isKindOfClass:NSDictionary.class] && event[@"data"] != NSNull.null)
            || ![event[@"raw"] isKindOfClass:NSDictionary.class]
            || ![event[@"receivedAt"] isKindOfClass:NSNumber.class] || !isfinite([event[@"receivedAt"] doubleValue])) return NO;
    }
    for (id expiry in [root[@"dedup"] allValues])
        if (![expiry isKindOfClass:NSNumber.class] || !isfinite([expiry doubleValue])) return NO;
    return YES;
}

@implementation AVEventStore {
    NSURL *_file;
    NSMutableDictionary *_root;
}
- (instancetype)initWithDirectory:(NSURL *)directory error:(NSError **)error {
    if ((self = [super init])) {
        NSFileManager *manager = NSFileManager.defaultManager;
        if (![manager createDirectoryAtURL:directory withIntermediateDirectories:YES
                               attributes:@{NSFileProtectionKey: NSFileProtectionCompleteUntilFirstUserAuthentication} error:error]) return nil;
        if (![directory setResourceValue:@YES forKey:NSURLIsExcludedFromBackupKey error:error]) return nil;
        _file = [directory URLByAppendingPathComponent:@"events.json"];
        if ([manager fileExistsAtPath:_file.path]) {
            NSData *data = [NSData dataWithContentsOfURL:_file options:0 error:error];
            if (!data) return nil;
            id decoded = [NSJSONSerialization JSONObjectWithData:data options:NSJSONReadingMutableContainers error:error];
            if (!decoded) return nil;
            if (!AVValidStoredRoot(decoded)) {
                if (error) *error = AVError(@"storage_corrupt", @"Stored push data is invalid", nil, nil);
                return nil;
            }
            _root = decoded;
        } else {
            _root = [@{@"schema": @1, @"state": [NSMutableDictionary dictionary], @"events": [NSMutableArray array],
                       @"dedup": [NSMutableDictionary dictionary], @"dropped": @0} mutableCopy];
        }
    }
    return self;
}
- (NSDictionary *)state { return [_root[@"state"] copy]; }
- (NSUInteger)droppedEvents { return [_root[@"dropped"] unsignedIntegerValue]; }
- (NSMutableDictionary *)candidate {
    NSData *data = [NSJSONSerialization dataWithJSONObject:_root options:0 error:nil];
    return [NSJSONSerialization JSONObjectWithData:data options:NSJSONReadingMutableContainers error:nil];
}
- (BOOL)commit:(NSMutableDictionary *)candidate error:(NSError **)error {
    NSData *data = [NSJSONSerialization dataWithJSONObject:candidate options:NSJSONWritingSortedKeys error:error];
    if (!data || ![data writeToURL:_file options:NSDataWritingAtomic | NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication error:error]) return NO;
    _root = candidate;
    return YES;
}
- (BOOL)setStateValue:(id)value forKey:(NSString *)key error:(NSError **)error {
    NSMutableDictionary *candidate = [self candidate];
    if (value) candidate[@"state"][key] = value; else [candidate[@"state"] removeObjectForKey:key];
    return [self commit:candidate error:error];
}
- (BOOL)revokeConsentWithError:(NSError **)error {
    NSMutableDictionary *candidate = [self candidate];
    candidate[@"state"][@"consent"] = @NO;
    [candidate[@"state"] removeObjectForKey:@"registrationId"];
    [candidate[@"events"] removeAllObjects]; [candidate[@"dedup"] removeAllObjects];
    return [self commit:candidate error:error];
}
- (void)prune:(NSMutableDictionary *)candidate configuration:(AVPushConfiguration *)config now:(double)now {
    NSMutableArray *events = candidate[@"events"];
    NSMutableDictionary *dedup = candidate[@"dedup"];
    NSUInteger originalCount = events.count;
    NSIndexSet *expired = [events indexesOfObjectsPassingTest:^BOOL(NSDictionary *event, NSUInteger index, BOOL *stop) {
        return [event[@"receivedAt"] doubleValue] <= now - config.eventRetention * 1000;
    }];
    [events removeObjectsAtIndexes:expired];
    for (NSString *key in [dedup.allKeys copy]) if ([dedup[key] doubleValue] <= now) [dedup removeObjectForKey:key];
    while (events.count > config.maxPendingEvents) [events removeObjectAtIndex:0];
    while (events.count && [NSJSONSerialization dataWithJSONObject:events options:0 error:nil].length > 4 * 1024 * 1024)
        [events removeObjectAtIndex:0];
    NSArray *keys = [dedup keysSortedByValueUsingSelector:@selector(compare:)];
    if (keys.count > 5000) [dedup removeObjectsForKeys:[keys subarrayWithRange:NSMakeRange(0, keys.count - 5000)]];
    candidate[@"dropped"] = @([candidate[@"dropped"] unsignedIntegerValue] + originalCount - events.count);
}
- (AVPushEvent *)append:(NSDictionary *)event deduplicationKey:(NSString *)key configuration:(AVPushConfiguration *)configuration error:(NSError **)error {
    if (![NSJSONSerialization isValidJSONObject:event]) {
        if (error) *error = AVError(@"invalid_data", @"Push payload is not JSON compatible", nil, nil);
        return nil;
    }
    if ([NSJSONSerialization dataWithJSONObject:event options:0 error:error].length > 256 * 1024) {
        if (error) *error = AVError(@"event_too_large", @"Push event exceeds storage limit", nil, nil);
        return nil;
    }
    NSMutableDictionary *candidate = [self candidate];
    double now = NSDate.date.timeIntervalSince1970 * 1000;
    [self prune:candidate configuration:configuration now:now];
    if (key && candidate[@"dedup"][key]) return nil;
    if (key) candidate[@"dedup"][key] = @(now + 60000);
    [candidate[@"events"] addObject:event];
    [self prune:candidate configuration:configuration now:now];
    return [self commit:candidate error:error] ? [[AVPushEvent alloc] initWithDictionary:event replayed:NO] : nil;
}
- (NSArray<AVPushEvent *> *)pending:(AVPushConfiguration *)configuration error:(NSError **)error {
    NSMutableDictionary *candidate = [self candidate];
    [self prune:candidate configuration:configuration now:NSDate.date.timeIntervalSince1970 * 1000];
    if (![candidate isEqual:_root] && ![self commit:candidate error:error]) return nil;
    NSMutableArray *events = [NSMutableArray array];
    for (NSDictionary *event in _root[@"events"]) [events addObject:[[AVPushEvent alloc] initWithDictionary:event replayed:YES]];
    return events;
}
- (BOOL)acknowledge:(NSString *)eventID error:(NSError **)error {
    if (!eventID.length) {
        if (error) *error = AVError(@"invalid_argument", @"Event ID is required", nil, nil);
        return NO;
    }
    NSMutableDictionary *candidate = [self candidate]; NSMutableArray *events = candidate[@"events"];
    NSIndexSet *matches = [events indexesOfObjectsPassingTest:^BOOL(NSDictionary *event, NSUInteger index, BOOL *stop) {
        return [event[@"id"] isEqual:eventID];
    }];
    if (!matches.count) return NO;
    [events removeObjectsAtIndexes:matches]; return [self commit:candidate error:error];
}
- (BOOL)clearWithError:(NSError **)error {
    NSMutableDictionary *candidate = [self candidate];
    [candidate[@"events"] removeAllObjects]; [candidate[@"dedup"] removeAllObjects];
    return [self commit:candidate error:error];
}
@end
