/*
 * system_properties.h - private bionic property serials used by libbase.
 *
 * The NDK exposes the public property API only. libbase also uses the serial
 * helpers for WaitForProperty(); the standalone daemon never waits on a property,
 * so the stubs return a constant serial.
 */
#pragma once

#include_next <sys/system_properties.h>

#ifdef __cplusplus
extern "C" {
#endif

uint32_t __system_property_area_serial(void);
uint32_t __system_property_serial(const prop_info* pi);
bool __system_property_wait(const prop_info* pi, uint32_t old_serial, uint32_t* new_serial,
                           const struct timespec* relative_timeout);

#ifdef __cplusplus
}
#endif
