#import <Avvessa/Avvessa.h>

/** UNI owns a shared UN delegate slot. Preserve assignments made by other installed modules. */
void AVUniInstallNotificationDelegate(UNUserNotificationCenter *center, AVNotificationDelegate *coordinator);
