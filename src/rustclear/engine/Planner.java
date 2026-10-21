package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import net.rustclear.mod.DungeonSettings;

/**
 * Picks a dungeon that meets the player's settings (Smalldungeons generation.rs plan_dungeon):
 * guaranteed rooms, allowed puzzles and a total secret range. Rooms are grouped by slot type and
 * shape; counting tables over secret totals let it pick uniformly among the dungeons that fit.
 */
public final class Planner {
    private static final int MAX_SECRETS = 512;

    private Planner() {
    }

    /** Thrown when no dungeon meets the settings; the message says why. */
    public static final class PlanException extends Exception {
        PlanException(String message) {
            super(message);
        }
    }

    private record Candidate(String name, int secrets, List<Integer> keys) {}

    private record Group(List<Integer> slots, List<Candidate> candidates) {}

    private record GroupTable(String key, List<Candidate> free, int pick, int forcedSecrets, double[][] ways) {}

    // ---------------------------------------------------------------- settings helpers

    /** DungeonSettings::normalized: guaranteed rooms deduplicated (ignoring case), puzzles sorted, secrets ordered. */
    public static DungeonSettings normalized(DungeonSettings in) {
        DungeonSettings s = in == null ? new DungeonSettings() : in.copy();
        List<String> seen = new ArrayList<>();
        List<String> rooms = new ArrayList<>();
        for (String r : s.rooms == null ? List.<String>of() : s.rooms) {
            String key = r == null ? "" : r.trim().toLowerCase();
            if (key.isEmpty() || seen.contains(key)) {
                continue;
            }
            seen.add(key);
            rooms.add(r);
        }
        s.rooms = rooms;
        if (s.puzzles != null) {
            List<String> puzzles = new ArrayList<>();
            for (String p : s.puzzles) {
                if (p != null && !p.trim().isEmpty() && !puzzles.contains(p)) {
                    puzzles.add(p);
                }
            }
            Collections.sort(puzzles);
            s.puzzles = puzzles;
        }
        if (s.secrets != null && s.secrets.length == 2) {
            s.secrets = new int[] {Math.min(s.secrets[0], s.secrets[1]), Math.max(s.secrets[0], s.secrets[1])};
        } else {
            s.secrets = null;
        }
        return s;
    }

    /** DungeonSettings::describe. */
    public static String describe(DungeonSettings s) {
        String rooms = s.rooms == null || s.rooms.isEmpty() ? "random" : String.join(", ", s.rooms);
        String puzzles = s.puzzles == null ? "random" : String.join(", ", s.puzzles);
        String secrets = s.secrets == null ? "random" : s.secrets[0] + "-" + s.secrets[1];
        return "rooms: " + rooms + "; puzzles: " + puzzles + "; secrets: " + secrets;
    }

    private static boolean puzzleAllowed(DungeonSettings s, String name) {
        return s.puzzles == null || s.puzzles.stream().anyMatch(p -> p.equalsIgnoreCase(name));
    }

    // ---------------------------------------------------------------- planning

    public static DungeonPlan plan(RoomStore store, DungeonSettings settingsIn, Random random) throws PlanException {
        DungeonSettings settings = normalized(settingsIn);
        if (settings.isFullyRandom()) {
            return DungeonPlan.random(store, random);
        }
        int secretMin = settings.secrets == null ? 0 : settings.secrets[0];
        int secretMax = settings.secrets == null ? MAX_SECRETS - 1 : settings.secrets[1];
        List<String> guaranteed = settings.rooms;
        List<Layout> layouts = DungeonPlan.fillableLayouts(store);

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < layouts.size(); i++) {
            order.add(i);
        }
        Collections.shuffle(order, random);

        Integer fewestPuzzles = null;
        int[] reachable = null;
        boolean anyRoomsFit = guaranteed.isEmpty();

