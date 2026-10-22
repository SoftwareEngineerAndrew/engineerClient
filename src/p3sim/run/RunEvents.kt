package com.engineerclient.p3sim.run

import java.util.UUID

/*
 * The run event bus's vocabulary. Every event is a small immutable value; [RunEvent.t] is the run clock when it was
 * posted (`Run.t`: server ticks since T0, negative during the countdown). Players and mobs are named by UUID, rooms and
 * doors by their index in the DungeonPlan, so events are pure data and can be unit-tested and logged.
 *
 * Adding an event type is a new subclass here (additive, an integration request). Module-private notices that nobody else
 * needs use [ModuleEvent] instead. Subscribers match with `when (e)`; an unknown subclass must be ignored, never an error.
 */
sealed interface RunEvent {
    val t: Int
}

// ---- lifecycle ----------------------------------------------------------------------------------------------------

/** The run exists, the dungeon is built, the countdown is about to begin. */
data class RunStarted(override val t: Int) : RunEvent

/** The stage machine moved (forward, or back by a savepoint restore). Systems also get `onStage`. */
data class StageChanged(override val t: Int, val from: RunStage?, val to: RunStage) : RunEvent

enum class EndReason { COMPLETED, STOPPED, FAILED }

/** The run is over. Systems also get `onRunEnd`. */
data class RunEnded(override val t: Int, val reason: EndReason) : RunEvent

data class SavepointTaken(override val t: Int, val savepointId: String) : RunEvent

/** Posted AFTER every module has restored its state from the savepoint. */
data class SavepointRestored(override val t: Int, val savepointId: String) : RunEvent

data class PlayerJoined(override val t: Int, val player: UUID) : RunEvent
data class PlayerLeft(override val t: Int, val player: UUID) : RunEvent

// ---- rooms --------------------------------------------------------------------------------------------------------

/** A room left UNDISCOVERED (it appears on the map). */
data class RoomDiscovered(override val t: Int, val room: Int) : RunEvent

/** A player stepped into a room (by position, tile-wise). Posted for bots too. */
data class RoomEntered(override val t: Int, val room: Int, val player: UUID) : RunEvent

/** The room turned WHITE: its required objective is done. */
data class RoomCleared(override val t: Int, val room: Int) : RunEvent

/** The room turned GREEN: everything done, all secrets found. */
data class RoomGreen(override val t: Int, val room: Int) : RunEvent

/** The room turned RED: a puzzle failed, or the blood room during the Watcher fight. */
data class RoomFailed(override val t: Int, val room: Int) : RunEvent

// ---- doors and keys -----------------------------------------------------------------------------------------------

enum class KeyKind { WITHER, BLOOD }

/** A door started to fall (the first block became barrier). [by] is null for the doors that open by themselves. */
data class DoorOpened(override val t: Int, val door: Int, val kind: com.engineerclient.p3sim.pack.layouts.DoorKind, val by: UUID?) : RunEvent

/** All 36 blocks of the door are air now (14 ticks after [DoorOpened]). */
data class DoorFell(override val t: Int, val door: Int) : RunEvent

/** A player tried a closed wither or blood door without the key. */
data class DoorLocked(override val t: Int, val door: Int, val player: UUID) : RunEvent

/** A key stand appeared where a room's last required mob died. */
data class KeyDropped(override val t: Int, val kind: KeyKind, val room: Int, val x: Double, val y: Double, val z: Double) : RunEvent

/** A player picked a key up (the party count goes up). */
data class KeyPicked(override val t: Int, val kind: KeyKind, val player: UUID) : RunEvent

// ---- secrets, crypts, bonus ----------------------------------------------------------------------------------------

enum class SecretKind { CHEST, ITEM, BAT, ESSENCE, REDSTONE_KEY, LOCKED_CHEST }

/** A secret was found (the team count went up). [x],[y],[z] is where it was. */
data class SecretFound(override val t: Int, val room: Int, val kind: SecretKind, val player: UUID?, val x: Int, val y: Int, val z: Int) : RunEvent

