#!/usr/bin/env python3
"""tls_smoke.py - transport and auth smoke test for the patched adbd.

Runs the daemon on an Android device or emulator (the host build does not
link), drives it from the host through `adb forward`, and checks the contract
the app depends on:

  * --tls-listen: CNXN -> STLS, the TLS handshake needs a --tls-clients key, the
    server key is the --tls-key key (SPKI pin), ADB key auth runs inside TLS and
    accepts a --keys key that is not a TLS client, A_OPEN before TLS is ignored;
  * --listen: plain auth, STLS kicks, failed-signature and public-key limits,
    a bounded log line for an unlisted key;
  * reverse: device listeners bind 127.0.0.1 only, other hosts and socket types
    are refused;
  * strict listeners: invalid specs, no listener, a busy port and a TLS
    listener without a key make the daemon exit non-zero;
  * the log and pid files are private (0600).

Usage: tls_smoke.py [--serial emulator-5554] [--adbd build/out/x86_64/adbd]
Needs `adb` and `openssl` on the host; only the Python standard library.
"""
import argparse
import base64
import hashlib
import os
import socket
import ssl
import struct
import subprocess
import sys
import tempfile
import time

A_CNXN = 0x4E584E43
A_AUTH = 0x48545541
A_STLS = 0x534C5453
A_OPEN = 0x4E45504F
A_OKAY = 0x59414B4F
A_CLSE = 0x45534C43
A_WRTE = 0x45545257
A_VERSION = 0x01000001
A_STLS_VERSION = 0x01000000
AUTH_TOKEN, AUTH_SIGNATURE, AUTH_RSAPUBLICKEY = 1, 2, 3
SHA1_DIGEST_INFO = bytes.fromhex("3021300906052b0e03021a05000414")

REMOTE = "/data/local/tmp/adbd-smoke"
PLAIN_PORT, TLS_PORT = 27037, 27038
HOST_PLAIN, HOST_TLS = 37037, 37038

results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(("ok   " if ok else "FAIL ") + name + (f" ({detail})" if detail else ""))


class Key:
    """An RSA-2048 key generated with the openssl CLI."""

    def __init__(self, workdir, name):
        self.pem = os.path.join(workdir, name + ".pem")
        self.cert = os.path.join(workdir, name + ".crt")
        run(["openssl", "genrsa", "-traditional", "-out", self.pem, "2048"])
        run(["openssl", "req", "-new", "-x509", "-key", self.pem, "-out", self.cert,
             "-days", "1", "-subj", "/CN=" + name])
        text = run(["openssl", "rsa", "-in", self.pem, "-noout", "-text"]).decode()
        self.n = self._field(text, "modulus:")
        self.d = self._field(text, "privateExponent:")
        self.spki = run(["openssl", "pkey", "-in", self.pem, "-pubout", "-outform", "DER"])

    @staticmethod
    def _field(text, label):
        lines = text.splitlines()
        start = next(i for i, l in enumerate(lines) if l.strip() == label)
        hexdigits = ""
        for line in lines[start + 1:]:
            if not line.startswith("    "):
                break
            hexdigits += line.strip().replace(":", "")
        return int(hexdigits, 16)

    def android_pubkey(self, comment):
        n = self.n
        n0inv = (-pow(n, -1, 1 << 32)) % (1 << 32)
        rr = pow(1 << 2048, 2, n)
        blob = struct.pack("<II", 64, n0inv) + n.to_bytes(256, "little") + \
            rr.to_bytes(256, "little") + struct.pack("<I", 65537)
        return base64.b64encode(blob).decode() + " " + comment

    def sign(self, token):
        em = SHA1_DIGEST_INFO + token
        em = b"\x00\x01" + b"\xff" * (256 - 3 - len(em)) + b"\x00" + em
        return pow(int.from_bytes(em, "big"), self.d, self.n).to_bytes(256, "big")


def run(cmd, check_rc=True, timeout=60):
    proc = subprocess.run(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=timeout)
    if check_rc and proc.returncode != 0:
        raise RuntimeError(f"{cmd} failed: {proc.stdout.decode(errors='replace')}")
    return proc.stdout


def packet(cmd, arg0, arg1, payload=b""):
    return struct.pack("<6I", cmd, arg0, arg1, len(payload), sum(payload) & 0xFFFFFFFF,
                       cmd ^ 0xFFFFFFFF) + payload


def recv_exact(sock, n):
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise EOFError("closed")
        data += chunk
    return data


