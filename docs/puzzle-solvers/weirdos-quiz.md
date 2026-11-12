# Three Weirdos and Quiz: what the server does, and how to build the solvers

Source: 89 Dungeon Recorder recordings (Oct 12-16 2026), Odin 0.4.0's `WeirdosSolver` / `QuizSolver` bytecode and
`assets/odin/puzzles/quiz-answers.json`. Scripts and outputs are in this folder: `weirdos.py`, `wsolver.py`, `quiz.py`,
`qcover.py`, `qwho.py`, `wtl/`, `qtl2/`, `quizchat.txt`, `wsum.txt`, `allchat/` (every system_chat line of every recording).
Times are server ticks `n` as the client counts them (ping packets, 20/s) unless they are given in seconds.
**M** = measured in the recordings, **O** = read from Odin's code, **A** = assumed or community knowledge, not verified here.

---

## 1. Three Weirdos

### Data available (thin)
- 23 recordings show the room. In 6 of them a teammate solved it: 905cde, 18603d, 568bfb, 65343a, d3bba8, 74c29a. **The recorder
  never entered a Weirdos room**: it never clicked an NPC, and no `[NPC] <weirdo>: ...` line appears in any recording. The
  only `[NPC]` lines in all 89 recordings are Mort's. So none of the statement texts below come from the data.
- At each solve the recorder was 31 to 169 blocks from the room centre.

### How it appears in packets (M unless marked)
- **Activation**: the room does nothing until the first player walks in. In the same tick (n 54365 in 65343a, for example) the
  server sends 6 armor stands, two per NPC: a name stand at y 69.0 and a `CLICK` stand at y 68.75. It also places 3 chests
  (`blk` air -> `minecraft:chest[facing=south,type=single]`, src server, always facing south whatever the room's rotation).
  About 10 ticks later the three NPC bodies spawn as `minecraft:player` entities at the stands' x/z (e.g. -58.5/-56.5/-54.5,
  69, -143.5/-142.5/-143.5). In 4 of the 5 rooms where the entry time could be told, chest placement fell on the same tick as
  the solving teammate's map marker entering the room.
- **Names**: the stands' custom name (data index 2) is null at first. It arrives in a later `set_entity_data` once you are close
  enough: 5 to 90 s after the spawn in these recordings, because the recorder came near only later. Example: `{"t":"Lino",
  "j":{"extra":[{"text":"Lino","color":"red"}]}}`, and `CLICK` on the lower stand. Names seen: Lino (twice), Luverne,
  Winona (twice), Virginia, Hugo, Madelia, Rose, Carver, Elmo. Names are random, and so is which position holds the correct NPC
  (the opened chest was the middle one in 3 rooms and a side one in 1).
- **Statements (A/O)**: you right-click an NPC and get `[NPC] <Name>: <statement>`. Odin parses this with
  `\[NPC] (.+): (.+).?`. **A**: the statement goes only to the player who clicked. That fits 0 statement lines in 6 teammate
  solves, but since the recorder was always 30+ blocks away, a short-range broadcast cannot be ruled out.
- **Solve**: in the same tick or the next one, three things arrive: `PUZZLE SOLVED! <player> wasn't fooled by <NPC>! Good
  job!` (NPC name in red), a `block_event` on the correct chest (b0=1, b1=1, the lid opening), and the two other chests set to
  air. This works as a party-wide answer and reset signal: everyone receives it, and the NPC name in it identifies the correct
  NPC. **A**: failing gives `PUZZLE FAIL! <player> ...`, which Odin's generic fail regex `^PUZZLE FAIL! (\w{1,16}) .+$`
  catches. No fail was seen.
- **Chest position (O, checked against M)**: Odin computes `BlockPos(int(x)-1, 69, int(z)-1)` from the name stand, adds room-
  relative `(1,0,0)` and converts back to world coordinates. This reproduces the placed chest in every room checked (WEST and
  SOUTH rotations): the chest is the block one step "forward" of the NPC. Note that `int()` truncates toward zero, which is
  only right because dungeon coordinates are negative. Using the 3 server-placed chest blocks directly is simpler and safer.

### Fixed vs random, and what can be known when
- Fixed: there are 3 NPCs and 3 chests, the room layout per rotation, and the statement vocabulary (O).
- Random: the names, which NPC is correct, and each NPC's line. I checked add_entity, set_entity_data and attributes in one
  room (65343a) and found nothing that tells NPCs apart before a click. Skins (player_info textures) were not compared.
