package app.gamenative.wow.debug

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import app.gamenative.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import timber.log.Timber

/**
 * Settings, Share debug report: one zip with a summary of the device and settings, the app's logs,
 * the game's own logs and the controller test. Emails, the saved login and other [secrets] are
 * replaced before anything is written, so the zip is safe to attach to a GitHub issue.
 */
object DebugReport {
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val MAX_WINE_LOG_BYTES = 8_000_000L
    private const val WINE_LOG_HEAD_CHARS = 300_000
    private const val WINE_KEEP_CHARS = 1_500_000
    private val WINE_KEEP = listOf(":mountmgr:", ":volume:", ":advapi:")
    private const val MAX_LOGCAT_CHARS = 3_000_000
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val TEXT_FILES = setOf("txt", "log")

    /** Replaces each secret and every email address. Secrets shorter than 4 characters are left alone. */
    fun redact(text: String, secrets: Collection<String>): String {
        var out = text
        secrets.filter { it.length >= 4 }.sortedByDescending { it.length }.forEach { out = out.replace(it, "[redacted]") }
        return EMAIL.replace(out, "[email]")
    }

    /** Builds the zip in the cache folder. [summary] is "label: value" lines from the caller's state. */
    fun build(context: Context, summary: List<String>, gameFolder: File?, secrets: Collection<String>): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.cacheDir, "debug_report").apply { deleteRecursively(); mkdirs() }
        val zip = File(dir, "foreverywhere-report-$stamp.zip")
        val external = context.getExternalFilesDir(null) ?: context.filesDir
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            fun put(name: String, text: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(redact(text, secrets).toByteArray())
                out.closeEntry()
            }
            fun putFile(name: String, file: File, maxBytes: Long = MAX_FILE_BYTES) {
                if (file.isFile) runCatching { put(name, tail(file, maxBytes)) }.onFailure { Timber.w(it, "Report skipped $name") }
            }

            put("summary.txt", summaryText(summary))
            put("logcat.txt", logcat())
            // Why Android says this app's processes ended (low memory, native crash, killed by the system...):
            // a session that vanishes without a Java crash leaves no other trace (bnet-test-46, game start).
            runCatching { put("exit_reasons.txt", exitReasons(context)) }.onFailure { Timber.w(it, "Report skipped exit_reasons.txt") }
            putFile("app.log", File(DebugLog.dir(context), "app.log"))
            putFile("app.log.1", File(DebugLog.dir(context), "app.log.1"))
            // CPU and memory use of the last session, sampled every few seconds (SessionProfiler).
            putFile("session_profile.log", SessionProfiler.file(context))
            putFile("fps_session.json", File(context.filesDir, "imagefs/tmp/fps_session.json"))
            // Wine traces can be long, and what starts a crash can be megabytes before its end.
            File(external, "wine_logs/wine_debug.log").takeIf { it.isFile }?.let { log ->
                runCatching { put("wine_debug.log", wineLog(log)) }.onFailure { Timber.w(it, "Report skipped wine_debug.log") }
            }
            newest(File(external, "crash_logs"), 5).forEach { putFile("crash_logs/${it.name}", it) }
            // Battle.net's and its Agent's own logs from the WoW container, newest first: they say what Battle.net
            // was doing when it failed, which Wine's log can't.
            battleNetLogs(context).forEach { (name, file) -> putFile("battle.net/$name", file) }
            // What the FUSE chmod shim did (app/src/main/cpp/gamenative_fuse_chmod.c): loaded in wineserver, and
            // which permission changes Android refused and the shim let through.
            runCatching { com.winlator.container.ContainerManager(context).getContainerById("wow_forever")?.rootDir }
                .getOrNull()?.let { putFile("wine/fuse_chmod.log", File(it, ".wine/fuse_chmod.log")) }
            // Which launch options Battle.net left in the registry for the game's launcher login: key and value
            // names with the size of each value only, never the values (they hold the account and its login token).
            runCatching { com.winlator.container.ContainerManager(context).getContainerById("wow_forever")?.rootDir }
                .getOrNull()?.let { File(it, ".wine/user.reg") }?.takeIf { it.isFile }?.let { reg ->
                    runCatching { put("wine/launch_options.txt", launchOptions(reg)) }
                }
            // The Agent's install database (protobuf): which region each install, Battle.net's own included, is on.
            battleNetProductDb(context)?.let { db ->
                runCatching { put("battle.net/product.db.txt", printable(db.readBytes())) }
                // Battle.net's own install metadata, next to its exe.
                File(db.parentFile?.parentFile?.parentFile?.parentFile, "Program Files (x86)/Battle.net/.build.info")
                    .takeIf { it.isFile }?.let { putFile("battle.net/Battle.net-install.build.info", it) }
            }
            gameFolder?.let { game ->
                // Install metadata Battle.net's Agent reads when it registers ("Locate the game") the install.
                val installRoot = if (game.name == "_forever_") game.parentFile else game
                listOf(".build.info", ".product.db", ".flavor.info", "_forever_/.flavor.info", ".patch.result").forEach { name ->
                    File(installRoot, name).takeIf { it.isFile && it.length() < 64_000 }
                        ?.let { putFile("game_root/${name.replace('/', '_')}", it) }
                }
                put(
                    "game_root/listing.txt",
                    installRoot?.listFiles().orEmpty().sortedBy { it.name }
                        .joinToString("\n") { "${if (it.isDirectory) "d" else "-"} ${it.length()} ${it.name}" },
                )
                // All of the game's logs: which ones matter depends on the problem (connection, login, region).
                newest(File(game, "Logs"), 40).forEach { putFile("game/Logs/${it.name}", it, 300_000) }
                // The game's settings (region, locale, graphics API) and what its folder holds.
                putFile("game/Config.wtf", File(game, "WTF/Config.wtf"))
                put(
                    "game/listing.txt",
                    game.listFiles().orEmpty().sortedBy { it.name }
                        .joinToString("\n") { "${if (it.isDirectory) "d" else "-"} ${it.length()} ${it.name}" },
                )
                newest(File(game, "Errors"), 5).forEach { putFile("game/Errors/${it.name}", it) }
            }
        }
        return zip
    }

    fun share(context: Context, zip: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "WoW Foreverywhere debug report")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share debug report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun summaryText(summary: List<String>) = buildString {
        appendLine("WoW Foreverywhere ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "unknown"
        appendLine("Chip: $soc, hardware ${Build.HARDWARE}")
        summary.forEach { appendLine(it) }
        appendLine()
        appendLine("Input devices")
        runCatching { InputRouting.devices().forEach { appendLine(InputRouting.describe(it)) } }
        if (ControllerTestLog.presses.isNotEmpty()) {
            appendLine()
            append(ControllerTestLog.report(emptyList()).substringBefore("\nInput devices"))
        }
    }

    /**
     * The app's part of the system log: every process running as this app, so Wine and the processes it starts
     * are included, as are Android's denials of them and an earlier run of the app that's still in the buffer.
     * Apps can read their own lines without any permission. Older logcats without `--uid` get this process only.
     */
    private fun logcat(): String = runCatching {
        val (status, text) = runLogcat("--uid", Process.myUid().toString())
        val output = if (status == 0) text else runLogcat("--pid", Process.myPid().toString()).second
        output.takeLast(MAX_LOGCAT_CHARS)
    }.getOrElse { "logcat unavailable: ${it.message}" }

    private fun runLogcat(vararg filter: String): Pair<Int, String> {
        val proc = ProcessBuilder(listOf("logcat", "-d", "-v", "threadtime") + filter).redirectErrorStream(true).start()
        val text = proc.inputStream.use { String(it.readBytes()) }
        return proc.waitFor() to text
    }

    private fun newest(dir: File, count: Int): List<File> =
        dir.listFiles { f -> f.isFile && f.extension.lowercase() in TEXT_FILES }.orEmpty().sortedByDescending { it.lastModified() }.take(count)

    /**
     * Unwind backtraces and handler calls that a nested-exception loop repeats thousands of times (+seh). They filled
     * an 8 MB report on their own and pushed out the exceptions that started the loop.
     */
    // RtlUnwindEx/RtlRestoreContext: wow64 longjmps back on every user32 callback (window messages), ~16 lines a
    // time; in bnet-test-31 they were 99% of the kept tail and pushed the moment of a hang out of the report.
    // Real exceptions still show through their dispatch_exception lines.
    private val WINE_LOOP_NOISE = listOf(
        ":seh:virtual_unwind backtrace:", ":seh:call_seh_handlers ", ":seh:RtlUnwindEx ", ":seh:RtlRestoreContext ",
    )

    /**
     * The Wine log for a report: its first lines, then the last [MAX_WINE_LOG_BYTES] once [WINE_LOOP_NOISE] is
     * left out, with a note of how much was dropped. Read line by line, so a log of any size fits in memory.
     */
    private fun wineLog(file: File): String {
        val head = StringBuilder()
        val tail = ArrayDeque<String>()
        var tailBytes = 0L
        var lines = 0L
        var noise = 0L
        var cut = 0L
        // Drive and permission traces (Battle.net's Agent) are kept whole, wherever they fall in the log.
        val kept = StringBuilder()
        file.bufferedReader().useLines { all ->
            all.forEach { line ->
                lines++
                if (WINE_KEEP.any { line.contains(it) } && kept.length < WINE_KEEP_CHARS) kept.append(line).append('\n')
                if (WINE_LOOP_NOISE.any { line.contains(it) }) { noise++; return@forEach }
                if (head.length < WINE_LOG_HEAD_CHARS) { head.append(line).append('\n'); return@forEach }
                tail.addLast(line)
                tailBytes += line.length + 1
                while (tailBytes > MAX_WINE_LOG_BYTES) { tailBytes -= tail.removeFirst().length + 1; cut++ }
            }
        }
        return buildString {
            append("[wine_debug.log: ${file.length()} bytes, $lines lines; $noise repeated unwind/handler lines left out")
            append(if (cut > 0) "; $cut lines between the head and the tail left out]\n" else "]\n")
            if (kept.isNotEmpty()) append("[drive and permission traces, kept whole]\n").append(kept).append("[end of kept traces]\n")
            append(head)
            if (cut > 0) append("[... $cut lines left out ...]\n")
            tail.forEach { append(it).append('\n') }
        }
    }

    private fun launchOptions(userReg: File): String = buildString {
        var inside = false
        userReg.bufferedReader(Charsets.ISO_8859_1).useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith("[")) {
                    inside = line.contains("Blizzard Entertainment", ignoreCase = true)
                    if (inside) append(line.substringBefore(']')).append("]\n")
                } else if (inside && (line.startsWith("\"") || line.startsWith("@"))) {
                    val name = line.substringBefore("=")
                    val value = line.substringAfter("=", "")
                    append("  ").append(name).append(" = <").append(value.substringBefore(':').take(8).filter { it.isLetter() }.ifEmpty { "str" })
                    append(", ").append(value.length).append(" chars>\n")
                }
            }
        }
        if (isEmpty()) append("No Blizzard Entertainment keys in user.reg\n")
    }

    /** Android's record of this app's last process exits, newest first, with a native crash's tombstone text. */
    private fun exitReasons(context: Context): String {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return "Needs Android 11+\n"
        val manager = context.getSystemService(android.app.ActivityManager::class.java)
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        return buildString {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 12).forEach { info ->
                append("${format.format(java.util.Date(info.timestamp))} pid=${info.pid} process=${info.processName}\n")
                append("  reason=${info.reason} status=${info.status} importance=${info.importance}")
                append(" pss=${info.pss / 1024}MB rss=${info.rss / 1024}MB\n")
                append("  description=${info.description}\n")
                runCatching { info.traceInputStream?.use { it.readBytes() } }.getOrNull()?.let { trace ->
                    append("  trace (${trace.size} bytes):\n").append(printable(trace.copyOf(minOf(trace.size, 48_000)))).append('\n')
                }
            }
            append("\nreason: 1 exit_self, 2 signaled, 3 low_memory, 4 crash (Java), 5 crash_native, 6 anr, 9 excessive_resource_usage, ")
            append("10 user_requested, 11 user_stopped, 12 dependency_died, 13 other\n")
        }
    }

    /** Folders in the WoW container where Battle.net, its Agent and its crash reporter write logs. */
    private val BATTLE_NET_LOG_ROOTS = listOf(
        ".wine/drive_c/users/xuser/AppData/Local/Battle.net",
        ".wine/drive_c/users/xuser/AppData/Roaming/Battle.net",
        ".wine/drive_c/users/xuser/AppData/Local/Blizzard Entertainment",
        ".wine/drive_c/ProgramData/Battle.net",
        ".wine/drive_c/ProgramData/Blizzard Entertainment",
    )
    private val BATTLE_NET_LOG_TYPES = setOf("log", "txt", "json", "config", "info")

    /** The newest Battle.net log files, as zip-relative names, or nothing when the container doesn't exist. */
    private fun battleNetLogs(context: Context): List<Pair<String, File>> {
        val root = runCatching { com.winlator.container.ContainerManager(context).getContainerById("wow_forever")?.rootDir }
            .getOrNull() ?: return emptyList()
        return BATTLE_NET_LOG_ROOTS.map { File(root, it) }.filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown().maxDepth(4)
                .filter { it.isFile && it.extension.lowercase() in BATTLE_NET_LOG_TYPES && it.length() > 0 }
                .map { "${dir.name}/${it.relativeTo(dir).path}" to it }
                .toList()
        }.sortedByDescending { it.second.lastModified() }.take(20)
    }

    private fun battleNetProductDb(context: Context): File? = runCatching {
        com.winlator.container.ContainerManager(context).getContainerById("wow_forever")?.rootDir
    }.getOrNull()?.let { File(it, ".wine/drive_c/ProgramData/Battle.net/Agent/product.db") }?.takeIf { it.isFile && it.length() < 512_000 }

    /** A binary file's readable runs of 3+ characters, one per line, like `strings`. */
    private fun printable(bytes: ByteArray): String = buildString {
        val run = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) run.append(c.toChar()) else { if (run.length >= 3) append(run).append('\n'); run.setLength(0) }
        }
        if (run.length >= 3) append(run)
    }

    /** The last [MAX_FILE_BYTES] of a file: the end of a log is where a problem shows. */
    private fun tail(file: File, maxBytes: Long = MAX_FILE_BYTES): String = RandomAccessFile(file, "r").use { raf ->
        val start = (raf.length() - maxBytes).coerceAtLeast(0)
        raf.seek(start)
        val bytes = ByteArray((raf.length() - start).toInt())
        raf.readFully(bytes)
        (if (start > 0) "[earlier lines left out]\n" else "") + String(bytes)
    }
}
