package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/** A layout with a room picked for every slot. */
public record DungeonPlan(Layout layout, List<PlacedRoom> rooms) {

    /** Picks a fillable layout at random, then a room for each slot (Dungeon::from_str). */
    public static DungeonPlan random(RoomStore store, Random random) {
        List<Layout> layouts = fillableLayouts(store);
        return fill(layouts.get(random.nextInt(layouts.size())), store, random);
    }

    /** room_data.rs get_random_data_with_type for every slot in order: no room twice, and no two puzzles of one name. */
    public static DungeonPlan fill(Layout layout, RoomStore store, Random random) {
        List<PlacedRoom> rooms = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        Set<String> puzzles = new HashSet<>();
        for (Layout.Slot slot : layout.slots) {
            List<RoomData> candidates = store.rooms.stream()
                .filter(r -> r.type() == slot.type() && r.shape() == slot.shape())
                .filter(r -> !used.contains(r.index()))
                .filter(r -> slot.type() != RoomType.PUZZLE || !puzzles.contains(r.name()))
                .toList();
            if (candidates.isEmpty()) {
                throw new IllegalStateException("No room left for a " + slot.type() + " " + slot.shape() + " slot");
            }
            RoomData data = candidates.get(random.nextInt(candidates.size()));
            used.add(data.index());
            puzzles.add(data.name());
            rooms.add(PlacedRoom.of(slot, data, layout.doors, random));
        }
        return new DungeonPlan(layout, List.copyOf(rooms));
    }

    private static List<Layout> fillable;

    /**
     * The layouts every slot of which can get a different room. 65 recorded layouts have shapes
     * no room fills (generation.rs fillable_layouts); random runs never use them.
     */
    public static synchronized List<Layout> fillableLayouts(RoomStore store) {
        if (fillable == null) {
            Map<String, Long> rooms = store.rooms.stream()
                .collect(Collectors.groupingBy(r -> r.type() + "/" + r.shape(), Collectors.counting()));
            Map<String, Long> puzzleNames = Map.of(RoomType.PUZZLE + "/" + RoomShape.ONE_BY_ONE_END,
                store.rooms.stream().filter(r -> r.type() == RoomType.PUZZLE).map(RoomData::name).distinct().count());
            fillable = store.layouts.stream().filter(layout -> {
                Map<String, Long> needed = layout.slots.stream()
                    .collect(Collectors.groupingBy(s -> s.type() + "/" + s.shape(), Collectors.counting()));
                return needed.entrySet().stream().allMatch(e ->
                    e.getValue() <= Math.min(rooms.getOrDefault(e.getKey(), 0L), puzzleNames.getOrDefault(e.getKey(), Long.MAX_VALUE)));
            }).toList();
        }
        return fillable;
    }
}
