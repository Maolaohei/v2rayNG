package com.v2ray.ang.root

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Root command runner backed by the `su` binary.
 *
 * Scripts are written to the app's private root runtime dir and executed with
 * `su -c sh <file>` so shell quoting stays simple. stderr is merged into stdout to
 * avoid pipe-buffer deadlocks.
 *
 * Short probes reuse a long-lived `su` shell when available to avoid Magisk/KernelSU
 * spawn jitter that previously made health checks flap.
 */
object RootShell {

    data class Result(val code: Int, val output: String) {
        val success: Boolean get() = code == 0
    }

    private val lock = Any()
    private val markerSeq = AtomicLong(0L)
    private const val SESSION_IDLE_MS = 45_000L

    @Volatile private var session: SuSession? = null
    @Volatile private var sessionLastUsedMs: Long = 0L

    /** Write [script] to `<filesDir>/root/<name>` and run it as root. */
    fun runScript(context: Context, name: String, script: String): Result {
        val dir = File(context.filesDir, AppConfig.ROOT_RUNTIME_DIR).apply { mkdirs() }
        val file = File(dir, name).apply {
            writeText(script)
            setExecutable(true, false)
        }
        // Prefer one-shot for long setup/teardown scripts: safer than feeding huge bodies
        // into a sticky shell, and still reuses session for later short probes.
        return execOneShot("sh ${file.absolutePath}", timeoutSeconds = 45)
    }

    fun exec(command: String, timeoutSeconds: Long = 30): Result {
        val useSession = timeoutSeconds <= 15 && command.length < 2_000
        if (useSession) {
            synchronized(lock) {
                try {
                    val result = execOnSessionLocked(command, timeoutSeconds)
                    if (result != null) return result
                } catch (e: Exception) {
                    LogUtil.w(AppConfig.TAG, "RootShell: session exec failed, fallback one-shot (${e.message})")
                    closeSessionLocked()
                }
            }
        }
        return execOneShot(command, timeoutSeconds)
    }

    /** Drop sticky su session (call on full stop). */
    fun closeSession() {
        synchronized(lock) { closeSessionLocked() }
    }

    private fun execOnSessionLocked(command: String, timeoutSeconds: Long): Result? {
        val now = System.currentTimeMillis()
        val existing = session
        if (existing != null && (!existing.isAlive() || now - sessionLastUsedMs > SESSION_IDLE_MS)) {
            closeSessionLocked()
        }
        val s = session ?: openSessionLocked() ?: return null
        val id = markerSeq.incrementAndGet()
        val begin = "__V2NG_BEGIN_${id}__"
        val end = "__V2NG_END_${id}__"
        // Run command in a subshell so `set -e` / early exit cannot kill the sticky shell.
        s.stdin.write("echo $begin\n")
        s.stdin.write("sh -c ${shellSingleQuote(command)}\n")
        s.stdin.write("echo $end:\$?\n")
        s.stdin.flush()

        val out = StringBuilder()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        var started = false
        var code = -1
        while (System.nanoTime() < deadline) {
            if (!s.isAlive()) {
                closeSessionLocked()
                return null
            }
            val line = s.readLine(deadline) ?: break
            if (!started) {
                if (line.trim() == begin) {
                    started = true
                }
                continue
            }
            val trimmed = line.trim()
            if (trimmed.startsWith(end)) {
                code = trimmed.substringAfter(':', "-1").toIntOrNull() ?: -1
                sessionLastUsedMs = System.currentTimeMillis()
                return Result(code, out.toString())
            }
            out.append(line).append('\n')
        }
        closeSessionLocked()
        return null
    }

    private fun openSessionLocked(): SuSession? {
        return try {
            val process = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            val stdin = BufferedWriter(OutputStreamWriter(process.outputStream))
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            // Prove the shell is actually root and responsive.
            stdin.write("id -u\n")
            stdin.flush()
            val idLine = readLineWithTimeout(stdout, process, 5_000L)?.trim()
            if (idLine != "0") {
                process.destroy()
                LogUtil.w(AppConfig.TAG, "RootShell: sticky su rejected (id=$idLine)")
                return null
            }
            val s = SuSession(process, stdin, stdout)
            session = s
            sessionLastUsedMs = System.currentTimeMillis()
            LogUtil.i(AppConfig.TAG, "RootShell: sticky su session opened")
            s
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "RootShell: sticky su open failed (${e.message})")
            null
        }
    }

    private fun closeSessionLocked() {
        val s = session
        session = null
        if (s == null) return
        try {
            s.stdin.write("exit\n")
            s.stdin.flush()
        } catch (_: Exception) {
        }
        try {
            s.process.destroy()
        } catch (_: Exception) {
        }
    }

    private fun execOneShot(command: String, timeoutSeconds: Long): Result {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                LogUtil.e(AppConfig.TAG, "RootShell: timed out: $command")
                return Result(-1, output)
            }
            val result = Result(process.exitValue(), output)
            if (!result.success) {
                LogUtil.w(AppConfig.TAG, "RootShell: '$command' exited ${result.code}: ${output.trim()}")
            }
            result
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "RootShell: failed to run '$command'", e)
            Result(-1, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun shellSingleQuote(value: String): String {
        // POSIX-safe single-quote wrap: abc'def -> 'abc'\''def'
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun readLineWithTimeout(reader: BufferedReader, process: Process, timeoutMs: Long): String? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) return null
            if (reader.ready()) {
                return reader.readLine()
            }
            try {
                Thread.sleep(10L)
            } catch (_: InterruptedException) {
                return null
            }
        }
        return null
    }

    private class SuSession(
        val process: Process,
        val stdin: BufferedWriter,
        private val stdout: BufferedReader,
    ) {
        fun isAlive(): Boolean = try {
            // Process.isAlive is API 26+; fall back for minSdk 24.
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                process.isAlive
            } else {
                try {
                    process.exitValue()
                    false
                } catch (_: IllegalThreadStateException) {
                    true
                }
            }
        } catch (_: Exception) {
            false
        }

        fun readLine(deadlineNano: Long): String? {
            while (System.nanoTime() < deadlineNano) {
                if (!isAlive()) return null
                if (stdout.ready()) {
                    return stdout.readLine()
                }
                try {
                    Thread.sleep(10L)
                } catch (_: InterruptedException) {
                    return null
                }
            }
            return null
        }
    }
}