def read_packet(sock, timeout=3.0):
    sock.settimeout(timeout)
    header = recv_exact(sock, 24)
    cmd, arg0, arg1, length, _, magic = struct.unpack("<6I", header)
    if magic != cmd ^ 0xFFFFFFFF:
        raise ValueError("bad magic")
    return cmd, arg0, arg1, recv_exact(sock, length)


def closed_or_silent(sock, timeout=1.5):
    """True when the peer closes or sends nothing; False when it sends a packet."""
    try:
        read_packet(sock, timeout)
        return False
    except (EOFError, ConnectionError, socket.timeout, ssl.SSLError, OSError):
        return True


def is_closed(sock, timeout=3.0):
    try:
        while True:
            read_packet(sock, timeout)
    except (EOFError, ConnectionError, ssl.SSLError):
        return True
    except (socket.timeout, OSError):
        return False


def connect(port):
    return socket.create_connection(("127.0.0.1", port), timeout=5)


def cnxn(sock):
    sock.sendall(packet(A_CNXN, A_VERSION, 1 << 20, b"host::\0"))
    return read_packet(sock)


def tls_upgrade(sock, key):
    """CNXN, STLS both ways, then the TLS 1.3 handshake with |key|'s certificate."""
    cmd, arg0, _, _ = cnxn(sock)
    if cmd != A_STLS:
        raise RuntimeError(f"expected STLS, got {cmd:#x}")
    sock.sendall(packet(A_STLS, A_STLS_VERSION, 0))
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    ctx.minimum_version = ssl.TLSVersion.TLSv1_3
    ctx.load_cert_chain(key.cert, key.pem)
    tls = ctx.wrap_socket(sock, server_side=False, do_handshake_on_connect=False)
    tls.settimeout(5)
    tls.do_handshake()
    return tls, arg0


def peer_spki(tls, workdir):
    der = tls.getpeercert(binary_form=True)
    path = os.path.join(workdir, "peer.der")
    with open(path, "wb") as f:
        f.write(der)
    pem = run(["openssl", "x509", "-inform", "DER", "-in", path, "-pubkey", "-noout"]).decode()
    body = "".join(l for l in pem.splitlines() if not l.startswith("-----"))
    return base64.b64decode(body)


def open_service(sock, service, local_id=1):
    sock.sendall(packet(A_OPEN, local_id, 0, service.encode() + b"\0"))
    reply = read_packet(sock)
    output = b""
    if reply[0] == A_OKAY:
        remote = reply[1]
        try:
            while True:
                cmd, arg0, _, data = read_packet(sock, 3)
                if cmd == A_WRTE:
                    output += data
                    sock.sendall(packet(A_OKAY, local_id, remote))
                elif cmd == A_CLSE:
                    break
        except (socket.timeout, EOFError):
            pass
    return reply[0], output


class Device:
    def __init__(self, serial):
        self.serial = serial

    def adb(self, *args, check_rc=True, timeout=60):
        return run(["adb", "-s", self.serial, *args], check_rc=check_rc, timeout=timeout)

    def sh(self, command, timeout=60):
        return self.adb("shell", "-n", "-T", command, check_rc=False, timeout=timeout).decode(errors="replace")


def daemon_args(extra=""):
    return (f"--keys {REMOTE}/keys --tls-clients {REMOTE}/tls_clients "
            f"--tls-key {REMOTE}/server.pem --log {REMOTE}/adbd.log "
            f"--pid-file {REMOTE}/adbd.pid {extra}")


