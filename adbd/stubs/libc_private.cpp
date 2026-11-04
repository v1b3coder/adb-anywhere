/*
 * libc_private.cpp - private bionic property serials that the NDK omits.
 *
 * libbase uses these only from WaitForProperty(), which the daemon never calls.
 */
#include <sys/system_properties.h>

#include <stdint.h>

extern "C" uint32_t __system_property_area_serial(void) {
    return 0;
}

extern "C" uint32_t __system_property_serial(const prop_info* pi) {
    (void)pi;
    return 0;
}

extern "C" bool __system_property_wait(const prop_info* pi, uint32_t old_serial,
                                       uint32_t* new_serial, const struct timespec* timeout) {
    (void)pi;
    (void)old_serial;
    (void)new_serial;
    (void)timeout;
    return false;
}
