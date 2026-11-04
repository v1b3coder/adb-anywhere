/*
 * tls_connection.h - the TLS type used by transport.h.
 *
 * TLS is not built into this daemon. transport.cpp returns false from
 * DoTlsHandshake(), so this class is only needed to complete the unique_ptr
 * member in FdConnection.
 */
#pragma once

#include <cstdint>
#include <string>
#include <string_view>

namespace adb {
namespace tls {

class TlsConnection {
  public:
    enum class TlsError : int {
        Success = 0,
    };

    TlsConnection() = default;
    ~TlsConnection() = default;

    TlsError DoHandshake() { return TlsError::Success; }
    bool ReadFully(void*, size_t) { return false; }
    bool WriteFully(std::string_view) { return false; }
    void EnableClientPostHandshakeCheck(bool) {}
    void SetCertificateCallback(void*) {}
    void SetCertVerifyCallback(void*) {}
    void SetClientCAList(void*) {}
};

}  // namespace tls
}  // namespace adb