        for (int layoutIndex : order) {
            Layout layout = layouts.get(layoutIndex);
            List<Layout.Slot> slots = layout.slots;
            int puzzleSlots = (int) slots.stream().filter(s -> s.type() == RoomType.PUZZLE).count();
            fewestPuzzles = fewestPuzzles == null ? puzzleSlots : Math.min(fewestPuzzles, puzzleSlots);

            TreeMap<String, Group> groups = buildGroups(slots, store, settings);
            if (groups == null) {
                continue;
            }
            List<Map<String, List<Candidate>>> assignments = new ArrayList<>();
            assignGuaranteed(groups, guaranteed, 0, assignments, new TreeMap<>());
            if (assignments.isEmpty()) {
                continue;
            }
            anyRoomsFit = true;
            Collections.shuffle(assignments, random);

            for (Map<String, List<Candidate>> assignment : assignments) {
                List<GroupTable> tables = new ArrayList<>();
                List<double[]> totals = buildTables(groups, assignment, tables);
                double[] last = totals.get(totals.size() - 1);

                List<Integer> inRange = new ArrayList<>();
                int low = -1;
                int high = -1;
                for (int s = 0; s < MAX_SECRETS; s++) {
                    if (last[s] > 0) {
                        if (low < 0) {
                            low = s;
                        }
                        high = s;
                        if (s >= secretMin && s <= secretMax) {
                            inRange.add(s);
                        }
                    }
                }
                if (low >= 0) {
                    reachable = reachable == null ? new int[] {low, high}
                        : new int[] {Math.min(reachable[0], low), Math.max(reachable[1], high)};
                }
                double weight = 0;
                for (int s : inRange) {
                    weight += last[s];
                }
                if (weight <= 0) {
                    continue;
                }
                double roll = random.nextDouble() * weight;
                int target = inRange.get(inRange.size() - 1);
                for (int s : inRange) {
                    if (roll < last[s]) {
                        target = s;
                        break;
                    }
                    roll -= last[s];
                }

                int[] picks = new int[slots.size()];
                Arrays.fill(picks, -1);
                int remaining = target;
                for (int n = tables.size() - 1; n >= 0; n--) {
                    GroupTable table = tables.get(n);
                    double[] before = totals.get(n);
                    int freeSecrets = Math.max(0, remaining - table.forcedSecrets());
                    List<int[]> optionB = new ArrayList<>();
                    List<Double> optionW = new ArrayList<>();
                    for (int b = 0; b <= freeSecrets; b++) {
                        int a = remaining - table.forcedSecrets() - b;
                        if (a < 0 || b >= MAX_SECRETS) {
                            continue;
                        }
                        double w = table.ways()[table.pick()][b] * before[a];
                        if (w > 0) {
                            optionB.add(new int[] {b});
                            optionW.add(w);
                        }
                    }
                    double total = 0;
                    for (double w : optionW) {
                        total += w;
                    }
                    double r = random.nextDouble() * total;
                    int groupSecrets = optionB.isEmpty() ? 0 : optionB.get(optionB.size() - 1)[0];
                    for (int i = 0; i < optionB.size(); i++) {
                        if (r < optionW.get(i)) {
                            groupSecrets = optionB.get(i)[0];
                            break;
                        }
                        r -= optionW.get(i);
                    }
                    remaining = Math.max(0, remaining - table.forcedSecrets() - groupSecrets);

                    List<Candidate> chosen = sampleSubset(table.free(), table.pick(), groupSecrets, random);
                    chosen.addAll(assignment.getOrDefault(table.key(), List.of()));
                    Collections.shuffle(chosen, random);
                    Group group = groups.get(table.key());
                    for (int i = 0; i < group.slots().size() && i < chosen.size(); i++) {
                        List<Integer> keys = chosen.get(i).keys();
                        picks[group.slots().get(i)] = keys.get(random.nextInt(keys.size()));
                    }
                }
                if (Arrays.stream(picks).anyMatch(p -> p < 0)) {
                    continue;
                }
                List<PlacedRoom> rooms = new ArrayList<>();
                for (int i = 0; i < slots.size(); i++) {
                    rooms.add(PlacedRoom.of(slots.get(i), store.rooms.get(picks[i]), layout.doors, random));
                }
                return new DungeonPlan(layout, List.copyOf(rooms));
            }
        }

