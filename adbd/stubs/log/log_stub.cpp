/*
 * log_stub.cpp - the small liblog surface the standalone daemon needs.
 *
 * The daemon and libbase call liblog through <android/log.h>. On a retail device
 * there is no logd socket this process may write to, so every entry point falls
 * back to stderr and keeps the same semantics (loggability, logger callback,
 * aborter callback).
 */
#include <android/log.h>
#include <log/log_properties.h>
#include <private/android_logger.h>

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static __android_logger_function g_logger;
static __android_aborter_function g_aborter;
static int32_t g_min_priority = ANDROID_LOG_DEFAULT;
static char g_default_tag[128] = "adbd";

static char prio_char(int32_t prio) {
    switch (prio) {
        case ANDROID_LOG_VERBOSE:
            return 'V';
        case ANDROID_LOG_DEBUG:
            return 'D';
        case ANDROID_LOG_INFO:
            return 'I';
        case ANDROID_LOG_WARN:
            return 'W';
        case ANDROID_LOG_ERROR:
            return 'E';
        case ANDROID_LOG_FATAL:
            return 'F';
        default:
            return '?';
    }
}

static void log_to_stderr(const struct __android_log_message* m) {
    const char* tag = (m->tag != nullptr && m->tag[0] != '\0') ? m->tag : g_default_tag;
    fprintf(stderr, "%c/%-16s: %s\n", prio_char(m->priority), tag,
            m->message != nullptr ? m->message : "");
    fflush(stderr);
}

extern "C" {

void __android_log_set_default_tag(const char* tag) {
    if (tag == nullptr) return;
    snprintf(g_default_tag, sizeof g_default_tag, "%s", tag);
}

void __android_log_set_logger(__android_logger_function logger) {
    g_logger = logger;
}

void __android_log_logd_logger(const struct __android_log_message* log_message) {
    log_to_stderr(log_message);
}

void __android_log_set_aborter(__android_aborter_function aborter) {
    g_aborter = aborter;
}

void __android_log_call_aborter(const char* abort_message) {
    if (g_aborter != nullptr) g_aborter(abort_message);
}

void __android_log_write_log_message(struct __android_log_message* log_message) {
    if (g_logger != nullptr) {
        g_logger(log_message);
    } else {
        log_to_stderr(log_message);
    }
}

int32_t __android_log_set_minimum_priority(int32_t priority) {
    int32_t old = g_min_priority;
    g_min_priority = priority;
    return old;
}

int32_t __android_log_get_minimum_priority(void) {
    return g_min_priority;
}

int __android_log_is_loggable(int prio, const char* tag, int default_prio) {
    (void)tag;
    int32_t minimum = (g_min_priority != ANDROID_LOG_DEFAULT) ? g_min_priority : default_prio;
    return prio >= minimum;
}

int __android_log_is_debuggable(void) {
    return 0;
}

int __android_log_buf_print(int bufID, int prio, const char* tag, const char* fmt, ...) {
    (void)bufID;
    char message[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(message, sizeof message, fmt, ap);
    va_end(ap);

    struct __android_log_message m = {
            sizeof(struct __android_log_message), bufID, prio, tag, nullptr, 0, message};
    __android_log_write_log_message(&m);
    return 1;
}

int __android_log_security_bswrite(int32_t tag, const char* msg) {
    char tag_str[32];
    snprintf(tag_str, sizeof tag_str, "sec:%u", tag);
    struct __android_log_message m = {
            sizeof(struct __android_log_message), LOG_ID_SECURITY, ANDROID_LOG_INFO, tag_str,
            nullptr, 0, msg};
    __android_log_write_log_message(&m);
    return 1;
}

void __android_log_assert(const char* cond, const char* tag, const char* fmt, ...) {
    char message[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(message, sizeof message, fmt, ap);
    va_end(ap);

    struct __android_log_message m = {
            sizeof(struct __android_log_message), LOG_ID_MAIN, ANDROID_LOG_FATAL, tag,
            nullptr, 0, message};
    __android_log_write_log_message(&m);
    fprintf(stderr, "adbd: assertion failed: %s (%s)\n", cond != nullptr ? cond : "", message);
    abort();
}

}  // extern "C"
