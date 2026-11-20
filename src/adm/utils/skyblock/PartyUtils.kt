package com.adm.utils.skyblock

import com.adm.AdmMod.mc
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.PartyEvent
import com.adm.events.core.on
import com.adm.utils.handlers.schedule
import net.hypixel.modapi.HypixelModAPI
import net.hypixel.modapi.packet.impl.clientbound.ClientboundHelloPacket
import net.hypixel.modapi.packet.impl.clientbound.ClientboundPartyInfoPacket
import net.hypixel.modapi.packet.impl.serverbound.ServerboundPartyInfoPacket
import kotlin.jvm.optionals.getOrNull

object PartyUtils {

    private val joinedSelf = Regex("^You have joined ((?:\\[[^]]*?])? ?)?(\\w{1,16})'s? party!$")
    private val joinedOther = Regex("^((?:\\[[^]]*?])? ?)?(\\w{1,16}) joined the party\\.$")
    private val leftParty = Regex("^((?:\\[[^]]*?])? ?)?(\\w{1,16}) has left the party\\.$")
    private val kickedParty = Regex("^((?:\\[[^]]*?])? ?)?(\\w{1,16}) has been removed from the party\\.$")
    private val kickedOffline = Regex("^Kicked ((?:\\[[^]]*?])? ?)?(\\w{1,16}) because they were offline\\.$")
    private val kickedDisconnected = Regex("^((?:\\[[^]]*?])? ?)?(\\w{1,16}) was removed from your party because they disconnected\\.$")
    private val transferLeave = Regex("^The party was transferred to ((?:\\[[^]]*?])? ?)?(\\w{1,16}) because ((?:\\[[^]]*?])? ?)?(\\w{1,16}) left$")
    private val transferBy = Regex("^The party was transferred to ((?:\\[[^]]*?])? ?)?(\\w{1,16}) by ((?:\\[[^]]*?])? ?)?(\\w{1,16})$")
    private val partyChat = Regex("^Party > ((?:\\[[^]]*?])? ?)?(\\w{1,16}): (.+)$")
    private val partyInvite = Regex("^((?:\\[[^]]*?])? ?)?(\\w{1,16}) invited ((?:\\[[^]]*?])? ?)?(\\w{1,16}) to the party! They have 60 seconds to accept.$")
    private val leaderDisconnected = Regex("^The party leader, ((?:\\[[^]]*?])? ?)?(\\w{1,16}) has disconnected, they have 5 minutes to rejoin before the party is disbanded\\.$")
    private val leaderRejoined = Regex("^The party leader ((?:\\[[^]]*?])? ?)?(\\w{1,16}) has rejoined\\.$")
    private val memberFormat = Regex("^((?:\\[[^]]*?])? ?)?(\\w{1,16})$")
    private val partyWith = Regex("^You'll be partying with: (.+)$")

    private val queuedInFinder = Regex("^Party Finder > Your party has been queued in the dungeon finder!$")
    private val dungeonJoin = Regex("^Party Finder > (\\w{1,16}) joined the dungeon group! \\((\\w+) Level (\\d+)\\)$")
    private val kuudraJoin = Regex("^Party Finder > ((?:\\[[^]]*?])? ?)?(\\w{1,16}) joined the group! \\(Combat Level (\\d+)\\)$")
    private val membersList = Regex("^Party (Leader|Moderators|Members): (.+)$")

    private val disbandPatterns = listOf(
        Regex("^((?:\\[[^]]*?])? ?)?(\\w{1,16}) has disbanded the party!$"),
        Regex("^You have been kicked from the party by ((?:\\[[^]]*?])? ?)?(\\w{1,16})$"),
        Regex("^The party was disbanded because all invites expired and the party was empty.$"),
        Regex("^The party was disbanded because the party leader disconnected.$"),
        Regex("^You left the party.$"),
        Regex("^You are not currently in a party.$")
    )

    val members = mutableListOf<String>()

    var partyLeader: String? = null
        private set

    var isInParty: Boolean = false
        private set

    init {
        on<MessageEvent.Chat> { if (parseChat(message)) requestPartyInfo() }
        on<LevelEvent.Load> { requestPartyInfo() }

        HypixelModAPI.getInstance().createHandler(ClientboundHelloPacket::class.java) { if (mc.player != null) requestPartyInfo() }
        HypixelModAPI.getInstance().createHandler(ClientboundPartyInfoPacket::class.java, ::onPartyInfo)
    }

