/*
 * adbd_auth.cpp - key-file implementation of the libadbd_auth interface.
 *
 * AOSP's libadbd_auth talks to system_server, which owns the key store at
 * /data/misc/adb/adb_keys. That file is 0640 system:system and the standalone
 * daemon runs as shell, so this implementation replaces the framework with a key
 * file the app can write: ADBD_KEYS, default /data/local/tmp/adb-anywhere/adb_keys.
 * The key file is the only source of truth; an unknown key is never authorized
 * and no prompt is shown.
 */
#include <adbd_auth.h>

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <mutex>
#include <string>
#include <vector>

struct AdbdAuthContext {
    AdbdAuthCallbacksV1 callbacks;
    uint64_t next_id;
};

namespace {

constexpr const char* kDefaultKeyFile = "/data/local/tmp/adb-anywhere/adb_keys";

// The most bytes of an unlisted key the log shows.
constexpr size_t kMaxLoggedKey = 64;

std::mutex g_lock;

const char* key_file() {
    const char* path = getenv("ADBD_KEYS");
    return (path != nullptr && path[0] != '\0') ? path : kDefaultKeyFile;
}

std::vector<std::string> read_keys() {
    std::vector<std::string> keys;
    FILE* fp = fopen(key_file(), "re");
    if (fp == nullptr) return keys;

    char* line = nullptr;
    size_t cap = 0;
    while (getline(&line, &cap, fp) != -1) {
        std::string key(line);
        while (!key.empty() && (key.back() == '\n' || key.back() == '\r')) key.pop_back();
        if (key.empty() || key[0] == '#') continue;
        keys.push_back(std::move(key));
    }
    free(line);
    fclose(fp);
    return keys;
}

}  // namespace

AdbdAuthContext* adbd_auth_new(AdbdAuthCallbacks* callbacks) {
    auto* ctx = new AdbdAuthContext();
    if (callbacks != nullptr && callbacks->version >= 1) {
        ctx->callbacks = *reinterpret_cast<AdbdAuthCallbacksV1*>(callbacks);
    }
    ctx->next_id = 1;
    return ctx;
}

void adbd_auth_delete(AdbdAuthContext* ctx) {
    delete ctx;
}

void adbd_auth_run(AdbdAuthContext* ctx) {
    (void)ctx;
    while (true) pause();
}

void adbd_auth_get_public_keys(AdbdAuthContext* ctx,
                               bool (*callback)(void* opaque, const char* public_key, size_t len),
                               void* opaque) {
    (void)ctx;
    for (const std::string& key : read_keys()) {
        if (!callback(opaque, key.c_str(), key.size())) break;
    }
}

uint64_t adbd_auth_notify_auth(AdbdAuthContext* ctx, const char* public_key, size_t len) {
    (void)public_key;
    (void)len;
    std::lock_guard<std::mutex> lock(g_lock);
    return ctx->next_id++;
}

void adbd_auth_notify_disconnect(AdbdAuthContext* ctx, uint64_t id) {
    (void)ctx;
    (void)id;
}

void adbd_auth_prompt_user(AdbdAuthContext* ctx, const char* public_key, size_t len,
                           void* opaque) {
    (void)ctx;
    (void)opaque;
    // Unknown keys are never auto-accepted: the app owns the key file. The key
    // comes from an unauthenticated peer, so log a bounded, printable prefix: a
    // peer must not grow the log by 1 MiB per packet or forge log lines.
    char shown[kMaxLoggedKey + 1];
    size_t n = 0;
    for (; n < len && n < kMaxLoggedKey; ++n) {
        unsigned char c = static_cast<unsigned char>(public_key[n]);
        shown[n] = (c >= 0x20 && c < 0x7f) ? static_cast<char>(c) : '?';
    }
    shown[n] = '\0';
    fprintf(stderr, "adbd: rejecting unlisted key %s%s\n", shown, len > kMaxLoggedKey ? "..." : "");
}

// daemon/auth.cpp CHECKs that a disconnected transport carries an auth id, and it
// only asks for one through this variant. Without it, any unlisted key that
// disconnects aborts the daemon.
uint64_t adbd_auth_prompt_user_with_id(AdbdAuthContext* ctx, const char* public_key,
                                       size_t len, void* opaque) {
    adbd_auth_prompt_user(ctx, public_key, len, opaque);
    std::lock_guard<std::mutex> lock(g_lock);
    return ctx->next_id++;
}

uint64_t adbd_auth_tls_device_connected(AdbdAuthContext* ctx, AdbTransportType type,
                                        const char* public_key, size_t len) {
    (void)ctx;
    (void)type;
    (void)public_key;
    (void)len;
    return 0;
}

void adbd_auth_tls_device_disconnected(AdbdAuthContext* ctx, AdbTransportType type, uint64_t id) {
    (void)ctx;
    (void)type;
    (void)id;
}

uint32_t adbd_auth_get_max_version(void) {
    return 1;
}

bool adbd_auth_supports_feature(AdbdAuthFeature feature) {
    (void)feature;
    return false;
}

void adbd_auth_send_tls_server_port(AdbdAuthContext* ctx, uint16_t port) {
    (void)ctx;
    (void)port;
}
