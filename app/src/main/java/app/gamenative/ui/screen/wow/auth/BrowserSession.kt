package app.gamenative.ui.screen.wow.auth

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.gamenative.ui.screen.wow.WowProfile
import timber.log.Timber

/**
 * Keeps each launcher profile's Battle.net sign-in apart. Battle.net remembers a signed-in browser through cookies, and
 * WebView keeps one cookie store per app, so without this a second profile's sign-in page would open already signed in
 * to the first profile's account and start the game on the wrong one.
 *
 * Where the device's WebView supports profiles, every launcher profile but the default one gets a WebView profile of
 * its own, and switching keeps each account signed in. The default profile stays in WebView's default store, where an
 * install's cookies were before profiles existed.
 *
 * Older WebViews have the one store only. There it belongs to the profile that signed in last, and it's emptied when
 * another profile signs in: that profile's player signs in again, but never lands on someone else's account.
 *
 * All of this must run on the main thread, like the rest of WebView.
 */
internal object BrowserSession {
    private const val PREFS = "battle_net_browser_session"
    private const val KEY_OWNER = "default_store_owner"

    /**
     * Points a new [webView] at [profile]'s cookies. Call it before anything else touches the WebView: a WebView
     * profile can only be chosen while the WebView is unused. The returned function runs its argument once the cookie
     * store is ready, which is at once unless a shared store had to be emptied first; load the page from there.
     */
    fun prepare(context: Context, webView: WebView, profile: WowProfile): (onReady: () -> Unit) -> Unit {
        val webProfile = profile.webProfile
        if (webProfile != null && useOwnStore(webView, webProfile)) return { onReady -> onReady() }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_OWNER, WowProfile.DEFAULT_ID) == profile.id) return { onReady -> onReady() }

        var cleared = false
        var waiting: (() -> Unit)? = null
        clearDefaultStore {
            // The owner is only recorded once the store is empty, so an interrupted clear runs again next time.
            prefs.edit().putString(KEY_OWNER, profile.id).apply()
            cleared = true
            waiting?.invoke()
            waiting = null
        }
        return fun(onReady: () -> Unit) {
            if (cleared) onReady() else waiting = onReady
        }
    }

    /** Signs a removed profile out of the browser. */
    fun delete(context: Context, profile: WowProfile) {
        val webProfile = profile.webProfile ?: return
        if (profilesSupported()) {
            // Fails while a WebView still uses the profile. The profile id isn't reused, so nothing reads it again.
            runCatching { ProfileStore.getInstance().deleteProfile(webProfile) }
                .onFailure { Timber.w(it, "Couldn't delete the WebView profile of ${profile.id}") }
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_OWNER, WowProfile.DEFAULT_ID) == profile.id) {
            clearDefaultStore { prefs.edit().remove(KEY_OWNER).apply() }
        }
    }

    private fun useOwnStore(webView: WebView, webProfile: String): Boolean {
        if (!profilesSupported()) return false
        return runCatching {
            ProfileStore.getInstance().getOrCreateProfile(webProfile)
            WebViewCompat.setProfile(webView, webProfile)
        }.onFailure { Timber.w(it, "Couldn't select a WebView profile, sharing the default cookie store") }.isSuccess
    }

    private fun profilesSupported() =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    private fun clearDefaultStore(onCleared: () -> Unit) {
        runCatching { WebStorage.getInstance().deleteAllData() }
        val cookies = runCatching { CookieManager.getInstance() }.getOrNull()
        if (cookies == null) {
            onCleared()
            return
        }
        cookies.removeAllCookies {
            cookies.flush()
            onCleared()
        }
    }
}
