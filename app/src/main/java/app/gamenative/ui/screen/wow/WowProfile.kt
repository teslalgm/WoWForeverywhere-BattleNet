package app.gamenative.ui.screen.wow

import android.content.Context
import app.gamenative.ui.screen.wow.auth.BattleNetBrowserLogin
import com.winlator.container.ContainerManager
import com.winlator.core.FileUtils
import com.winlator.xenvironment.ImageFs
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * One "machine" in the launcher, for players with more than one Battle.net account. Each profile has its own
 * Wine container, machine identity, saved login and browser-login session, so switching accounts means starting
 * another profile instead of signing out. Game files, add-ons and Config.wtf stay shared.
 *
 * The default profile is the install as it was before profiles existed: it keeps the container id and every file and
 * preference name, so nothing changes for players who never add a second profile.
 */
data class WowProfile(val id: String, val name: String) {
    val isDefault get() = id == DEFAULT_ID

    /**
     * The container this profile runs in. The suffix is never a bare number: ContainerUtils reads a trailing
     * "_<number>" in a container id as a game id.
     */
    val containerId get() = if (isDefault) DEFAULT_CONTAINER_ID else "${DEFAULT_CONTAINER_ID}_$id"

    /** Appended to the names of per-profile files and preferences. Empty for the default profile. */
    val storageSuffix get() = if (isDefault) "" else "_$id"

    /** The WebView profile holding this profile's Battle.net cookies, or null for WebView's own default profile. */
    val webProfile get() = if (isDefault) null else "wow_$id"

    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_CONTAINER_ID = "wow_forever"
        val DEFAULT = WowProfile(DEFAULT_ID, "Main")
    }
}

internal object WowProfiles {
    const val MAX_PROFILES = 8
    const val MAX_NAME_LENGTH = 24

    private const val PREFS = "wow_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active"
    private const val KEY_NEXT_INDEX = "next_index"
    private const val FIRST_INDEX = 2
    private val ID_PATTERN = Regex("p[0-9]{1,6}")

    /** Every profile, the default one first. Never empty. */
    fun all(context: Context): List<WowProfile> = decode(prefs(context).getString(KEY_PROFILES, null))

    /** The profile the launcher shows and starts. Falls back to the default one if the saved choice is gone. */
    fun active(context: Context): WowProfile {
        val id = prefs(context).getString(KEY_ACTIVE, null)
        return all(context).firstOrNull { it.id == id } ?: WowProfile.DEFAULT
    }

    fun select(context: Context, profile: WowProfile) {
        prefs(context).edit().putString(KEY_ACTIVE, profile.id).apply()
    }

    /** The profile that owns [containerId]. A container no profile owns belongs to the default one, as before. */
    fun forContainer(context: Context, containerId: String): WowProfile =
        all(context).firstOrNull { it.containerId == containerId } ?: WowProfile.DEFAULT

    /** Adds a profile, or returns null if [name] isn't usable or the list is full. */
    fun add(context: Context, name: String): WowProfile? {
        val profiles = all(context)
        if (profiles.size >= MAX_PROFILES) return null
        val cleaned = cleanName(name, profiles) ?: return null
        val prefs = prefs(context)
        // An index is never handed out twice, so a new profile can't pick up what a deleted one left behind: a
        // container, a machine identity or a saved login. The check for leftovers covers a lost counter.
        var index = maxOf(prefs.getInt(KEY_NEXT_INDEX, FIRST_INDEX), FIRST_INDEX)
        while (hasLeftovers(context, WowProfile("p$index", cleaned))) index++
        val profile = WowProfile("p$index", cleaned)
        prefs.edit()
            .putInt(KEY_NEXT_INDEX, index + 1)
            .putString(KEY_PROFILES, encode(profiles + profile))
            .apply()
        return profile
    }

