package net.rustclear.mod.engine.blood;

/**
 * One mob the Watcher releases (blood-watcher.md §4), as the run's mob system spawns it: a player
 * NPC (the normals and the revived bosses, each in its own recorded skin), a giant, or a
 * Parasite's silverfish. [label] is the tag before the health ("§6Golden Flamer"), [killer] the
 * name a death line uses ("Golden Flamer").
 */
public record CampMob(Type type, String label, String killer, double health, String skin, String signature,
                      String mainHand, String feet, boolean invisible, double speedFactor, int level) {

    public enum Type {
        /** A normal (one of the 17 names) or the Wandering Soul. */
        NORMAL,
        /** Bonzo, Scarf, Livid or Spirit Bear. */
        BOSS,
        GIANT,
        /** A Parasite's silverfish: tagged "[Lv79] Parasite 33,333/33,333❤", never counted. */
        SILVERFISH,
        /** P1's wither-skeleton adds. */
        WITHER_MINER,
        WITHER_GUARD
    }
}
