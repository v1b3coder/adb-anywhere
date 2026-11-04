/*
 * android.h - SELinux helpers used by the shell service.
 *
 * The standalone daemon is started by the app as shell, so it does not need to
 * change its own SELinux context.
 */
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

int selinux_android_setcon(const char* con);
int selinux_android_restorecon(const char* path, unsigned int flags);

#ifdef __cplusplus
}
#endif
