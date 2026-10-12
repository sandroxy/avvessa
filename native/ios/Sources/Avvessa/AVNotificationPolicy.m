#import "AVInternal.h"
#import <CoreFoundation/CoreFoundation.h>
#import <math.h>

static BOOL Fields(NSDictionary *value, NSArray *allowed, NSError **error) {
    if (![value isKindOfClass:NSDictionary.class]) {
        if (error) *error = AVError(@"invalid_configuration", @"Policy values must be objects", nil, nil); return NO;
    }
    for (id key in value) if (![allowed containsObject:key]) {
        if (error) *error = AVError(@"invalid_configuration", @"Unknown policy field", nil, nil); return NO;
    }
    return YES;
}
static NSString *Scalar(id value) {
    if ([value isKindOfClass:NSString.class]) return [value length] <= 1024 ? value : nil;
    if (![value isKindOfClass:NSNumber.class]) return nil;
    if (CFGetTypeID((__bridge CFTypeRef)value) == CFBooleanGetTypeID()) return [value boolValue] ? @"true" : @"false";
    double number = [value doubleValue];
    if (!isfinite(number) || fabs(number) > 9007199254740991.0) return nil;
    NSDecimalNumber *decimal = [NSDecimalNumber decimalNumberWithString:[value stringValue] locale:@{@"NSDecimalSeparator": @"."}];
    return decimal.stringValue;
}
static BOOL Text(NSDictionary *value, NSString *key) {
    id text = value[key];
    return [text isKindOfClass:NSString.class] && [text length] <= 128
        && [text stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet].length > 0;
}
static BOOL Rule(NSDictionary *rule, BOOL active, NSError **error) {
    if (!Fields(rule, active ? @[@"match",@"platforms",@"reason",@"idField",@"categoryField"] : @[@"match",@"platforms",@"reason"], error)) return NO;
    NSDictionary *match = rule[@"match"];
    BOOL valid = [match isKindOfClass:NSDictionary.class] && match.count > 0 && match.count <= 32;
    if (valid) for (NSString *key in match) if (!Text(@{@"field":key},@"field") || !Scalar(match[key])) { valid = NO; break; }
    if (active && !Text(rule,@"idField")) valid = NO;
    for (NSString *key in @[@"categoryField",@"reason"]) if (rule[key] && !Text(rule,key)) valid = NO;
    NSArray *platforms = rule[@"platforms"];
    if (platforms) {
        if (![platforms isKindOfClass:NSArray.class] || !platforms.count) valid = NO;
        else {
            NSMutableSet *seen = [NSMutableSet set];
            for (id platform in platforms) {
                if (![@[@"android",@"ios"] containsObject:platform] || [seen containsObject:platform]) valid = NO;
                [seen addObject:platform];
            }
        }
    }
    if (!valid && error) *error = AVError(@"invalid_configuration", @"Invalid notification rule, match, field or platform", nil, nil);
    return valid;
}
static NSDictionary *Snapshot(NSDictionary *value) {
    NSData *json = [NSJSONSerialization dataWithJSONObject:value options:0 error:nil];
    return [NSJSONSerialization JSONObjectWithData:json options:0 error:nil];
}
static BOOL Matches(NSDictionary *data, NSDictionary *rule, NSString *platform) {
    if (rule[@"platforms"] && ![rule[@"platforms"] containsObject:platform]) return NO;
    NSDictionary *match = rule[@"match"];
    for (NSString *key in match) if (![Scalar(data[key]) isEqual:Scalar(match[key])]) return NO;
    return YES;
}

@implementation AVNotificationPolicy
- (instancetype)initWithDictionary:(NSDictionary *)dictionary error:(NSError **)error {
    if (!Fields(dictionary,@[@"suppressForeground",@"activeContext"],error)) return nil;
    id rules = dictionary[@"suppressForeground"];
    if (rules && (![rules isKindOfClass:NSArray.class] || [rules count] > 32)) {
        if (error) *error = AVError(@"invalid_configuration", @"suppressForeground must contain at most 32 rules", nil, nil); return nil;
    }
    for (NSDictionary *rule in rules) if (!Rule(rule,NO,error)) return nil;
    if (dictionary[@"activeContext"] && !Rule(dictionary[@"activeContext"],YES,error)) return nil;
    if ((self = [super init])) _dictionary = Snapshot(dictionary);
    return self;
}
- (NSString *)suppressionReasonForData:(NSDictionary *)data context:(NSDictionary *)context platform:(NSString *)platform {
    if (!data) return nil;
    for (NSDictionary *rule in _dictionary[@"suppressForeground"])
        if (Matches(data,rule,platform)) return rule[@"reason"] ?: @"rule";
    NSDictionary *active = _dictionary[@"activeContext"];
    if (!active || !context || !Matches(data,active,platform)) return nil;
    if (![Scalar(data[active[@"idField"]]) isEqual:Scalar(context[@"id"])]) return nil;
    if (active[@"categoryField"] && ![Scalar(data[active[@"categoryField"]]) isEqual:Scalar(context[@"category"])]) return nil;
    return active[@"reason"] ?: @"activeContext";
}
@end

NSDictionary *AVNotificationContext(NSDictionary *value, NSError **error) {
    if (!Fields(value,@[@"id",@"category"],error)) return nil;
    if (!Scalar(value[@"id"]).length || (value[@"category"] && !Scalar(value[@"category"]))) {
        if (error) *error = AVError(@"invalid_argument", @"Context id must be a nonempty scalar and category must be a scalar", nil, nil); return nil;
    }
    return Snapshot(value);
}

@implementation AVNotificationLifecycle
- (instancetype)initWithDictionary:(NSDictionary *)dictionary error:(NSError **)error {
    NSArray *keys = @[@"clearBadgeOnStart",@"clearBadgeOnForeground",@"clearBadgeOnOpen",@"clearDeliveredOnStart",@"clearDeliveredOnForeground"];
    if (!Fields(dictionary,keys,error)) return nil;
    NSMutableDictionary *normalized = [NSMutableDictionary dictionary];
    for (NSString *key in keys) {
        id value = dictionary[key] ?: @NO;
        if (![value isKindOfClass:NSNumber.class] || CFGetTypeID((__bridge CFTypeRef)value) != CFBooleanGetTypeID()) {
            if (error) *error = AVError(@"invalid_configuration", @"Lifecycle options must be booleans", nil, nil); return nil;
        }
        normalized[key] = value;
    }
    if ((self = [super init])) _dictionary = [normalized copy];
    return self;
}
@end
