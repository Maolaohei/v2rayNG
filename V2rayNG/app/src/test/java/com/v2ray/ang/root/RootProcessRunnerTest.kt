package com.v2ray.ang.root

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Ported from upstream RootProcessRunnerTest (upstream 61c4e40e). Windows dev boxes do not
 * usually have `sh` on PATH, so resolve Git for Windows' shell when the plain name fails and
 * skip the suite only when no POSIX shell can be started at all.
 */
class RootProcessRunnerTest {
    private val shell: String? = resolveShell()

    @BeforeEach
    fun requireShell() {
        assumeTrue(shell != null, "no usable sh on this machine")
    }

    private fun run(script: String, timeoutMillis: Long, outputLimit: Int = 65536) =
        RootProcessRunner.run(listOf(requireNotNull(shell), "-c", script), timeoutMillis, outputLimit)

    @Test fun `a silent blocked command respects its deadline`() {
        val start = System.nanoTime()
        val result = run("sleep 5", 150)
        assertEquals(-1, result.code)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
    }
    @Test fun `inherited stdout cannot hold a completed command open`() {
        val start = System.nanoTime()
        val result = run("sleep 5 & echo complete", 3000)
        assertEquals(0, result.code)
        assertTrue(result.output.contains("complete"))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
    }
    @Test fun `large output is drained without unbounded retention`() {
        val result = run("yes x | head -c 1048576", 3000, 4096)
        assertEquals(0, result.code)
        assertEquals(4096, result.output.length)
    }
    @Test fun `failure status and diagnostic output survive`() {
        val result = run("echo lock-unavailable >&2; exit 4", 1000)
        assertEquals(4, result.code)
        assertTrue(result.output.contains("lock-unavailable"))
    }
}

private fun resolveShell(): String? = listOf(
    "sh",
    "C:\\Program Files\\Git\\bin\\sh.exe",
    "C:\\Program Files (x86)\\Git\\bin\\sh.exe"
).firstOrNull { candidate ->
    try {
        val probe = ProcessBuilder(candidate, "-c", "exit 0").redirectErrorStream(true).start()
        val finished = probe.waitFor(5, TimeUnit.SECONDS)
        probe.destroy()
        finished && probe.exitValue() == 0
    } catch (_: Exception) {
        false
    }
}