    /** Renames [profile], or returns null if [name] isn't usable. */
    fun rename(context: Context, profile: WowProfile, name: String): WowProfile? {
        val profiles = all(context)
        if (profiles.none { it.id == profile.id }) return null
        val cleaned = cleanName(name, profiles.filterNot { it.id == profile.id }) ?: return null
        val renamed = profile.copy(name = cleaned)
        prefs(context).edit()
            .putString(KEY_PROFILES, encode(profiles.map { if (it.id == profile.id) renamed else it }))
            .apply()
        return renamed
    }

    /** Takes [profile] off the list. Its container and saved data are removed separately. */
    fun remove(context: Context, profile: WowProfile) {
        require(!profile.isDefault) { "The default profile can't be removed" }
        val prefs = prefs(context)
        val edit = prefs.edit().putString(KEY_PROFILES, encode(all(context).filterNot { it.id == profile.id }))
        if (prefs.getString(KEY_ACTIVE, null) == profile.id) edit.remove(KEY_ACTIVE)
        edit.apply()
    }

    /**
     * Deletes what a removed profile kept on this device: its container, machine identity, saved login and login
     * setting. Its browser session is main-thread work and goes through BrowserSession. Run this off the main thread.
     * Game files are never touched: they live outside the container, and the links to them aren't followed.
     */
    fun deleteData(context: Context, profile: WowProfile) {
        require(!profile.isDefault) { "The default profile can't be removed" }
        val manager = ContainerManager(context)
        val container = manager.getContainerById(profile.containerId)
        val rootDir = container?.rootDir
        if (rootDir != null && rootDir.name == "${ImageFs.USER}-${profile.containerId}") {
            if (!FileUtils.delete(rootDir)) Timber.w("Couldn't fully delete the container of profile ${profile.id}")
            // The active-container link may have pointed at the folder that's now gone.
            val activeLink = File(rootDir.parentFile, ImageFs.USER)
            if (!activeLink.exists()) {
                ContainerManager(context).getContainerById(WowProfile.DEFAULT_CONTAINER_ID)?.let(manager::activateContainer)
            }
        }
        WowMachineIdentity.forget(context, profile)
        BattleNetSignIn.delete(context, profile)
        BattleNetBrowserLogin.delete(context, profile)
    }

    private fun hasLeftovers(context: Context, profile: WowProfile): Boolean = runCatching {
        val container = File(ImageFs.find(context).rootDir, "home/${ImageFs.USER}-${profile.containerId}")
        val savedLogin = File(context.applicationInfo.dataDir, "shared_prefs/battle_net_login${profile.storageSuffix}.xml")
        container.exists() || savedLogin.exists() || WowMachineIdentity.file(context, profile).exists()
    }.getOrDefault(false)

    /** Trimmed, single-spaced and capped, or null when empty or already taken by one of [others]. */
    internal fun cleanName(name: String, others: List<WowProfile>): String? {
        val cleaned = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LENGTH).trim()
        if (cleaned.isEmpty() || others.any { it.name.equals(cleaned, ignoreCase = true) }) return null
        return cleaned
    }

    internal fun encode(profiles: List<WowProfile>): String = JSONArray().apply {
        profiles.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
    }.toString()

    /** Unreadable or foreign entries are dropped rather than failing: the default profile must always be there. */
    internal fun decode(json: String?): List<WowProfile> {
        val saved = runCatching {
            val array = JSONArray(json ?: "[]")
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val id = entry.optString("id")
                val name = entry.optString("name").trim()
                if (name.isEmpty() || (id != WowProfile.DEFAULT_ID && !ID_PATTERN.matches(id))) null else WowProfile(id, name)
            }
        }.onFailure { Timber.w(it, "Saved profiles unreadable, keeping the default profile only") }
            .getOrDefault(emptyList())
            .distinctBy { it.id }
        val default = saved.firstOrNull { it.isDefault } ?: WowProfile.DEFAULT
        return listOf(default) + saved.filterNot { it.isDefault }.take(MAX_PROFILES - 1)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
