package app.gamenative.ui.screen.wow

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

    /**
     * Turns Wine's relay trace on or off for calls libcef.dll makes into the windowing DLLs. Battle.net's main thread
     * recurses inside libcef until its 32-bit stack runs out (sixth test); the return address of every such call
     * shows which libcef code is on that loop. Only takes effect with +relay in WINEDEBUG.
     */
    fun setRelayTrace(containerRoot: File, enabled: Boolean) {
        val reg = File(containerRoot, USER_REG_PREFIX_PATH)
        if (!reg.isFile) return
        val kept = StringBuilder()
        var skipping = false
        reg.readLines().forEach { line ->
            if (line.startsWith("[")) skipping = line.startsWith(DEBUG_KEY)
            if (!skipping) kept.append(line).append('\n')
        }
        if (enabled) {
            kept.append('\n').append(DEBUG_KEY).append(' ').append(System.currentTimeMillis() / 1000).append('\n')
            kept.append("\"RelayFromInclude\"=\"libcef.dll\"\n")
            kept.append("\"RelayInclude\"=\"user32;gdi32;imm32;uxtheme;dwmapi;shcore;ole32;combase;shell32;comctl32\"\n")
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
