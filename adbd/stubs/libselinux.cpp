/*
 * libselinux.cpp - no-op SELinux context switch for the standalone daemon.
 */
#include <selinux/android.h>

extern "C" int selinux_android_setcon(const char* con) {
    (void)con;
    return 0;
}

extern "C" int selinux_android_restorecon(const char* path, unsigned int flags) {
    (void)path;
    (void)flags;
    return 0;
}
