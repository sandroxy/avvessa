#import "AVNotificationDelegateInstallation.h"
#import <objc/runtime.h>

static __weak UNUserNotificationCenter *AVManagedCenter;
static __weak AVNotificationDelegate *AVManagedDelegate;
static void (*AVSetDelegate)(id, SEL, id);

void AVInstallNotificationDelegate(UNUserNotificationCenter *center, AVNotificationDelegate *coordinator) {
    NSCAssert(NSThread.isMainThread, @"Notification delegate installation requires the main thread");
    AVManagedCenter = center; AVManagedDelegate = coordinator;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        Method method = class_getInstanceMethod(UNUserNotificationCenter.class, @selector(setDelegate:));
        AVSetDelegate = (void (*)(id, SEL, id))method_getImplementation(method);
        method_setImplementation(method, imp_implementationWithBlock(^(UNUserNotificationCenter *owner, id<UNUserNotificationCenterDelegate> delegate) {
            if (owner != AVManagedCenter || !AVManagedDelegate || delegate == AVManagedDelegate) {
                AVSetDelegate(owner, @selector(setDelegate:), delegate); return;
            }
            void (^assign)(void) = ^{
                AVNotificationDelegate *managed = AVManagedDelegate;
                if (owner == AVManagedCenter && managed && delegate != managed) {
                    [managed addDelegate:delegate]; AVSetDelegate(owner, @selector(setDelegate:), managed);
                } else AVSetDelegate(owner, @selector(setDelegate:), delegate);
            };
            if (NSThread.isMainThread) assign(); else dispatch_async(dispatch_get_main_queue(), assign);
        }));
    });
    [coordinator addDelegate:center.delegate]; center.delegate = coordinator;
}
