/*
 * sys/capability.h - host-build stand-in for libcap's header.
 *
 * adb.cpp includes it in the daemon build but uses nothing from it. Android has
 * the header in bionic; a Linux host without libcap-dev does not. Only the
 * ADBD_HOST=1 compile check and the host smoke test use this directory.
 */
#pragma once
