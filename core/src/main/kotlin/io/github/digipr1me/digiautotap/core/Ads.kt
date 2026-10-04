package io.github.digipr1me.digiautotap.core

/**
 * The window in front, as the system names it: the package, and the class
 * of the activity the last window event named in it (null where no event
 * named one since the package changed).
 */
data class Front(val pkg: String?, val cls: String?)

/**
 * The one thing only the accessibility service can tell about an ad: the
 * activity in front (PLAN_WERBUNG.md 1). An ad is not drawn on the game's
 * canvas; it is an activity of its own inside the game's package, so it is
 * told by that and never by pixels. Nothing here touches anything: the app
 * never taps, clicks or sends back into an ad (notes/ads.md, "The app never
 * touches an ad, and nothing that closed one ships"), and the service holds
 * every gesture while one is in front.
 */
interface AdHand {
    /** The game's package, or null while none is known. */
    val game: String?

    /** Package and activity in front, asked now. */
    fun front(): Front
}

/** What the ad classes are, and which network each one is (PLAN_WERBUNG.md 1). */
object Ads {
    /** The game itself: `com.bandainamcoent.dgup_ww`'s one activity. Every other one of its package is foreign. */
    const val GAME_ACTIVITY = "com.google.firebase.MessagingUnityPlayerActivity"

    /**
     * The fullscreen activities of the six networks the game's ironSource
     * LevelPlay mediation bundles, read off the manifest of 1.4.0 on
     * 2026-09-26, as prefixes: an SDK update renames a class sooner than a
     * package. What is foreign and in none of them is logged as `unknown`
     * and not guessed at.
     */
    val NETWORKS: List<Pair<String, String>> = listOf(
        "com.google.android.gms.ads.AdActivity" to "AdMob",
        "com.unity3d.services.ads." to "Unity",
        "com.unity3d.ads." to "Unity",
        "com.ironsource.sdk.controller." to "ironSource",
        "com.applovin.adview." to "AppLovin",
        "com.fyber.inneractive.sdk.activities." to "Fyber",
        "com.inmobi.ads." to "InMobi",
    )

    fun network(cls: String?): String? =
        if (cls == null) null else NETWORKS.firstOrNull { cls.startsWith(it.first) }?.second

    /** The class in front where it is one of the game's package and not the game: an ad, or something unnamed. */
    fun foreign(f: Front, game: String?): String? =
        if (game != null && f.pkg == game && f.cls != null && f.cls != GAME_ACTIVITY) f.cls else null

    /** The game itself in front. A package with no class named yet is the game until an event says otherwise. */
    fun isGame(f: Front, game: String?): Boolean =
        game != null && f.pkg == game && (f.cls == null || f.cls == GAME_ACTIVITY)

    /**
     * The game's own node: the Unity player's view carries this content
     * description, and no ad's window does. Counted on 2026-09-28 over the
     * 1527 node trees AdProbe had kept on LDPlayer instance 1 since S1: 606
     * were taken with an ad's web page in the active window (AdMob and
     * Unity), none of them with this node; every tree of the game's window
     * has it, also the five under the game's own Bandai Namco ID sign-in
     * web page.
     */
    const val GAME_VIEW = "Game view"

    /**
     * How long an event's word must have stood before the window may
     * overrule it ([stale]). An ad's activity is in front 0.6 to 1.0 s
     * after its start (S1: Displayed 0.64 and 0.99 s), and until then the
     * game's window is still the active one; a heal sooner than this would
     * take a starting ad for the game, and no second event comes to
     * correct it.
     */
    const val STALE_AFTER_MS = 3000L

    /**
     * Is the ad [f] names already gone, and only the last window event
     * still says it? Measured 2026-09-28 on instance 1: an AdMob pod that
     * ends by itself on the way back from the store sends its events in
     * the order store, game, AdActivity -- the ad's own event last, 0.3 to
     * 1.3 s after the game had the focus -- and the game sends none after
     * it, because its window never lost the focus again. Four of six such
     * ads left the class standing until something else came to the front
     * (61 s, 177 s and 90 s until the next ad, and 598 s and on after the
     * last one; W2's Idle Rewards ad of 03:26 the same, 112 s until this
     * app was opened), and the director said `ad` on the dungeon list, the
     * Meat Field and the Explore menu, with every gesture held over the
     * game behind it. The class comes from events; the package is polled
     * ([Front]); so the poll asks the window too, once the class has stood
     * [STALE_AFTER_MS]: the game's own node ([GAME_VIEW]) in the active
     * window is the game.
     */
    fun stale(f: Front, game: String?, heldMs: Long, gameView: () -> Boolean): Boolean =
        foreign(f, game) != null && heldMs >= STALE_AFTER_MS && gameView()

    /**
     * Where an ad sends the player without being asked: the Play Store
     * (measured, `com.android.vending/...HsdpAlias`, from the game's uid and
     * with no input), a browser, or a network's own in-app browser. One of
     * them in front after a film button is as much an ad as the ad itself.
     */
    val DETOUR_PACKAGES = setOf(
        "com.android.vending", "com.android.chrome", "com.android.browser",
        "com.google.android.googlequicksearchbox", "com.google.android.webview",
        "org.mozilla.firefox", "com.sec.android.app.sbrowser", "com.mi.globalbrowser",
        "com.opera.browser", "com.microsoft.emmx", "com.brave.browser", "com.huawei.browser",
    )

    fun detour(f: Front, game: String?): Boolean =
        (f.pkg != null && f.pkg != game && f.pkg in DETOUR_PACKAGES) ||
            (f.pkg == game && f.cls?.endsWith("OpenUrlActivity") == true)

    /** An ad, or where one sends the player, in front: asked of [hand], no frame; false where there is none to ask. */
    fun inFront(hand: AdHand?): Boolean {
        val f = hand?.front() ?: return false
        return foreign(f, hand.game) != null || detour(f, hand.game)
    }
}
