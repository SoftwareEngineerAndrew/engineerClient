package net.rustclear.mod.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * The dungeon data the jar carries (assets/rustclear/engine, written by tools/pack_engine_data.py):
 * every room, the recorded layouts and the door block patterns. Loaded once, on first use.
 *
 * rooms.bin.gz: "RCR1", u16 room count, then per room: name, id, shape, type (each a u16 length
 * and UTF-8), i32 bottom, width, length, height, u8 secrets, crushers JSON (string as before),
 * i32 block count, then that many big-endian u16 block ids. It is read whole and decoded from
 * memory: about 17 million block ids, too many to read one at a time.
 */
public final class RoomStore {
    private static final String ROOT = "/assets/rustclear/engine/";
    private static RoomStore instance;

    public final List<RoomData> rooms;
    /** Every recorded layout that parses (layouts.txt), parsed once. */
    public final List<Layout> layouts;
    /** Door patterns: 5x5x5 1.8 block ids each, x fastest, then z, then y (doors.txt lines). */
    public final List<short[]> doorPatterns;

    private RoomStore(List<RoomData> rooms, List<Layout> layouts, List<short[]> doorPatterns) {
        this.rooms = rooms;
        this.layouts = layouts;
        this.doorPatterns = doorPatterns;
    }

    public static synchronized RoomStore get() {
        if (instance == null) {
            try {
                instance = new RoomStore(readRooms(), readLayouts(), readDoors());
            } catch (IOException e) {
                throw new UncheckedIOException("Rustclear's dungeon data is missing from the jar", e);
            }
        }
        return instance;
    }

    private static InputStream open(String file) throws IOException {
        InputStream in = RoomStore.class.getResourceAsStream(ROOT + file);
        if (in == null) {
            throw new IOException("No " + ROOT + file);
        }
        return in;
    }

    private static List<RoomData> readRooms() throws IOException {
        ByteBuffer in;
        try (InputStream gz = new GZIPInputStream(open("rooms.bin.gz"), 1 << 16)) {
            in = ByteBuffer.wrap(gz.readAllBytes());
        }
        byte[] magic = new byte[4];
        in.get(magic);
        if (!"RCR1".equals(new String(magic, StandardCharsets.US_ASCII))) {
            throw new IOException("rooms.bin.gz is not RCR1 data");
        }
        int count = Short.toUnsignedInt(in.getShort());
        List<RoomData> rooms = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = utf(in);
            String id = utf(in);
            RoomShape shape = RoomShape.fromCode(utf(in));
            RoomType type = RoomType.fromCode(utf(in));
            int bottom = in.getInt();
            int width = in.getInt();
            int length = in.getInt();
            int height = in.getInt();
            int secrets = Byte.toUnsignedInt(in.get());
            String crushers = utf(in);
            short[] blocks = new short[in.getInt()];
            in.asShortBuffer().get(blocks);
            in.position(in.position() + blocks.length * 2);
            rooms.add(new RoomData(i, name, id, shape, type, bottom, width, length, height, secrets, crushers, blocks));
        }
        return List.copyOf(rooms);
    }

    /** A u16 length, then that many bytes of UTF-8 (pack_engine_data.py utf). */
    private static String utf(ByteBuffer in) {
        int length = Short.toUnsignedInt(in.getShort());
        String s = new String(in.array(), in.arrayOffset() + in.position(), length, StandardCharsets.UTF_8);
        in.position(in.position() + length);
        return s;
    }

    private static List<Layout> readLayouts() throws IOException {
        List<Layout> layouts = new ArrayList<>();
        for (String line : readLines("layouts.txt")) {
            try {
                layouts.add(Layout.parse(line));
            } catch (RuntimeException e) {
                // A layout that doesn't parse is never played (as before, when each use parsed it).
            }
        }
        return List.copyOf(layouts);
    }

    private static List<String> readLines(String file) throws IOException {
        try (InputStream in = open(file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        }
    }

    private static List<short[]> readDoors() throws IOException {
        List<short[]> doors = new ArrayList<>();
        for (String line : readLines("doors.txt")) {
            short[] blocks = new short[line.length() / 4];
            for (int i = 0; i < blocks.length; i++) {
                blocks[i] = (short) Integer.parseInt(line, i * 4, i * 4 + 4, 16);
            }
            doors.add(blocks);
        }
        return List.copyOf(doors);
    }
}
