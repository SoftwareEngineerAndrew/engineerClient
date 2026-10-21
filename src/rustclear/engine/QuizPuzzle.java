package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * Quiz (Smalldungeons dungeon/room/quiz.rs): Oruo the Omniscient asks three trivia questions, each
 * answered by pressing a button on one of three pedestals. A wrong answer fails the room.
 */
final class QuizPuzzle extends Puzzle {
    private static final String[] INTRO_LINES = {
        "I am Oruo the Omniscient. I have lived many lives. I have learned all there is to know.",
        "Though I sit stationary in this prison that is The Catacombs, my knowledge knows no bounds.",
        "Prove your knowledge by answering 3 questions and I shall reward you in ways that transcend time!",
        "Answer incorrectly, and your moment of ineptitude will live on for generations.",
    };
    private static final int STAGE_GAP_TICKS = 40;
    private static final int COMPLETION_GAP_TICKS = 20;
    private static final int CORRECT_CHIME_DELAY_TICKS = 4;
    private static final int FIRST_OPTION_DELAY_TICKS = 40;
    private static final int OPTION_REVEAL_GAP_TICKS = 10;
    private static final int LINE_WIDTH = 75;
    private static final String[] LETTERS = {"ⓐ", "ⓑ", "ⓒ"};
    /** HOLOGRAM_Y_OFFSET (1.3) for a small stand; ours is full size, so it stands 0.9875 lower. */
    private static final double HOLOGRAM_Y = 1.3 - 0.9875;
    /** A standing armour stand's height: a marker's name floats this much lower, so it is put this much higher. */
    private static final double STAND_HEIGHT = 1.975;

    /** PEDESTALS: the three answers' pedestal centres, left to right. */
    private static final int[][] PEDESTALS = {{20, 70, 6}, {15, 70, 9}, {10, 70, 6}};

    private static final String[][] FAIRY_SOULS = {
        {"How many total Fairy Souls are there?", "289 Fairy Souls"},
        {"How many Fairy Souls are there in the Hub?", "80 Fairy Souls"},
        {"How many Fairy Souls are there in Backwater Bayou?", "5 Fairy Souls"},
        {"How many Fairy Souls are there in Spider's Den?", "19 Fairy Souls"},
        {"How many Fairy Souls are there in The End?", "12 Fairy Souls"},
        {"How many Fairy Souls are there in The Farming Islands?", "20 Fairy Souls"},
        {"How many Fairy Souls are there in Crimson Isle?", "29 Fairy Souls"},
        {"How many Fairy Souls are there in The Park?", "12 Fairy Souls"},
        {"How many Fairy Souls are there in Deep Caverns?", "21 Fairy Souls"},
        {"How many Fairy Souls are there in Jerry's Workshop?", "5 Fairy Souls"},
        {"How many Fairy Souls are there in Dungeon Hub?", "7 Fairy Souls"},
        {"How many Fairy Souls are there in Gold Mine?", "12 Fairy Souls"},
    };
    private static final String[][] STATUS = {
        {"What is the status of Scarf?", "Apprentice Necromancer"},
        {"What is the status of Bonzo?", "New Necromancer"},
        {"What is the status of Thorn?", "Shaman Necromancer"},
        {"What is the status of The Watcher?", "Stalker"},
        {"What is the status of Sadan?", "Necromancer Lord"},
        {"What is the status of Livid?", "Master Necromancer"},
        {"What is the status of The Professor?", "Professor"},
        {"What is the status of Maxor, Storm, Goldor, and Necron?", "The Wither Lords"},
    };
    private static final String[][] MISC = {
        {"What is the name of the lady of the Nether?", "Elle"},
        {"Which brother is on the Spider's Den?", "Rick"},
        {"How many unique minions are there?", "61 Minions"},
        {"Which villager in the Village gives you a Rogue Sword?", "Jamie"},
        {"What is the name of Rick's brother?", "Pat"},
        {"What is the name of the vendor in the Hub who sells stained clay?", "Wool Weaver"},
        {"What is the name of the person that upgrades pets?", "Kat"},
    };
    private static final String SPIDER_DEN_CORRECT = "Wither Skeleton";
    private static final String[] SPIDER_DEN_WRONG = {"Zombie Spider", "Cave Spider", "Dashing Spooder", "Broodfather", "Night Spider"};
    private static final String[] END_DRAGON_REAL = {
        "Protector Dragon", "Wise Dragon", "Unstable Dragon", "Superior Dragon", "Strong Dragon", "Young Dragon", "Old Dragon",
    };
    private static final String[] END_DRAGON_FAKE = {
        "Zoomer Dragon", "Weak Dragon", "Stonk Dragon", "Boomer Dragon", "Booger Dragon", "Professor Dragon",
    };
    private static final long SKYBLOCK_EPOCH_SECS = 1560276000L;
    private static final long SKYBLOCK_YEAR_SECS = 446400L;

