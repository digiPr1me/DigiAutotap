package io.github.digipr1me.digiautotap.core

/**
 * The window in front, for a flow test that plays an ad: the game, or an
 * activity of its own package that is not the game ([Ads.foreign]), set by
 * the test or by the world's answer to a tap. [AdHand] has no gesture of
 * its own, so a test counts what went to the capture -- its taps and its
 * backs -- while one stood in front.
 */
class FakeFront(override val game: String = GAME) : AdHand {
    var front = Front(game, Ads.GAME_ACTIVITY)

    override fun front(): Front = front

    fun ad(cls: String = ADMOB) { front = Front(game, cls) }
    fun store() { front = Front("com.android.vending", "com.google.android.finsky.HsdpAlias") }
    fun backInGame() { front = Front(game, Ads.GAME_ACTIVITY) }

    /** An ad of the game's package in front. */
    val inAd: Boolean get() = Ads.foreign(front, game) != null

    companion object {
        const val GAME = "com.bandainamcoent.dgup_ww"
        const val ADMOB = "com.google.android.gms.ads.AdActivity"
        const val UNITY = "com.unity3d.ads.adplayer.FullScreenWebViewDisplay"
    }
}