/** A crypt tomb was destroyed (the crypt counter went up). */
data class CryptDestroyed(override val t: Int, val room: Int, val player: UUID?) : RunEvent

enum class BonusKind { MIMIC, PRINCE, BAT }

/** A bonus mob died and its score point counts. */
data class BonusScored(override val t: Int, val kind: BonusKind, val player: UUID?) : RunEvent

/** A blessing was granted (`Power`, `Life`, `Wisdom`, `Stone`, `Time`). */
data class BlessingGranted(override val t: Int, val blessing: String, val level: Int, val player: UUID?) : RunEvent

// ---- mobs ---------------------------------------------------------------------------------------------------------

/** What the event bus needs to know about a mob without holding an entity. */
data class MobRef(
    val id: UUID,
    /** The archetype id from the mob pack (`crypt_dreadlord`), or a module's own id for specials (`watcher`, `mimic`). */
    val kind: String,
    val room: Int?,
    val starred: Boolean,
    /** Must die for the room to turn WHITE. */
    val required: Boolean,
)

data class MobSpawned(override val t: Int, val mob: MobRef) : RunEvent

/** A mob took damage and is still alive. [remaining] is HP left; [by] the player or bot, null for the world. */
data class MobDamaged(override val t: Int, val mob: MobRef, val amount: Double, val remaining: Double, val by: UUID?) : RunEvent

/** A mob died (HP reached 0). The mobs module removes it on the same tick. */
data class MobKilled(override val t: Int, val mob: MobRef, val by: UUID?) : RunEvent

// ---- players ------------------------------------------------------------------------------------------------------

/** Where damage came from. */
enum class DamageSource {
    MELEE, PROJECTILE, TRAP, IMPLOSION, BOW, MAGE_BEAM, FALL, OTHER,

    /** A crusher's arm: the death line reads "was crushed". */
    CRUSH,

    /** A teammate that dropped out of the run: the death line reads "disconnected and became a ghost" (bots only). */
    DISCONNECT,
}

/** A party member lost [percent] of max HP (true damage when [trueDamage]) and has [hpAfter] percent left. */
data class PlayerDamaged(override val t: Int, val player: UUID, val percent: Double, val hpAfter: Double, val source: DamageSource, val attacker: UUID?, val trueDamage: Boolean) : RunEvent

/** A party member died and became a ghost. */
data class PlayerDied(override val t: Int, val player: UUID, val source: DamageSource, val attacker: UUID?) : RunEvent

/** A ghost was revived ([by] null for a Revive Stone or the 6-second timer). */
data class PlayerRevived(override val t: Int, val player: UUID, val by: UUID?) : RunEvent

// ---- puzzles ------------------------------------------------------------------------------------------------------

data class PuzzleSolved(override val t: Int, val puzzle: Int, val room: Int, val player: UUID?) : RunEvent
data class PuzzleFailed(override val t: Int, val puzzle: Int, val room: Int, val player: UUID?, val reason: String?) : RunEvent

// ---- blood and the boss seam --------------------------------------------------------------------------------------

/** The blood door opened (the Watcher stage begins). */
data class BloodDoorOpened(override val t: Int) : RunEvent

/** The Watcher is done ("You may pass."): the blood room turns GREEN, the party may enter the boss. */
data class WatcherDone(override val t: Int) : RunEvent

/** The one-tick boss entry happened this tick (lightning, bar, party at P1). */
data class BossEntry(override val t: Int) : RunEvent

// ---- score --------------------------------------------------------------------------------------------------------

/** The team score was recomputed (every 200 ticks). */
data class ScoreUpdated(override val t: Int, val score: Int, val clearedPct: Int) : RunEvent

// ---- escape hatch -------------------------------------------------------------------------------------------------

/** A notice only one module family cares about. [source] is the module id, [name] the notice, [data] free-form. */
data class ModuleEvent(override val t: Int, val source: String, val name: String, val data: Map<String, Any?> = emptyMap()) : RunEvent