    /** QuestionInstance: the options already shuffled, [correct] pointing at the answer. */
    private record Question(String text, String[] options, int correct) {}

    private final BlockPos[] pedestals = new BlockPos[3];
    /** Each world button position's answer index. */
    private final Map<BlockPos, Integer> buttons = new HashMap<>();
    private final Question[] questions = new Question[3];
    private final ArmorStand[] holograms = new ArmorStand[3];
    private int current;
    private boolean started;
    private boolean awaitingAnswer;

    QuizPuzzle(DungeonRun run, RoomState room) {
        super(run, room);
        for (int i = 0; i < 3; i++) {
            int[] p = PEDESTALS[i];
            pedestals[i] = world(p[0], p[1], p[2]);
            // button_offsets: one button on each side of the pedestal.
            buttons.put(world(p[0], p[1], p[2] - 1), i);
            buttons.put(world(p[0] + 1, p[1], p[2]), i);
            buttons.put(world(p[0], p[1], p[2] + 1), i);
            buttons.put(world(p[0] - 1, p[1], p[2]), i);
        }
    }

    /** Dungeon::tick's Quiz checks: the start once a player is past the entry row, and the fall rescue. */
    @Override
    void tick() {
        for (ServerPlayer player : playersInRoom()) {
            if (!started && local(player.blockPosition())[2] >= 0) {
                start();
            }
            if (player.getY() < 63.0) {
                BlockPos rescue = world(15, 69, 4);
                player.teleportTo(level, rescue.getX() + 0.5, rescue.getY(), rescue.getZ() + 0.5, Relative.ROTATION, 0f, 0f, false);
                player.resetFallDistance();
            }
        }
    }

    /** setup: picks three questions and starts Oruo's intro. */
    private void start() {
        started = true;
        List<int[]> kinds = new ArrayList<>();
        for (int i = 0; i < FAIRY_SOULS.length; i++) {
            kinds.add(new int[] {0, i});
        }
        for (int i = 0; i < STATUS.length; i++) {
            kinds.add(new int[] {1, i});
        }
        for (int i = 0; i < MISC.length; i++) {
            kinds.add(new int[] {2, i});
        }
        for (int special = 3; special <= 6; special++) {
            kinds.add(new int[] {special, 0});
        }
        Collections.shuffle(kinds, random);
        for (int i = 0; i < 3; i++) {
            questions[i] = build(kinds.get(i)[0], kinds.get(i)[1]);
        }
        say(INTRO_LINES[0]);
        scheduleIntroLine(1);
    }

