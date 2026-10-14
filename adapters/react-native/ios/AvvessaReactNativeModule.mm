#import <React/RCTEventEmitter.h>
#import <React/RCTInvalidating.h>
#import "AvvessaBridge.h"
#import "AVReactNativeLifecycle.h"
#ifdef RCT_NEW_ARCH_ENABLED
#import <AvvessaSpec/AvvessaSpec.h>
#endif

@interface AvvessaReactNativeModule : RCTEventEmitter <RCTInvalidating
#ifdef RCT_NEW_ARCH_ENABLED
    , NativeAvvessaSpec
#endif
>
@end

@implementation AvvessaReactNativeModule {
    AVBridgeSession *_session;
    BOOL _disposed;
}
RCT_EXPORT_MODULE(Avvessa)
+ (BOOL)requiresMainQueueSetup { return YES; }
- (dispatch_queue_t)methodQueue { return dispatch_get_main_queue(); }
- (NSArray<NSString *> *)supportedEvents { return @[@"AvvessaObservation"]; }
RCT_EXPORT_METHOD(invoke:(NSString *)method argumentsJSON:(NSString *)arguments
                  resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject) {
    if (_disposed) { reject(@"bridge_closed", @"The React Native bridge has been invalidated", nil); return; }
    if ([method isEqual:@"initialize"] && !AVReactNativeLifecycle.shared.launched) {
        reject(@"host_unavailable", @"Forward application launch through AVReactNativeLifecycle before initializing Avvessa", nil);
        return;
    }
    if (!_session) _session = [[AVBridgeSession alloc]
        initWithLaunchOptionsProvider:^NSDictionary *{ return AVReactNativeLifecycle.shared.launchOptions; }
        activation:^{ [AVReactNativeLifecycle.shared activate]; }];
    NSDictionary *args = [NSJSONSerialization JSONObjectWithData:[arguments dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
    NSString *identifier = [method isEqual:@"subscribe"] && [args isKindOfClass:NSDictionary.class] &&
        [args[@"subscriptionId"] isKindOfClass:NSString.class] ? args[@"subscriptionId"] : nil;
    __block BOOL replied = NO;
    __weak AvvessaReactNativeModule *weakSelf = self;
    [_session invoke:method argumentsJSON:arguments completion:^(NSString *json) {
        AvvessaReactNativeModule *owner = weakSelf;
        if (!owner || owner->_disposed) return;
        if (!replied) { replied = YES; resolve(json); }
        else if (identifier) [owner sendEventWithName:@"AvvessaObservation" body:@{@"subscriptionId":identifier, @"response":json}];
    }];
}
- (void)invalidate {
    void (^close)(void) = ^{
        self->_disposed = YES;
        [self->_session close];
        self->_session = nil;
    };
    if (NSThread.isMainThread) close(); else dispatch_sync(dispatch_get_main_queue(), close);
    [super invalidate];
}
#ifdef RCT_NEW_ARCH_ENABLED
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:(const facebook::react::ObjCTurboModule::InitParams &)params {
    return std::make_shared<facebook::react::NativeAvvessaSpecJSI>(params);
}
#endif
@end