def exit_code(dev, args):
    out = dev.sh(f"cd {REMOTE} && timeout 3 ./adbd {args} </dev/null >/dev/null 2>&1; echo rc=$?")
    return out.strip().split("rc=")[-1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    here = os.path.dirname(os.path.abspath(__file__))
    parser.add_argument("--adbd", default=os.path.join(here, "..", "build", "out", "x86_64", "adbd"))
    opts = parser.parse_args()
    dev = Device(opts.serial)
    work = tempfile.mkdtemp(prefix="adbd-smoke-")

    server = Key(work, "server")
    client = Key(work, "app")         # TLS client and inner key
    developer = Key(work, "laptop")   # inner key only, not a TLS client
    stranger = Key(work, "stranger")  # in no file

    with open(os.path.join(work, "keys"), "w") as f:
        f.write(client.android_pubkey("app") + "\n" + developer.android_pubkey("laptop") + "\n")
    with open(os.path.join(work, "tls_clients"), "w") as f:
        f.write(client.android_pubkey("app") + "\n")

    dev.sh(f"pkill -f 'adbd-smoke/adbd' ; rm -rf {REMOTE}; mkdir -p {REMOTE}")
    dev.adb("push", opts.adbd, f"{REMOTE}/adbd")
    for name in ("keys", "tls_clients"):
        dev.adb("push", os.path.join(work, name), f"{REMOTE}/{name}")
    dev.adb("push", server.pem, f"{REMOTE}/server.pem")
    dev.sh(f"chmod 755 {REMOTE}/adbd; chmod 600 {REMOTE}/server.pem")

    print("== strict listeners ==")
    for spec in ("tcp:5555", "tcp:0.0.0.0:27100", "tcp:[::]:27100", "tcp:localhost:27100",
                 "tcp:127.0.0.1", "tcp:0:27100", "tcp:[::ffff:0.0.0.0]:27100", "vsock:5555"):
        rc = exit_code(dev, daemon_args(f"--listen {spec}"))
        check(f"--listen {spec} exits non-zero", rc not in ("0", "124"), f"rc={rc}")
    rc = exit_code(dev, daemon_args(""))
    check("no listener exits non-zero", rc not in ("0", "124"), f"rc={rc}")
    rc = exit_code(dev, f"--keys {REMOTE}/keys --tls-listen tcp:127.0.0.1:27101")
    check("--tls-listen without --tls-key exits non-zero", rc not in ("0", "124"), f"rc={rc}")
    rc = exit_code(dev, daemon_args("--listen tcp:127.0.0.1:27102"))
    check("valid listener keeps running", rc == "124", f"rc={rc}")

    dev.sh(f"setsid {REMOTE}/adbd {daemon_args(f'--listen tcp:127.0.0.1:{PLAIN_PORT} --tls-listen tcp:127.0.0.1:{TLS_PORT}')} "
           f"</dev/null >/dev/null 2>{REMOTE}/stderr &")
    time.sleep(1.5)
    pid = dev.sh(f"cat {REMOTE}/adbd.pid").strip()
    check("daemon started", pid.isdigit(), dev.sh(f"cat {REMOTE}/stderr {REMOTE}/adbd.log | tail -5"))
    rc = exit_code(dev, daemon_args(f"--listen tcp:127.0.0.1:{PLAIN_PORT}"))
    check("busy port exits non-zero", rc not in ("0", "124"), f"rc={rc}")
    modes = dev.sh(f"ls -l {REMOTE}/adbd.log {REMOTE}/adbd.pid")
    check("log and pid files are 0600",
          all(l.startswith("-rw-------") for l in modes.strip().splitlines()), modes.strip())

    dev.adb("forward", f"tcp:{HOST_PLAIN}", f"tcp:{PLAIN_PORT}")
    dev.adb("forward", f"tcp:{HOST_TLS}", f"tcp:{TLS_PORT}")

    print("== TLS tunnel listener ==")
    s = connect(HOST_TLS)
    cmd, arg0, _, _ = cnxn(s)
    check("CNXN on the TLS listener gets STLS", cmd == A_STLS and arg0 == A_STLS_VERSION,
          f"{cmd:#x}/{arg0:#x}")
    s.sendall(packet(A_AUTH, AUTH_SIGNATURE, 0, developer.sign(b"\0" * 20)))
    s.sendall(packet(A_OPEN, 1, 0, b"shell:id\0"))
    check("AUTH and OPEN before TLS are not honoured", closed_or_silent(s))
    s.close()

    s = connect(HOST_TLS)
    try:
        tls, _ = tls_upgrade(s, stranger)
        failed = closed_or_silent(tls, 3)
    except (ssl.SSLError, OSError, EOFError):
        failed = True
    check("TLS with a key outside --tls-clients fails", failed)

    s = connect(HOST_TLS)
    tls, _ = tls_upgrade(s, client)
    check("TLS server key is the --tls-key key (SPKI pin)",
          hashlib.sha256(peer_spki(tls, work)).hexdigest() == hashlib.sha256(server.spki).hexdigest())
    cmd, arg0, _, token = read_packet(tls)
    check("AUTH TOKEN arrives inside TLS", cmd == A_AUTH and arg0 == AUTH_TOKEN and len(token) == 20)
    tls.sendall(packet(A_AUTH, AUTH_SIGNATURE, 0, stranger.sign(token)))
    cmd, arg0, _, token = read_packet(tls)
    check("unlisted inner key gets a new token", cmd == A_AUTH and arg0 == AUTH_TOKEN)
    tls.sendall(packet(A_AUTH, AUTH_SIGNATURE, 0, developer.sign(token)))
    cmd, _, _, banner = read_packet(tls)
    check("developer key (not a TLS client) authenticates inside TLS", cmd == A_CNXN,
          banner[:40].decode(errors="replace"))
    reply, out = open_service(tls, "shell:echo tunnel-ok")
    check("shell works through the tunnel", reply == A_OKAY and b"tunnel-ok" in out, out[:60])
    tls.sendall(packet(A_STLS, A_STLS_VERSION, 0))
    check("second STLS kicks", is_closed(tls))

    print("== plain listener ==")
    s = connect(HOST_PLAIN)
    cmd, arg0, _, _ = cnxn(s)
    check("CNXN on the plain listener gets AUTH TOKEN", cmd == A_AUTH and arg0 == AUTH_TOKEN)
    s.sendall(packet(A_STLS, A_STLS_VERSION, 0))
    check("STLS on the plain listener kicks", is_closed(s))

    s = connect(HOST_PLAIN)
    cnxn(s)
    kicked_at = None
    for attempt in range(1, 20):
        s.sendall(packet(A_AUTH, AUTH_SIGNATURE, 0, b"\1" * 256))
        try:
            cmd, arg0, _, _ = read_packet(s)
        except (EOFError, ConnectionError):
            kicked_at = attempt
            break
    check("16 failed signatures kick", kicked_at == 16, f"kicked after {kicked_at}")

    s = connect(HOST_PLAIN)
    cnxn(s)
    garbage = b"A" * 4000 + b"\nadbd: forged line\n" + b"B" * 4000 + b"\0"
    s.sendall(packet(A_AUTH, AUTH_RSAPUBLICKEY, 0, garbage))
    time.sleep(0.5)
    s.sendall(packet(A_AUTH, AUTH_RSAPUBLICKEY, 0, b"second\0"))
    check("second RSAPUBLICKEY kicks", is_closed(s))
    log = dev.sh(f"grep 'rejecting unlisted key' {REMOTE}/adbd.log {REMOTE}/stderr")
    lines = [l for l in log.splitlines() if "rejecting unlisted key" in l]
    check("unlisted key log line is bounded",
          bool(lines) and all(len(l) < 200 for l in lines) and "forged" not in log,
          f"{len(lines)} lines, max {max((len(l) for l in lines), default=0)} chars")

    s = connect(HOST_PLAIN)
    _, _, _, token = cnxn(s)
    s.sendall(packet(A_AUTH, AUTH_SIGNATURE, 0, developer.sign(token)))
    cmd, _, _, _ = read_packet(s)
    check("plain auth with a --keys key", cmd == A_CNXN)
    s.close()
    keep = []
    for spec, allowed in (("tcp:0.0.0.0:27201", False), ("tcp:[::0]:27202", False),
                          ("tcp:0:27203", False), ("tcp:10.0.2.15:27204", False),
                          ("localabstract:smoke", False), ("vsock:27205", False),
                          ("tcp:27206", True), ("tcp:localhost:27207", True),
                          ("tcp:127.0.0.1:27208", True)):
        # One authenticated transport per request: replies cannot interleave, and
        # the transport stays open so its reverse listener stays bound.
        r = connect(HOST_PLAIN)
        _, _, _, token = cnxn(r)
        r.sendall(packet(A_AUTH, AUTH_SIGNATURE, 0, developer.sign(token)))
        read_packet(r)
        reply, out = open_service(r, f"reverse:forward:{spec};tcp:9", 7)
        keep.append(r)
        accepted = reply == A_OKAY and out.startswith(b"OKAY")
        check(f"reverse {spec} {'accepted' if allowed else 'refused'}", accepted == allowed,
              out[:60].decode(errors="replace"))
    listeners = dev.sh("cat /proc/net/tcp /proc/net/tcp6")
    bound = []
    for line in listeners.splitlines()[1:]:
        parts = line.split()
        if len(parts) > 3 and parts[3] == "0A":
            addr, port = parts[1].rsplit(":", 1)
            if int(port, 16) in (27206, 27207, 27208):
                bound.append(addr)
    check("reverse listeners bind 127.0.0.1 only",
          len(bound) == 3 and all(a == "0100007F" for a in bound), str(bound))

    dev.sh(f"kill {pid}")
    dev.adb("forward", "--remove-all", check_rc=False)
    print(f"\n{sum(results)}/{len(results)} checks passed")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
