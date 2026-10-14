#if __has_include(<Avvessa/Avvessa.h>)
#import <Avvessa/Avvessa.h>
#else
#import "Avvessa.h"
#endif

/** Framework adapters opt in to preserving delegate assignments made by other modules. */
void AVInstallNotificationDelegate(UNUserNotificationCenter *center, AVNotificationDelegate *coordinator);