    /** build_question. */
    private Question build(int kind, int index) {
        return switch (kind) {
            case 0 -> fromPool(FAIRY_SOULS, index);
            case 1 -> fromPool(STATUS, index);
            case 2 -> fromPool(MISC, index);
            case 3 -> {
                List<String> wrong = shuffled(SPIDER_DEN_WRONG);
                yield shuffledQuestion("Which of these enemies does not spawn in the Spider's Den?", SPIDER_DEN_CORRECT, wrong.get(0), wrong.get(1));
            }
            case 4 -> {
                String fake = END_DRAGON_FAKE[random.nextInt(END_DRAGON_FAKE.length)];
                List<String> reals = shuffled(END_DRAGON_REAL);
                yield shuffledQuestion("Which of these is not a dragon in The End?", fake, reals.get(0), reals.get(1));
            }
            case 5 -> shuffledQuestion("Which of these monsters only spawns at night?", "Zombie Villager", "Ghast", "Silverfish");
            default -> {
                long year = (System.currentTimeMillis() / 1000 - SKYBLOCK_EPOCH_SECS) / SKYBLOCK_YEAR_SECS + 1;
                yield shuffledQuestion("What SkyBlock year is it?", "Year " + year, "Year " + (year - 1), "Year " + (year + 1));
            }
        };
    }

    /** other_two: two wrong answers from the same pool, never the same text as the answer or each other. */
    private Question fromPool(String[][] pool, int index) {
        Set<String> seen = new LinkedHashSet<>();
        seen.add(pool[index][1]);
        List<String> others = new ArrayList<>();
        for (int i = 0; i < pool.length; i++) {
            if (i != index && seen.add(pool[i][1])) {
                others.add(pool[i][1]);
            }
        }
        Collections.shuffle(others, random);
        return shuffledQuestion(pool[index][0], pool[index][1], others.get(0), others.get(1));
    }

    /** shuffled_instance. */
    private Question shuffledQuestion(String text, String correct, String wrongA, String wrongB) {
        List<String> options = new ArrayList<>(List.of(correct, wrongA, wrongB));
        Collections.shuffle(options, random);
        return new Question(text, options.toArray(new String[0]), options.indexOf(correct));
    }

    private List<String> shuffled(String[] values) {
        List<String> list = new ArrayList<>(List.of(values));
        Collections.shuffle(list, random);
        return list;
    }

    /** say: a line from the statue to everyone, with colorize_body's highlights. */
    private void say(String line) {
        String body = line.replace("Oruo the Omniscient", "§4Oruo the Omniscient§f").replace("The Catacombs", "§cThe Catacombs§f");
        broadcast("§4[STATUE] Oruo the Omniscient§f: " + body);
    }

    private void scheduleIntroLine(int line) {
        schedule(STAGE_GAP_TICKS, () -> {
            if (line < INTRO_LINES.length) {
                say(INTRO_LINES[line]);
                scheduleIntroLine(line + 1);
            } else {
                beginQuestion();
            }
        });
    }

    /** begin_question: the question and its options in chat, then the options appear one by one. */
    private void beginQuestion() {
        Question q = questions[current];
        broadcast("");
        broadcast(centered("Question #" + (current + 1), "§6§l"));
        broadcast(centered(q.text(), "§6"));
        broadcast("");
        for (int i = 0; i < 3; i++) {
            broadcast("    §6 " + LETTERS[i] + " §a" + q.options()[i]);
        }
        broadcast("");
        schedule(FIRST_OPTION_DELAY_TICKS, () -> revealOption(0));
    }

    /** center_colored. */
    private static String centered(String text, String color) {
        int pad = Math.max(0, LINE_WIDTH - text.codePointCount(0, text.length())) / 2;
        return " ".repeat(pad) + color + text;
    }

    /** reveal_answer_option: the pop and the floating answer over its pedestal. */
    private void revealOption(int index) {
        if (index == 2) {
            awaitingAnswer = true;
        }
        BlockPos pedestal = pedestals[index];
        sound(pedestal, SoundEvents.ITEM_PICKUP, 1f, 0.587f);
        ArmorStand stand = new ArmorStand(EntityTypes.ARMOR_STAND, level);
        // A marker, as Hypixel's holograms are: no box, so it never catches a click meant for the pedestal's buttons (its
        // gym). Raised by a standing stand's height so the text floats where it did.
        ((net.rustclear.mod.mixin.ArmorStandAccessor) stand).rustclear$setMarker(true);
        stand.snapTo(pedestal.getX() + 0.5, pedestal.getY() + HOLOGRAM_Y + STAND_HEIGHT, pedestal.getZ() + 0.5, 0f, 0f);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setPermanentlyInvulnerable(true);
        stand.setCustomName(EngineItems.text("§6" + LETTERS[index] + " §a" + questions[current].options()[index]));
        stand.setCustomNameVisible(true);
        holograms[index] = track(stand);
        if (index < 2) {
            schedule(OPTION_REVEAL_GAP_TICKS, () -> revealOption(index + 1));
        }
    }

