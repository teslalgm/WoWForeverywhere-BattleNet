package app.gamenative.ui.screen.wow

import com.winlator.core.WineRegistryEditor
import java.io.File
import org.json.JSONObject

/** What a launch starts in the WoW container. */
enum class LaunchTarget {
    /** The WoW client itself, signed in through login.txt or the in-game login screen. */
    GAME,

    /**
     * The official Battle.net desktop app (x64, run through FEX in the same ARM64EC prefix). It signs in the way it does
     * on a PC and starts the game with a launcher token, so the client never takes the direct login path that Blizzard
     * blocks with BLZ51914400 on some devices. Experimental.
     */
    BATTLE_NET,
}

/**
 * The Battle.net desktop app inside the WoW container. The app ships no Battle.net files: the player downloads Blizzard's
 * official Windows installer to Download/[INSTALLER_NAME], which the container sees as D:\.
 */
object BattleNet {
    const val INSTALLER_NAME = "Battle.net-Setup.exe"

    /**
     * FEX build used for Battle.net: assets/fexcore/fexcore-2508-wowbox.tzst, WoW Box's FEX (MIT) built from
     * https://github.com/FrontMage/FEX at 7519b9605209e9e19eaf7be669dae7c1848fe5f1, archive sha256 2beba31f8d0f32c1…
     */
    const val FEX_VERSION = "2508-wowbox"

    /** Android's shared Download folder, mounted as D: (see the container's drives). */
    private const val DOWNLOAD_DIR = "/storage/emulated/0/Download"

    const val INSTALLER_WINDOWS_PATH = "D:\\$INSTALLER_NAME"
    /** The native ARM64 client Battle.net is expected to start (the regular launch runs the same file). */
    const val GAME_EXECUTABLE = "Wow-arm64.exe"

    const val LAUNCHER_WINDOWS_PATH = "C:\\Program Files (x86)\\Battle.net\\Battle.net Launcher.exe"

    private const val LAUNCHER_PREFIX_PATH = ".wine/drive_c/Program Files (x86)/Battle.net/Battle.net Launcher.exe"

    /** Battle.net's own settings file in the Wine user's roaming profile. */
    private const val CONFIG_PREFIX_PATH = ".wine/drive_c/users/xuser/AppData/Roaming/Battle.net/Battle.net.config"

    /**
     * Turns off Battle.net's hardware acceleration. Its embedded Chromium window stays plain grey under Wine with GPU
     * compositing on (first test, Xiaomi 25102PCBEG), so the login page never shows. Keeps every other setting.
     */
    fun disableHardwareAcceleration(containerRoot: File) {
        val config = File(containerRoot, CONFIG_PREFIX_PATH)
        val json = runCatching { JSONObject(config.readText()) }.getOrNull() ?: JSONObject()
        val client = json.optJSONObject("Client") ?: JSONObject().also { json.put("Client", it) }
        if (client.optString("HardwareAcceleration") == "false") return
        client.put("HardwareAcceleration", "false")
        config.parentFile?.mkdirs()
        config.writeText(json.toString(2))
    }

    private const val USER_REG_PREFIX_PATH = ".wine/user.reg"
    private const val DEBUG_KEY = "[Software\\\\Wine\\\\Debug]"
    private const val REPLACEMENTS_KEY = "[Software\\\\Wine\\\\Fonts\\\\Replacements]"
    private const val FONTS_PREFIX_PATH = ".wine/drive_c/windows/Fonts"
    private const val FONT_ASSETS = "battlenet_fonts"
    private const val SYSTEM_REG_PREFIX_PATH = ".wine/system.reg"
    private const val NT_FONTS_KEY = "Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts"
    private const val FONTS_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Fonts"

