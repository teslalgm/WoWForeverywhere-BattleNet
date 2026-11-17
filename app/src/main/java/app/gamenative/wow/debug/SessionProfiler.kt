package app.gamenative.wow.debug

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * While a game session runs with debug logging on, notes every few seconds which of the app's processes use the
 * CPU and the memory, and how much memory the phone has left. A game started from Battle.net stuttered where a
 * normal start runs smoothly (bnet-test-51), and nothing else in a report says whether Battle.net's own processes,
 * memory pressure or something else is the cause. Written to session_profile.log next to app.log.
 */
object SessionProfiler {
    private const val INTERVAL_MS = 5_000L
    private const val MAX_SAMPLES = 2_000
    @Volatile private var running = false

    fun file(context: Context) = File(DebugLog.dir(context), "session_profile.log")

    fun start(context: Context, label: String) {
        if (!DebugLog.isEnabled(context) || running) return
        running = true
        val out = file(context).apply { parentFile?.mkdirs(); writeText("session: $label\n") }
        thread(isDaemon = true, name = "session-profiler") {
            try { sample(out) } catch (e: Throwable) { out.appendText("profiler stopped: $e\n") } finally { running = false }
        }
    }

    private class Proc(val name: String, val ticks: Long, val rssKb: Long)

    private fun sample(out: File) {
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
        val cores = Runtime.getRuntime().availableProcessors()
        var before = processes()
        var sawWine = false
        var idle = 0
        repeat(MAX_SAMPLES) {
            Thread.sleep(INTERVAL_MS)
            val now = processes()
            val hasWine = now.values.any { it.name == "wineserver" }
            if (hasWine) { sawWine = true; idle = 0 } else if (sawWine && ++idle >= 3) return
            // CPU as a share of one core over the interval; /proc counts 100 ticks a second.
            val cpu = now.mapNotNull { (pid, p) -> before[pid]?.let { p.name to (p.ticks - it.ticks) * 1000.0 / INTERVAL_MS } }
                .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            val rss = now.values.groupBy({ it.name }, { it.rssKb }).mapValues { it.value.sum() / 1024 }
            val mem = meminfo()
            out.appendText(buildString {
                append(clock.format(Date())).append(" free=").append(mem["MemAvailable"]).append("MB swapFree=").append(mem["SwapFree"])
                append("/").append(mem["SwapTotal"]).append("MB cores=").append(cores)
                append(" cpu%:")
                cpu.entries.sortedByDescending { it.value }.take(8).filter { it.value >= 1 }
                    .forEach { append(' ').append(it.key).append('=').append(it.value.toInt()) }
                append(" | rssMB:")
                rss.entries.sortedByDescending { it.value }.take(7).forEach { append(' ').append(it.key).append('=').append(it.value) }
                append('\n')
            })
            before = now
        }
    }

    /** This app's own processes (others can't be read): name, CPU ticks used so far, resident memory. */
    private fun processes(): Map<Int, Proc> = File("/proc").listFiles().orEmpty().mapNotNull { dir ->
        val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
        runCatching {
            val stat = File(dir, "stat").readText()
            val name = stat.substringAfter('(').substringBeforeLast(')')
            val fields = stat.substringAfterLast(')').trim().split(' ')
            // After the name: state is field 0, so utime and stime are 11 and 12, rss (pages) is 21.
            pid to Proc(name, fields[11].toLong() + fields[12].toLong(), fields[21].toLong() * 4)
        }.getOrNull()
    }.toMap()

    private fun meminfo(): Map<String, Long> = runCatching {
        File("/proc/meminfo").readLines().mapNotNull { line ->
            val parts = line.split(Regex("[:\\s]+"))
            parts.getOrNull(1)?.toLongOrNull()?.let { parts[0] to it / 1024 }
        }.toMap()
    }.getOrDefault(emptyMap())
}