    private void despawnHolograms() {
        for (int i = 0; i < 3; i++) {
            if (holograms[i] != null) {
                holograms[i].discard();
                holograms[i] = null;
            }
        }
    }

    @Override
    boolean useBlock(ServerPlayer player, BlockPos pos) {
        Integer answer = buttons.get(pos);
        if (answer != null) {
            if (started) {
                pressButton(player, pos, answer);
            }
            return true;
        }
        for (int i = 0; i < 3; i++) {
            if (pedestals[i].equals(pos) && started) {
                // interact_pedestal: answers like its buttons, after a scolding.
                say("Enough! My buttons are not to be pressed with such lack of grace!");
                pressButton(player, pos, i);
                return true;
            }
        }
        return false;
    }

    /** The answer holograms take no clicks. */
    @Override
    boolean useEntity(ServerPlayer player, Entity entity) {
        return isHologram(entity);
    }

    @Override
    boolean attackEntity(ServerPlayer player, Entity entity) {
        return isHologram(entity);
    }

    private boolean isHologram(Entity entity) {
        for (ArmorStand stand : holograms) {
            if (stand != null && stand == entity) {
                return true;
            }
        }
        return false;
    }

    /** interact_button. */
    private void pressButton(ServerPlayer player, BlockPos pos, int answer) {
        if (!awaitingAnswer) {
            return;
        }
        awaitingAnswer = false;
        boolean correct = answer == questions[current].correct();
        despawnHolograms();
        String who = player.getName().getString();
        sound(pos, SoundEvents.STONE_BUTTON_CLICK_ON, 0.3f, 0.6f);
        if (!correct) {
            // 6e38dd: "Yikes", the wrong-answer line 30 ticks later, and the quiz starts over from
            // its first line 54 after that ([failResetTicks]). No PUZZLE line.
            say("Yikes");
            schedule(30, () -> say("§a" + who + "§f chose the wrong answer! I shall never forget this moment of misrememberance."));
            room.failedBy = player.getName().getString();
            finish(true);
            return;
        }
        int number = current;
        boolean last = number == 2;
        say(last ? "§a" + who + " answered the final question correctly!"
            : "§a" + who + " answered §6Question #" + (number + 1) + " §acorrectly!");
        schedule(CORRECT_CHIME_DELAY_TICKS, () -> sound(pos, SoundEvents.PLAYER_LEVELUP, 1f, 0.746f));
        if (last) {
            schedule(COMPLETION_GAP_TICKS, () -> complete(player));
            return;
        }
        schedule(STAGE_GAP_TICKS, () -> {
            say(number == 0 ? "2 questions left... Then you will have proven your worth to me!" : "One more question!");
            schedule(STAGE_GAP_TICKS, () -> {
                current++;
                beginQuestion();
            });
        });
    }

    /** The quiz starts over 54 ticks after the wrong-answer line, which is 30 after "Yikes" (6e38dd). */
    @Override
    int failResetTicks() {
        return 30 + 54;
    }

    /** complete_quiz: no PUZZLE SOLVED banner, only Oruo's reward lines. */
    private void complete(ServerPlayer finder) {
        say("I bestow upon you all the power of a hundred years!");
        Blessings.grantTime(run, finder);
        finish(false);
    }

    @Override
    boolean isInteractable(BlockPos pos) {
        if (buttons.containsKey(pos)) {
            return true;
        }
        for (BlockPos pedestal : pedestals) {
            if (pedestal.equals(pos)) {
                return true;
            }
        }
        return false;
    }
}