    /** Registry names for the fonts copied into C:\\windows\\Fonts, which is the only list DirectWrite reads. */
    private val REGISTERED_FONTS = listOf(
        "DejaVu Sans (TrueType)" to "DejaVuSans.ttf",
        "DejaVu Sans Bold (TrueType)" to "DejaVuSans-Bold.ttf",
        "DejaVu Sans Mono (TrueType)" to "DejaVuSansMono.ttf",
        "DejaVu Sans Mono Bold (TrueType)" to "DejaVuSansMono-Bold.ttf",
        "DejaVu Serif (TrueType)" to "DejaVuSerif.ttf",
        "DejaVu Serif Bold (TrueType)" to "DejaVuSerif-Bold.ttf",
        "Source Han Sans CN Regular (OpenType)" to "SourceHanSansCN-Regular.otf",
        "Source Han Sans KR Regular (OpenType)" to "SourceHanSansKR-Regular.otf",
        "Noto Sans Thai (TrueType)" to "NotoSansThai-Regular.ttf",
        "Noto Sans Arabic (TrueType)" to "NotoSansArabic-Regular.ttf",
        "Noto Sans Hebrew (TrueType)" to "NotoSansHebrew-Regular.ttf",
        "Noto Sans Devanagari (TrueType)" to "NotoSansDevanagari-Regular.ttf",
        "Noto Sans Symbols (TrueType)" to "NotoSansSymbols-Regular.ttf",
        "Noto Sans Symbols 2 (TrueType)" to "NotoSansSymbols2-Regular.ttf",
    )

    /**
     * Taken out of DirectWrite's list so the Tahoma replacement below applies there. Wine's Tahoma has only ~850
     * glyphs (no Cyrillic, most of Latin Extended), and Wine's DirectWrite fallback sends all Latin and Cyrillic text
     * to Tahoma only: a character Tahoma lacks made Chromium retry Tahoma, then "sans", one level deeper each time,
     * until the stack ran out (ninth test, +dwrite). GDI keeps Wine's real Tahoma from Wine's own font folder.
     */
    private val UNREGISTERED_FONTS = listOf("Tahoma (TrueType)", "Tahoma Bold (TrueType)")

    /**
     * Windows UI fonts Battle.net's Chromium asks for, and the open font Wine shows in their place. Wine only uses a
     * replacement when the real font isn't installed, so a prefix that has Arial keeps it.
     */
    private val FONT_REPLACEMENTS = listOf(
        // DirectWrite: Wine's fallback table names these families for whole scripts.
        "Tahoma" to "DejaVu Sans", "Tahoma Bold" to "DejaVu Sans", "sans" to "DejaVu Sans", "sans-serif" to "DejaVu Sans",
        // Wine's table names Microsoft Sans Serif for Thai only, but GDI programs ask for it for Latin text, and Noto
        // Sans Thai has no Latin letters, so it stays on DejaVu (no Thai). Every other script in the table that
        // Battle.net's languages, news or symbols can use maps to a font that has it (coverage checked per range).
        "Microsoft Sans Serif" to "DejaVu Sans", "Noto Sans Armenian" to "DejaVu Sans", "Noto Sans Georgian" to "DejaVu Sans",
        "Noto Sans NKo" to "DejaVu Sans", "Noto Sans Lao" to "DejaVu Sans", "Noto Sans Ogham" to "DejaVu Sans",
        "Noto Sans Tifinagh" to "DejaVu Sans", "Noto Sans Lisu" to "DejaVu Sans",
        "Noto Sans Symbols2" to "Noto Sans Symbols 2", "Noto Emoji" to "Noto Sans Symbols 2",
        "Segoe UI Emoji" to "Noto Sans Symbols 2", "Segoe UI Symbol" to "Noto Sans Symbols",
        "Microsoft YaHei" to "Source Han Sans CN", "Microsoft JhengHei" to "Source Han Sans CN",
        "Noto Sans CJK TC" to "Source Han Sans CN", "Meiryo" to "Source Han Sans CN", "Meiryo UI" to "Source Han Sans CN",
        "MS Gothic" to "Source Han Sans CN", "Yu Gothic" to "Source Han Sans CN", "Yu Gothic UI" to "Source Han Sans CN",
        "SimSun" to "Source Han Sans CN",
        "Malgun Gothic" to "Source Han Sans KR", "Gulim" to "Source Han Sans KR",
        "Leelawadee UI" to "Noto Sans Thai", "Nirmala UI" to "Noto Sans Devanagari",
        // Windows UI fonts Chromium asks for by name.
        "Segoe UI" to "DejaVu Sans", "Segoe UI Semibold" to "DejaVu Sans", "Segoe UI Light" to "DejaVu Sans",
        "Arial" to "DejaVu Sans", "Helvetica" to "DejaVu Sans", "Verdana" to "DejaVu Sans", "Calibri" to "DejaVu Sans",
        "MS Shell Dlg" to "DejaVu Sans", "MS Shell Dlg 2" to "DejaVu Sans",
        "Times New Roman" to "DejaVu Serif", "Georgia" to "DejaVu Serif",
        "Courier New" to "DejaVu Sans Mono", "Consolas" to "DejaVu Sans Mono",
    )

