package com.engineerclient.adr

import com.engineerclient.EngineerClient
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils

/**
 * Your party, live, for ADR Online: [PartyState] reading Hypixel's chat, plus the dungeon's
 * teammates once in one. A /p list is sent only when the state has a doubt it can't settle (right
 * after joining Hypixel, after joining a Party Finder group, or chat that doesn't add up) - at most
 * one a minute, and its answer is kept out of your chat. One that gets no answer stops the asking for
 * [FAIL_PAUSE_MS]; a second stops it until the game is closed.
 */
object PartyTracker {
    val state = PartyState { EngineerClient.mc.user.name }

    /** Ask this long after joining Hypixel (its own join messages settle first). */
    private const val JOIN_WAIT_MS = 4_000L
    /** Ask this long after a doubt comes up (more of the same may follow, e.g. Party Finder joins). */
    private const val DOUBT_WAIT_MS = 2_500L
    private const val ASK_GAP_MS = 60_000L
    /** How long after asking the answer is hidden. */
    private const val HIDE_MS = 4_000L
    /** No asking for this long after a /p list got no answer. */
    private const val FAIL_PAUSE_MS = 30 * 60_000L

    /** /p lists in a row that got no answer (kept across servers: one per game at most). */
    private var failures = 0
    /** When the /p list sent last should have been answered by, or 0 when it was (or none is out). */
    private var answerBy = 0L
    /** No asking before this (after one went unanswered). */
    private var pausedUntil = 0L

    private var joinedAt = 0L
    private var onHypixel = false
    /** Between a join and a disconnect: the same connection, whatever servers it goes through. */
    private var connected = false
    private var doubtSince = 0L
    private var lastAsk = 0L
    @Volatile private var hideUntil = 0L
    private var lastTeam = emptyList<String>()

    private val NOT_IN_PARTY = Regex("""^You are not (?:currently )?in a party\.$""")
    private val SEPARATOR = Regex("""^-{10,}$""")

    /**
     * Joined a server: on Hypixel, the party from before isn't known (it isn't announced again). A
     * switch between Hypixel's servers can look like a join too, but it's the same connection: the
     * party stays as known.
     */
    fun onJoin() = synchronized(state) {
        onHypixel = ADR.onHypixel()
        if (connected) return
        connected = true
        joinedAt = System.currentTimeMillis()
        lastAsk = 0L
        lastTeam = emptyList()
        state.reset(if (onHypixel) "just joined" else "not on Hypixel")
    }

    fun onDisconnect() = synchronized(state) {
        onHypixel = false
        connected = false
        answerBy = 0L // cut off, not unanswered
        state.reset("not connected")
    }

    /**
     * A system chat message from the server (plain, and with colour codes). Returns true when it's
     * the answer to a /p list this sent, to be hidden.
     */
    fun onChat(plain: String, colored: String): Boolean = synchronized(state) {
        val now = System.currentTimeMillis()
        val lines = plain.split('\n')
        val colors = colored.split('\n').takeIf { it.size == lines.size }
        var hide = now < hideUntil
        for ((i, l) in lines.withIndex()) {
            val listLine = state.line(l, colors?.getOrNull(i), now)
            if (answerBy != 0L && (listLine || NOT_IN_PARTY.matches(l.trim()))) { answerBy = 0L; failures = 0 }
            if (hide && !listLine && !SEPARATOR.matches(l.trim()) && l.isNotBlank() && !NOT_IN_PARTY.matches(l.trim())) hide = false
        }
        // Answered: nothing more to hide.
        if (hide && state.doubt == null) hideUntil = now + 300
        hide
    }

    /** Every client tick while ADR is on. */
    fun tick() = synchronized(state) {
        val now = System.currentTimeMillis()
        if (answerBy != 0L && now > answerBy) {
            answerBy = 0L
            failures++
            pausedUntil = now + FAIL_PAUSE_MS
            EngineerClient.logger.info("[ec] adr: /p list got no answer, " + if (failures >= 2) "not asking again until the game restarts" else "not asking for ${FAIL_PAUSE_MS / 60_000} min")
        }
        // In a dungeon, its teammates are the party.
        if (DungeonUtils.inDungeons) {
            val team = DungeonUtils.dungeonTeammates.map { it.name }.filter { it.matches(NAME) }
            if (team.isNotEmpty() && team != lastTeam) { lastTeam = team; state.teammates(team) }
        } else lastTeam = emptyList()

        val doubt = state.doubt
        if (doubt == null) { doubtSince = 0L; return }
        if (doubtSince == 0L) doubtSince = now
        if (!onHypixel || EngineerClient.mc.player == null) return
        if (now - joinedAt < JOIN_WAIT_MS || now - doubtSince < DOUBT_WAIT_MS || now - lastAsk < ASK_GAP_MS) return
        ask(now, doubt)
    }

    /**
     * Asks Hypixel for the party now (its answer kept out of chat), for a screen that needs it: when it isn't
     * known, at most every 10 s. Needs ADR on (it reads the answer).
     */
    fun askIfUnknown() = synchronized(state) {
        val now = System.currentTimeMillis()
        if (state.known || !onHypixel || EngineerClient.mc.player == null || now - lastAsk < 10_000L) return
        ask(now, "asked for")
    }

    /**
     * The Leap Order screen's Recheck Party: a /p list now, whatever happened before (unanswered ones and the
     * pause after them forgotten). At most one every 2 s. Its answer is read and kept out of chat with ADR on;
     * with ADR off it shows in chat, and Odin's party list reads it.
     */
    fun recheck() = synchronized(state) {
        val now = System.currentTimeMillis()
        if (EngineerClient.mc.player == null || now - lastAsk < 2_000L) return
        failures = 0; pausedUntil = 0L; answerBy = 0L
        ask(now, "recheck")
    }

    /** A /p list is out and not answered yet. */
    val checking: Boolean get() = answerBy != 0L && System.currentTimeMillis() < answerBy

    /** Sends a /p list, unless one went unanswered lately (twice: ever, this game). */
    private fun ask(now: Long, why: String) {
        if (answerBy != 0L || failures >= 2 || now < pausedUntil) return
        lastAsk = now
        hideUntil = now + HIDE_MS
        answerBy = now + HIDE_MS
        EngineerClient.logger.info("[ec] adr: /p list ($why)")
        sendCommand("p list")
    }

    private val NAME = Regex("""^\w{1,16}$""")
}