        // PlanFailure::message
        if (!anyRoomsFit) {
            throw new PlanException("No dungeon can hold all of: " + String.join(", ", guaranteed) + ".");
        }
        if (settings.puzzles != null && fewestPuzzles != null && settings.puzzles.size() < fewestPuzzles) {
            throw new PlanException("Every layout has at least " + fewestPuzzles + " puzzle rooms; enable at least "
                + fewestPuzzles + " puzzles (you have " + settings.puzzles.size() + ").");
        }
        if (settings.secrets != null && reachable != null) {
            throw new PlanException("No dungeon has " + settings.secrets[0] + "-" + settings.secrets[1]
                + " secrets with these settings; the reachable range is " + reachable[0] + "-" + reachable[1] + ".");
        }
        throw new PlanException("No dungeon meets these settings.");
    }

    /** build_groups: slots by type/shape, each with the room names that may fill them. Null when a group has too few. */
    private static TreeMap<String, Group> buildGroups(List<Layout.Slot> slots, RoomStore store, DungeonSettings settings) {
        TreeMap<String, Group> groups = new TreeMap<>();
        for (int index = 0; index < slots.size(); index++) {
            Layout.Slot slot = slots.get(index);
            String key = slot.type() + "/" + slot.shape();
            Group existing = groups.get(key);
            if (existing != null) {
                existing.slots().add(index);
                continue;
            }
            TreeMap<String, Candidate> byName = new TreeMap<>();
            for (RoomData data : store.rooms) {
                if (data.type() != slot.type() || data.shape() != slot.shape()) {
                    continue;
                }
                if (slot.type() == RoomType.PUZZLE && !puzzleAllowed(settings, data.name())) {
                    continue;
                }
                byName.computeIfAbsent(data.name(), n -> new Candidate(n, data.secrets(), new ArrayList<>())).keys().add(data.index());
            }
            List<Integer> groupSlots = new ArrayList<>();
            groupSlots.add(index);
            groups.put(key, new Group(groupSlots, new ArrayList<>(byName.values())));
        }
        for (Group group : groups.values()) {
            if (group.candidates().size() < group.slots().size()) {
                return null;
            }
        }
        return groups;
    }

    /** count_ways: ways[j][s] = how many ways to choose j candidates with s secrets in total. */
    private static double[][] countWays(List<Candidate> candidates, int count, int pick) {
        double[][] ways = new double[pick + 1][MAX_SECRETS];
        ways[0][0] = 1;
        for (int c = 0; c < count; c++) {
            int secrets = candidates.get(c).secrets();
            for (int j = pick; j >= 1; j--) {
                for (int s = MAX_SECRETS - 1; s >= secrets; s--) {
                    double add = ways[j - 1][s - secrets];
                    if (add > 0) {
                        ways[j][s] += add;
                    }
                }
            }
        }
        return ways;
    }

    /** sample_subset: a uniformly random set of [pick] candidates whose secrets add up to [target]. */
    private static List<Candidate> sampleSubset(List<Candidate> candidates, int pick, int target, Random random) {
        List<double[][]> prefix = new ArrayList<>();
        for (int i = 0; i <= candidates.size(); i++) {
            prefix.add(countWays(candidates, i, pick));
        }
        List<Candidate> chosen = new ArrayList<>();
        int j = pick;
        int s = target;
        for (int i = candidates.size(); i >= 1; i--) {
            if (j == 0) {
                break;
            }
            Candidate candidate = candidates.get(i - 1);
            int secrets = candidate.secrets();
            double total = prefix.get(i)[j][s];
            double with = s >= secrets ? prefix.get(i - 1)[j - 1][s - secrets] : 0;
            if (with > 0 && random.nextDouble() * total < with) {
                chosen.add(candidate);
                j--;
                s -= secrets;
            }
        }
        return chosen;
    }

    /** assign_guaranteed: every way (up to 16) to put the guaranteed rooms in groups that can hold them. */
    private static void assignGuaranteed(TreeMap<String, Group> groups, List<String> names, int next,
                                         List<Map<String, List<Candidate>>> out, TreeMap<String, List<Candidate>> current) {
        if (out.size() >= 16) {
            return;
        }
        if (next == names.size()) {
            Map<String, List<Candidate>> copy = new HashMap<>();
            current.forEach((k, v) -> copy.put(k, new ArrayList<>(v)));
            out.add(copy);
            return;
        }
        String name = names.get(next);
        for (Map.Entry<String, Group> entry : groups.entrySet()) {
            Candidate candidate = entry.getValue().candidates().stream()
                .filter(c -> c.name().equalsIgnoreCase(name)).findFirst().orElse(null);
            if (candidate == null) {
                continue;
            }
            List<Candidate> used = current.computeIfAbsent(entry.getKey(), k -> new ArrayList<>());
            if (used.size() >= entry.getValue().slots().size()) {
                if (used.isEmpty()) {
                    current.remove(entry.getKey());
                }
                continue;
            }
            used.add(candidate);
            assignGuaranteed(groups, names, next + 1, out, current);
            used.remove(used.size() - 1);
            if (used.isEmpty()) {
                current.remove(entry.getKey());
            }
        }
    }

    /** build_tables: per group the ways to fill its free slots, and running totals over all groups. */
    private static List<double[]> buildTables(TreeMap<String, Group> groups, Map<String, List<Candidate>> assignment,
                                              List<GroupTable> out) {
        List<double[]> totals = new ArrayList<>();
        double[] first = new double[MAX_SECRETS];
        first[0] = 1;
        totals.add(first);
        for (Map.Entry<String, Group> entry : groups.entrySet()) {
            Group group = entry.getValue();
            List<Candidate> forced = assignment.getOrDefault(entry.getKey(), List.of());
            int forcedSecrets = forced.stream().mapToInt(Candidate::secrets).sum();
            List<Candidate> free = group.candidates().stream()
                .filter(c -> forced.stream().noneMatch(f -> f.name().equals(c.name())))
                .toList();
            int pick = group.slots().size() - forced.size();
            double[][] ways = countWays(free, free.size(), pick);
            double[] previous = totals.get(totals.size() - 1);
            double[] next = new double[MAX_SECRETS];
            for (int a = 0; a < MAX_SECRETS; a++) {
                if (previous[a] == 0) {
                    continue;
                }
                for (int b = 0; b < MAX_SECRETS; b++) {
                    if (ways[pick][b] == 0) {
                        continue;
                    }
                    int s = a + b + forcedSecrets;
                    if (s < MAX_SECRETS) {
                        next[s] += previous[a] * ways[pick][b];
                    }
                }
            }
            totals.add(next);
            out.add(new GroupTable(entry.getKey(), free, pick, forcedSecrets, ways));
        }
        return totals;
    }

    private static float[] histogram;

    /** secret_distribution: how common each secret total is across random dungeons, scaled so the peak is 1. */
    public static synchronized float[] secretHistogram(RoomStore store) {
        if (histogram != null) {
            return histogram;
        }
        DungeonSettings random = new DungeonSettings();
        double[] sum = new double[MAX_SECRETS];
        for (Layout layout : store.layouts) {
            TreeMap<String, Group> groups = buildGroups(layout.slots, store, random);
            if (groups == null) {
                continue;
            }
            List<double[]> totals = buildTables(groups, Map.of(), new ArrayList<>());
            double[] last = totals.get(totals.size() - 1);
            double weight = 0;
            for (double w : last) {
                weight += w;
            }
            if (weight > 0) {
                for (int s = 0; s < MAX_SECRETS; s++) {
                    sum[s] += last[s] / weight;
                }
            }
        }
        int end = 0;
        double peak = 0;
        for (int s = 0; s < MAX_SECRETS; s++) {
            if (sum[s] > 0) {
                end = s + 1;
            }
            peak = Math.max(peak, sum[s]);
        }
        float[] out = new float[end];
        for (int s = 0; s < end; s++) {
            out[s] = peak > 0 ? (float) (sum[s] / peak) : 0f;
        }
        histogram = out;
        return out;
    }
}
