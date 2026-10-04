package io.github.digipr1me.digiautotap.core

/**
 * What a supporter code opens, in one place: four rows of the Tasks list,
 * Gekkomon Run, Chef's Special, EX Missions and Presets. Everything else is
 * everybody's -- World Search, Summon, Meat Field, Dungeons with the daily
 * dungeon and the Lost Sector Tower, Bond token with the round through all
 * of the Digimon, the Quest Loop, and the free ads with the Ad Skip Pass
 * ([FreeAds]). The player's cut of 2026-10-02 (PLAN_ABSCHLUSS_1_3.md 3.4);
 * until that day Meat Field, Dungeons and the all-Digimon box were a
 * supporter's as well. No code opens anything about an ad (3.6;
 * notes/ads.md, "The app never touches an ad, and nothing that closed one
 * ships").
 *
 * Whether a code holds is not decided here: [Unlock] checks it and the shell
 * keeps the answer (`Supporter.unlocked`, null while it is still being
 * read). What is decided here is what that answer opens, so that the list,
 * the director, the Settings card and a test all ask the same table.
 */
object Paywall {

    /** The rows of the Tasks list a code opens, by key, in the list's order. */
    val TASKS = listOf("runner", "skewer", "exmissions", "preset")

    fun needsCode(key: String): Boolean = key in TASKS

    /** A supporter's row on a phone with no code that holds, or none known yet. */
    fun locked(key: String, unlocked: Boolean?): Boolean = needsCode(key) && unlocked != true

    /**
     * Locked rows read as off and cannot be switched on. The setting itself
     * is left alone while a code is missing: redeeming one later has to give
     * the player back what they had switched on, not a row of defaults --
     * and a row that is free since 2026-10-02 runs on what the player had set
     * while it was locked.
     */
    fun included(s: Settings, key: String, unlocked: Boolean?): Boolean =
        !locked(key, unlocked) && Stored.included(s, key)

    /** What the list says of a locked row, or null where the row is not locked. */
    fun why(key: String, unlocked: Boolean?): String? = when {
        !locked(key, unlocked) -> null
        unlocked == null -> CHECKING
        else -> NEEDS_CODE
    }

    const val CHECKING = "Checking the supporter code..."
    const val NEEDS_CODE = "Needs a supporter code"
}

/**
 * What may be done with a free ad -- View Ads on a summon mode, the film
 * button of a dungeon card, the Meat Field's ads for seeds and cans, Idle
 * Rewards' Extra Rewards -- asked here and nowhere else. Since 2026-10-03
 * one switch decides it, [pass]: the game's Ad Skip Pass as the player says
 * they have it (SkillSettings.AD_PASS_KEY), for everybody alike. With the
 * pass the reward comes with the tap and there is nothing to watch; without
 * it a free ad is never tapped, and no task stops for one left lying.
 * Which tasks take their free ads is each task's pick on the main page's
 * Ad Rewards card ([Stored.AD_PICKS]), and [Stored] folds both into each
 * task's own settings, so a skill reads one answer.
 *
 * No code counts here, and an `ad_watch` that an old settings file may
 * hold is read by nobody (PLAN_ABSCHLUSS_1_3.md 3.6).
 */
data class FreeAds(val pass: Boolean) {

    /** A film button may be tapped: with the pass, and only then. */
    val tap: Boolean get() = pass

    companion object {
        /**
         * The pass taken for granted and the game asking for an ad all the
         * same -- its question "View ads?", or the video already in front:
         * the switch is on and the account has no pass. Nothing is tapped
         * on the question or in the ad, no back key is sent, and the task
         * parks with this one sentence.
         */
        const val PARK = "An ad came although the Ad Skip Pass is switched on: close it yourself -- " +
            "I never touch an ad -- and switch the pass off on the main page if you do not have it."
    }
}