    /**
     * Gives the prefix real TrueType fonts for Battle.net. Its main thread recursed inside libcef's text code until
     * the 32-bit stack ran out (sixth and seventh tests): the deepest frame was Chromium's font render-settings lookup,
     * and the prefix only has Wine's Tahoma plus bitmap fonts. WoW Box, which gets Battle.net to its login screen,
     * ships DejaVu in C:\\windows\\Fonts. Copies DejaVu (free licence, see LICENSE-DejaVu.txt) there and maps the
     * Windows UI font names onto it. Returns what the Fonts folder holds afterwards, for the log.
     */
    fun installFonts(context: android.content.Context, containerRoot: File): List<String> {
        val fonts = File(containerRoot, FONTS_PREFIX_PATH).apply { mkdirs() }
        context.assets.list(FONT_ASSETS).orEmpty().forEach { name ->
            val target = File(fonts, name)
            if (!target.isFile) context.assets.open("$FONT_ASSETS/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        setUserRegSection(containerRoot, REPLACEMENTS_KEY, FONT_REPLACEMENTS.map { it.first to it.second })
        // DirectWrite, which Chromium's text code uses, only sees fonts listed in the registry, not files in the
        // Fonts folder. The prefix lists Tahoma under Z:\\opt\\wine, which this app's Wine doesn't use, so DirectWrite
        // had no fonts at all and the stack overflow moved into its FindFamilyName (eighth test).
        File(containerRoot, SYSTEM_REG_PREFIX_PATH).takeIf { it.isFile }?.let { reg ->
            WineRegistryEditor(reg).use { editor ->
                UNREGISTERED_FONTS.forEach { name ->
                    editor.removeValue(NT_FONTS_KEY, name)
                    editor.removeValue(FONTS_KEY, name)
                }
                REGISTERED_FONTS.forEach { (name, file) ->
                    if (File(fonts, file).isFile) {
                        editor.setStringValue(NT_FONTS_KEY, name, file)
                        editor.setStringValue(FONTS_KEY, name, file)
                    }
                }
            }
        }
        return fonts.list().orEmpty().sorted()
    }

    /**
     * Turns Wine's relay trace on or off for calls libcef.dll makes into the windowing DLLs. Only takes effect with
     * +relay in WINEDEBUG.
     */
    fun setRelayTrace(containerRoot: File, enabled: Boolean) = setUserRegSection(
        containerRoot,
        DEBUG_KEY,
        if (enabled) {
            listOf(
                "RelayFromInclude" to "libcef.dll",
                "RelayInclude" to "user32;gdi32;imm32;uxtheme;dwmapi;shcore;ole32;combase;shell32;comctl32",
            )
        } else {
            null
        },
    )

    /** Replaces one key's section in the prefix's user.reg with [values] (string values), or drops it for null. */
    private fun setUserRegSection(containerRoot: File, key: String, values: List<Pair<String, String>>?) {
        val reg = File(containerRoot, USER_REG_PREFIX_PATH)
        if (!reg.isFile) return
        val kept = StringBuilder()
        var skipping = false
        reg.readLines().forEach { line ->
            if (line.startsWith("[")) skipping = line.startsWith(key)
            if (!skipping) kept.append(line).append('\n')
        }
        if (values != null) {
            kept.append('\n').append(key).append(' ').append(System.currentTimeMillis() / 1000).append('\n')
            values.forEach { (name, value) -> kept.append('"').append(name).append("\"=\"").append(value).append("\"\n") }
        }
        reg.writeText(kept.toString())
    }

    fun installerFile(downloadDir: File = File(DOWNLOAD_DIR)): File = File(downloadDir, INSTALLER_NAME)

    fun isInstalled(containerRoot: File?): Boolean = containerRoot != null && File(containerRoot, LAUNCHER_PREFIX_PATH).isFile

    /**
     * The Windows path to start: Battle.net once it is installed in the prefix, else the installer from Download.
     * Null when neither exists, so the caller can tell the player where to put the installer.
     */
    fun executable(containerRoot: File?, downloadDir: File = File(DOWNLOAD_DIR)): String? = when {
        isInstalled(containerRoot) -> LAUNCHER_WINDOWS_PATH
        installerFile(downloadDir).isFile -> INSTALLER_WINDOWS_PATH
        else -> null
    }
}