- **The answer is known when the first statement is read, if it is a "truth" line. Otherwise it is known after the second
  statement.** Every line identifies its own NPC as correct or wrong without reference to the others (Odin's model): 6 "has
  the reward" patterns and 12 "wrong" patterns. Two wrong NPCs mean the third is correct, so at most 2 clicks are needed, and
  5/3 on average if the correct NPC's position is uniform.

### Timing (M, 6 teammate solves; entry time from Odin's map marker, so about ±0.5 s)
| rec | entry -> solve | chests placed -> solve |
|---|---|---|
| 905cde | 3.9 s | 3.93 s |
| 18603d | 2.2 s | 1.70 s |
| 568bfb | 1.9 s | n/a (not loaded) |
| 65343a | 3.4 s | 3.40 s |
| d3bba8 | 2.3 s | n/a |
| 74c29a | 3.7 s | n/a |
Good players need 1.7 to 3.9 s from activation to opening the chest: walking in, 1-2 clicks, reading chat, opening the chest.
The server adds no wait that can be measured. The time from click to statement could not be measured (no own clicks).

### Odin's solver (O) and where it falls short
- `onNPCMessage(npc, msg)`: if `msg` fully matches one of 6 `solutions` regexes, the chest of the ArmorStand named `npc` turns
  green (with a firework sound). If it matches one of 12 `wrong` regexes, it is added to the red set. Otherwise nothing happens.
  - solutions: `The reward is not in my chest!` · `At least one of them is lying, and the reward is not in .+'s chest.?` ·
    `My chest doesn't have the reward. We are all telling the truth.?` · `My chest has the reward and I'm telling the truth!` ·
    `The reward isn't in any of our chests.?` · `Both of them are telling the truth. Also, .+ has the reward in their chest.?`
  - wrong: `One of us is telling the truth!` · `They are both telling the truth. The reward isn't in .+'s chest.` ·
    `We are all telling the truth!` · `.+ is telling the truth and the reward is in his chest.` · `My chest doesn't have the
    reward. At least one of the others is telling the truth!` · `One of the others is lying.` · `They are both telling the
    truth, the reward is in .+'s chest.` · `They are both lying, the reward is in my chest!` · `The reward is in my chest.` ·
    `The reward is not in my chest. They are both lying.` · `.+ is telling the truth.` · `My chest has the reward.`
- Shortfalls:
  1. **No elimination.** After two red NPCs, Odin does not mark the third green.
  2. **Exact-name ArmorStand lookup**: it fails if the name stand has not received its name yet or the name has odd formatting.
     Our own `interact` packet already gives the clicked entity id, which is more robust.
  3. **Full-match regexes**, with no fallback for an unknown line (it does nothing), and no logging. The list's completeness
     cannot be checked from this data.
  4. Nothing comes from the party: it never uses the `wasn't fooled by <NPC>` line, so a teammate's solve or the correct NPC
     is never shown.
  5. Its chat hook is at Odin's level. Our ChatHider (`src/chat`, `src/resources/chat/hidden.json`) hides lines only when they
     are displayed and has no rule for weirdo `[NPC]` lines, so it does not interfere today.

### Solver options
1. **Odin-style per-statement classifier**: green or red per NPC from its own line.
2. **(1) plus elimination**: two reds make the third green.
3. **(2) plus a party signal**: on solve or fail chat, mark the named NPC's chest and clear the rest. Optionally post the
   result to party chat so teammates without the mod see it (opt-in).
4. **Content inference** (use the names inside wrong lines to point at the correct NPC): unverified. Odin's model treats each
   line as self-classifying, so this needs real data first.

**Recommended: (2) + (3).** Key the solver to the entity the player just interacted with (the `interact` packet's id, which
maps to the NPC body or stand; take the nearest name stand within 1.5 blocks). Show boxes on the 3 server-placed chest
blocks, not on computed positions. Show green the moment one truth line arrives or two wrong lines have arrived, and red on
wrong NPCs. Clear on `PUZZLE SOLVED/FAIL`, or when the two wrong chests turn to air. Classify on the packet-level
`system_chat` text after stripping formatting, before any mod can cancel it. Log every `[NPC] <name>: <line>` that matches
no pattern, so the list can be completed.

### More data needed
Recordings where the recorder clicks all three NPCs in several rooms. They would show: the full set of statements; whether
statements reach a teammate standing next to the clicker; the time from click to chat; the fail text and any penalty for
opening a wrong chest; whether a second click on the same NPC repeats its line.

---

## 2. Quiz (Oruo the Omniscient)

### Data available
- 19 recordings show the room. 11 contain a quiz run in chat, with 20 questions in total.
- 4 quizzes were completed: two by teammates (597719, 0a4f96) and **two by the recorder itself** (87ccfa, 8cbe5a). Those two
  include every button press it sent.
- The others stop after Q1 (the party left for the boss, or a ghost triggered it).
- No wrong answer was recorded.

### How it appears in packets (M)
- **Start**: a player entering the room sets it off. Everyone in the party gets the chat, wherever they are: the recorder was
  in the boss room for several of these. All lines are `system_chat`, not overlay:
  - `[STATUE] Oruo the Omniscient: I am Oruo the Omniscient. I have lived many lives. I have learned all there is to know.`
  - +40 ticks: `... Though I sit stationary in this prison that is The Catacombs, my knowledge knows no bounds.`
  - +40: `... Prove your knowledge by answering 3 questions and I shall reward you in ways that transcend time!`
  - +40: `... Answer incorrectly, and your moment of ineptitude will live on for generations.`
  - +40 (**intro -> Q1 = 159-161 ticks in all 11 runs**):
    ```
    "                                Question #1"            (gold bold; centred with spaces)
    "                         What SkyBlock year is it?"     (gold; may WRAP onto a 2nd line, e.g. "...who sells stained" / "glass?")
    ""
    "    §6 ⓐ §aYear 518"   click_event run_command "/guessanswer 42 0", hover "You can guess this answer by clicking in chat.
    "    §6 ⓑ §aYear 517"     To finalize your party's answer, you must select an option in the Chamber of the Omniscient One!"
    "    §6 ⓒ §aYear 519"   ("/guessanswer <quizId> <0|1|2>": a party guess, not an answer)
    ""
    ```
    The option text has inline legacy `§` codes. Odin sees it stripped (`"     ⓐ 23 Fairy Souls"`).
  - Correct answer: `[STATUE] Oruo the Omniscient: <player> answered Question #1 correctly!` (or `... answered the final
    question correctly!`). +40: `... 2 questions left... Then you will have proven your worth to me!` / `... One more
    question!`. +40: the next question block. After the final answer, +20: `... I bestow upon you all the power of a hundred
    years!`
  - Pressing after completion gives `You've already proven enough to me! No need to press more of my buttons!` and `You have
    already shown your vast knowledge of SkyBlock. Come back in another life to answer more questions!`. Spamming gives `I've
    had enough of you and your party fiddling with my buttons. Scram!`. Two presses in one tick gave `Enough! My buttons are
    not to be pressed with such lack of grace!` in 8cbe5a, in the same tick the final answer was accepted. No penalty was
    tied to either one for sure (the answer still counted; see the last point of "More data needed").
  - Wrong answer (O, from Odin's fail regex, not seen): `[STATUE] Oruo the Omniscient: <player> chose the wrong answer! I
    shall never forget this moment of misrememberance.`
- **Answering**: there are three answer blocks at room-relative (20,70,6)=ⓐ, (15,70,9)=ⓑ, (10,70,6)=ⓒ (O; Odin's world
  coordinates match what was pressed, e.g. 87ccfa ⓐ -20,70,-34, ⓑ -25,70,-31, ⓒ -30,70,-34). Each block carries
  `stone_button`s on several faces. A `use_item_on` on **either a button or the answer block itself** counts: 8cbe5a Q2 and Q3
  were accepted from clicks on the block face. The server rejects a press by sending the button back unpowered (the `ack`
  `powered=false`) within a tick or two.

### Timing: a fixed 3 s lockout per question, and pre-firing works (M)
- Answers accepted, in ticks after the `Question #` line: 597719: 59/61/60 · 0a4f96: 63/60/62 · 87ccfa (own): 59/61/59 ·
  8cbe5a (own): 132/60/63 · 332ee0: 242 (slow).
- Own presses settle where the gate is. 87ccfa Q3: 9 presses from +22 to +54 ticks were all ignored, with no message and no
  penalty. The press at +58 was accepted (the chat came in the next tick). 8cbe5a Q3: presses at +50, +54 and +57 were ignored,
  and the press at +62 was accepted. **The gate is about +58 ticks (about 3 s) after the question line.** Earlier presses are
  dropped silently, so standing on the answer and pressing about 5 times a second, as the recorder did, gets the answer in on
  the first tick it can count. Avoid two presses in one tick.
- **There is no answer deadline**: Q1 was answered after 12 s (332ee0), and unanswered quizzes simply waited.
- The server fixes the minimum quiz time: 160 (intro) + 3 x 60 (gates) + 2 x 80 (answer -> next question) = 500 ticks = 25 s
  from the intro to the final answer. Both fastest runs took 25.1 s (597719, 87ccfa). A solver can only remove a player's
  slowness, which was 0 to 12 s per question here.
- Odin's timer HUD (O): 220 ticks from the intro line and 100 ticks from "2 questions left"/"One more question" both equal
  "question + 60". So it counts down to when you *can* answer. Its description ("until you must answer") is misleading. It
  counts client ticks from flavour lines.

### Fixed vs random
3 random questions are drawn from a pool. Option order is random: the correct letter was a, b and c across runs, and the
same question had different distractors (Crimson Isle: 27/29/25, then 25/33/29). The texts and the timings are fixed. Every
answer is known as soon as the options arrive, 3 s before it can be pressed.

### Odin's solver (O) and how well it covers the data
- It checks every chat line. A line equal to `What SkyBlock year is it?` gives `Year ((now_s - 1560276000) / 446400 + 1)`.
  Otherwise it uses the first DB key that the line `contains`. Option lines must start with ⓐ/ⓑ/ⓒ, and an option is marked
  when it `endsWith` any of the answers. It draws a filled box one block below the answer block, plus a beacon beam.
- **Coverage: all 15 distinct questions in the 20 seen were in the DB, and exactly one option matched each time**
  (`qcover.py`). The 6 answers the recorder itself pressed (Livid, Farming Islands, lady of the Nether, Thorn, minions, Spider's
  Den) all agree with the DB.
  - Seen: not a dragon in The End; Crimson Isle, Deep Caverns, Spider's Den, Farming Islands and Jerry's Workshop fairy souls;
    status of Bonzo, Scarf, Livid and Thorn; SkyBlock year; enemy not in Spider's Den; Hub stained-glass vendor (wrapped
    question; Odin's key is the first line only, so it still matches); unique minions; lady of the Nether.
  - Not covered by any question seen: the other 19 keys.
- Shortfalls:
  1. **`endsWith` bug**: the answers `5 Fairy Souls` (Jerry's Workshop, Backwater Bayou) and `7 Fairy Souls` (Dungeon Hub)
     would also mark a distractor `15 Fairy Souls`, `25 ...`, `17 ...` and so on. Not triggered in this data: Jerry's options
     were 9/5/3.
  2. Lookup is by substring on any line, so a party chat line quoting a question can set the answers.
  3. The Year epoch should be checked (Odin uses 1560276000). The data cannot confirm which Year option was right (a
     teammate answered).
  4. The DB is static and fairy-soul counts change with updates. An unknown question shows nothing and is not logged.
  5. The timer is anchored to flavour lines and counts client ticks.
  6. Nothing shows when to press. In these recordings Odin's Quiz Solver setting was **off** (`modules`: `Quiz Solver: false`),
     so the recorder answered without it and Odin's runtime state stayed empty.

### Solver options
1. **Odin-style**: DB lookup, then a box on the answer block.
2. **(1) done properly**: parse the question block at the packet level (`Question #N`, then join the wrapped lines until the
   first ⓐ line, then the 3 options in order). Look the question up exactly, after normalising apostrophes, spaces and `§`
   codes. Compare options by equality, not suffix. Compute the Year. Log unknown questions with their options.
3. **(2) plus a gate countdown**: highlight the answer block as soon as the options arrive. Count down from the question
   packet with server ticks (gate about +58 to +60). Turn the highlight "press now" at the gate, and allow pre-firing: tell
   the player to hold or spam use on the block, at most once per tick.
4. **Auto-press**: excluded because of the Hypixel rules risk.
5. **Party help** via `/guessanswer <id> <idx>`: running a chat-click guess for the party is borderline automation. At most,
   show the letter so teammates know which block to stand at.

**Recommended: (3).**
- Show it as soon as the options arrive: a box and beam on the answer block, the letter and text in a small HUD, and a
  countdown to the gate. The colour changes at the gate.
- Hide it on `<player> answered Question #N correctly!`. Anyone in the party can answer; the line is broadcast.
- Show it in every party member's client, wherever they are, because the chat reaches the whole party.
- With an unknown question or more than one match, show "unknown". Never guess: a wrong press fails the puzzle.
- Hook chat before ChatHider or other mods cancel it. Our `hidden.json` blocks the `[STATUE] Oruo` flavour lines, but only at
  display, and the question and option lines are not `[STATUE]` lines.
- Under lag the gate is counted on the server, so anchor the countdown on `n` at the question packet, not on wall time.

### More data needed
- A wrong answer: its exact text, `PUZZLE FAIL`, and any penalty such as damage or a teleport.
- A press every tick around +55 to +62 to pin down the gate.
- Which option was right for the SkyBlock year question (confirms the epoch).
- More questions to check the other 19 DB keys and fairy-soul counts.
- Whether "Scram!" has a consequence. In 87ccfa a server teleport and 4 damage followed 1.2 s later; the player was walking out
  at the time, so the cause is unclear.
