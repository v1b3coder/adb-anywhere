/*
 * StaleDaemon - finds and kills every vendored adbd on the device without a
 * pid-file lookup, a process-name filter or a lock.
 */
package cz.slush.adbanywhere.adb

object StaleDaemon {
    /**
     * Shell function that SIGKILLs every vendored adbd and waits for it to
     * disappear. Returns 0 when none remain, 1 when a process survives.
     *
     * The app always starts the vendored daemon with --listen; Android's system
     * adbd does not accept that option. /proc/<pid>/cmdline therefore identifies
     * ours regardless of the remote directory, a stale pid file or a replacement
     * binary. The literal --listen is split into two variables so the shell that
     * runs this script does not match itself.
     */
    fun killFunction(): String = """
        kill_stale_adbd() {
          l=--lis
          t=ten
          for d in /proc/[0-9]*; do
            p=${'$'}{d#/proc/}
            [ "${'$'}p" = "${'$'}m" ] && continue
            [ -r "${'$'}d/cmdline" ] || continue
            c=${'$'}(tr '\0' ' ' < "${'$'}d/cmdline" 2>/dev/null)
            case "${'$'}c" in *adbd*"${'$'}l${'$'}t"*) kill -9 "${'$'}p" 2>/dev/null || true ;; esac
          done
          i=0
          while [ "${'$'}i" -lt 30 ]; do
            n=0
            for d in /proc/[0-9]*; do
              p=${'$'}{d#/proc/}
              [ "${'$'}p" = "${'$'}m" ] && continue
              [ -r "${'$'}d/cmdline" ] || continue
              c=${'$'}(tr '\0' ' ' < "${'$'}d/cmdline" 2>/dev/null)
              case "${'$'}c" in *adbd*"${'$'}l${'$'}t"*) n=1 ;; esac
            done
            [ "${'$'}n" = 0 ] && return 0
            sleep 0.1
            i=${'$'}((i+1))
          done
          return 1
        }
    """.trimIndent()

    /** Stops every vendored adbd and reports whether none remain. */
    fun stopScript(): String = "m=${'$'}${'$'}\n" + killFunction() + "\nkill_stale_adbd"

    /**
     * Stops every vendored adbd and only then starts the replacement. The
     * caller runs this detached, because the kill drops the shell that started it.
     * The script goes through a file so its own command line does not contain the
     * --listen that identifies the daemon.
     */
    fun restartScript(start: String): String =
        "m=${'$'}${'$'}\n" + killFunction() +
            "\nkill_stale_adbd || exit 1\nsleep 0.4\n$start\nrm -f \"${'$'}0\""
}
