#import "AvvessaUniModule.h"
#import <AvvessaBridge/AvvessaBridge.h>

@implementation AvvessaUniModule {
    AVBridgeSession *_session;
}
UNI_EXPORT_METHOD(@selector(invoke:argumentsJSON:callback:))
- (void)invoke:(NSString *)method argumentsJSON:(NSString *)arguments callback:(UniModuleKeepAliveCallback)callback {
    if (!callback) return;
    if (!_session) _session = [AVBridgeSession new];
    [_session invoke:method argumentsJSON:arguments completion:^(NSString *json) {
        NSDictionary *response = [NSJSONSerialization JSONObjectWithData:[json dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
        BOOL keep = [method isEqual:@"subscribe"] && [response[@"ok"] boolValue] && ![response[@"data"][@"kind"] isEqual:@"closed"];
        callback(json,keep);
    }];
}
@end
