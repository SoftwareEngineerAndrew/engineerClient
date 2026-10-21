package net.rustclear.mod.engine;

/**
 * One recorded room (Smalldungeons room_data.rs RoomData). [blocks] are 1.8 block ids
 * (id << 4 | meta), x fastest, then z, then y from [bottom] up.
 */
public record RoomData(
    int index, String name, String id, RoomShape shape, RoomType type,
    int bottom, int width, int length, int height, int secrets, String crushersJson, short[] blocks
) {
    /**
     * How many block layers the room has: [height] when the data has it, else what its blocks fill (44 of the 135
     * rooms carry height 0; the builder lays blocks until they run out, so they were built fine, but the planners'
     * room bounds came out empty and every walk in them found no floor).
     */
    public int levels() {
        if (height > 0) {
            return height;
        }
        int layer = Math.max(1, width * length);
        return (blocks.length + layer - 1) / layer;
    }

    public int blockAt(int x, int y, int z) {
        return blocks[((y - bottom) * length + z) * width + x] & 0xFFFF;
    }
}