    private fun parseChat(message: String): Boolean {
        if (!message.contains("arty") && !message.startsWith("Kicked ")) return false

        joinedOther.find(message)?.let { addMember(it.groupValues[2]); return true }

        joinedSelf.find(message)?.let {
            addMember(it.groupValues[2])
            partyLeader = it.groupValues[2]
            addMember(mc.player?.gameProfile?.name ?: return true)
            return true
        }

        leftParty.find(message)?.let { removeMember(it.groupValues[2]); return true }

        kickedParty.find(message)?.let { removeMember(it.groupValues[2]); return true }

        kickedOffline.find(message)?.let { removeMember(it.groupValues[2]); return true }

        kickedDisconnected.find(message)?.let { removeMember(it.groupValues[2]); return true }

        transferBy.find(message)?.let {
            addMember(it.groupValues[2])
            addMember(it.groupValues[4])
            partyLeader = it.groupValues[2]
            return true
        }

        transferLeave.find(message)?.let {
            addMember(it.groupValues[2])
            partyLeader = it.groupValues[2]
            removeMember(it.groupValues[4])
            return true
        }

        leaderDisconnected.find(message)?.let {
            partyLeader = it.groupValues[2]
            return true
        }

        leaderRejoined.find(message)?.let {
            partyLeader = it.groupValues[2]
            return true
        }

        partyChat.find(message)?.let {
            addMember(it.groupValues[2])
            return false
        }

        partyInvite.find(message)?.let {
            addMember(it.groupValues[2])
            if (partyLeader == null) partyLeader = it.groupValues[2]
            return false
        }

        queuedInFinder.find(message)?.let {
            addMember(mc.player?.gameProfile?.name ?: return true)
            if (partyLeader == null) partyLeader = mc.player?.gameProfile?.name
            return true
        }

        for (pattern in disbandPatterns) {
            if (pattern.containsMatchIn(message)) {
                disband()
                return true
            }
        }

        membersList.find(message)?.let { match ->
            val type = match.groupValues[1]

            match.groupValues[2].split(" ●").forEach { segment ->
                val memberMatch = memberFormat.find(segment.trim()) ?: return@forEach
                addMember(memberMatch.groupValues[2])
                if (type == "Leader") partyLeader = memberMatch.groupValues[2]

                return true
            }
        }

        partyWith.find(message)?.let { match ->
            match.groupValues[1].split(", ").forEach { playerName ->
                val memberMatch = memberFormat.find(playerName.trim()) ?: return@forEach
                addMember(memberMatch.groupValues[2])
            }
            return true
        }

        kuudraJoin.find(message)?.let { addMember(it.groupValues[2]); return true }

        dungeonJoin.find(message)?.let { addMember(it.groupValues[1]); return true }

        return false
    }

    private var lastRequest = 0L
    private var requestQueued = false

    private fun requestPartyInfo() {
        val wait = lastRequest + 5000 - System.currentTimeMillis()
        if (wait > 0) {
            if (!requestQueued) {
                requestQueued = true
                schedule((wait / 50).toInt() + 1) {
                    requestQueued = false
                    requestPartyInfo()
                }
            }
            return
        }
        if (runCatching { HypixelModAPI.getInstance().sendPacket(ServerboundPartyInfoPacket()) }.getOrDefault(false))
            lastRequest = System.currentTimeMillis()
    }

    private fun onPartyInfo(packet: ClientboundPartyInfoPacket) {
        if (!packet.isInParty) {
            if (isInParty) disband()
            return
        }
        val connection = mc.connection ?: return
        val names = packet.members.mapNotNull { connection.getPlayerInfo(it)?.profile?.name }
        // Members in another lobby have no player info; only a fully resolved reply can drop names.
        if (names.size == packet.members.size && members.retainAll(names)) PartyEvent.Leave(members).postAndCatch()
        names.forEach(::addMember)
        packet.leader.getOrNull()?.let { connection.getPlayerInfo(it)?.profile?.name }?.let { partyLeader = it }
    }

    private fun addMember(playerName: String) {
        if (!isInParty) isInParty = true
        if (playerName !in members) members.add(playerName)
    }

    private fun removeMember(playerName: String) {
        if (playerName !in members) return
        members.remove(playerName)

        PartyEvent.Leave(members).postAndCatch()
        if (members.isEmpty()) disband()
    }

    private fun disband() {
        PartyEvent.Leave(members).postAndCatch()
        members.clear()
        partyLeader = null
        isInParty = false
    }

    fun isLeader(): Boolean = partyLeader == mc.player?.gameProfile?.name
}