
import data  from "../data"
const Data = data.subSplits
const S32PacketConfirmTransaction = Java.type("net.minecraft.network.play.server.S32PacketConfirmTransaction");

register("command", (command, x, y, scale) => { 
    switch (command) {
    case undefined: ;
    case "": 
    ChatLib.chat(`\n                         &cEngineer&bSubSplits\n`);

    new TextComponent(`                              ${Data.toggled ? "&a" : "&c"}&nToggled ${Data.toggled ? "on" : "off"}`)
    .setHover("show_text", `/Splits toggle`).setClick("run_command", `/subsplits toggle`).chat()

    ChatLib.chat(`
&3 /SubSplits&b move &bx&7: &8(&7${Data.x}&8) &by&7: &8(&7${Data.y}&8) &bScale&7: &8(&7${Data.scale}&8)&b:&9 Changes position the SubSplits display
&3 /SubSplits&b show:&9 Shows SubSplits for 2s
&3 /SubSplits&b start:&9 Starts SubSplits
&3 /SubSplits&b next:&9 Starts the next SubSplit
&3 /SubSplits&b ${Data.onWorldLoad ? "&a" : "&c"}worldload&b:&9 Hides splits on worldload
`);
        break;

        case "toggle":
            Data.toggled = !Data.toggled;
            data.save()
            mainToggle(Data.toggled)
            ChatLib.chat(` &3SubSplits &9toggled ${Data.toggled ? "&aon" : "&coff"}`);
            break;

        case "worldload":
            Data.onWorldLoad = !Data.onWorldLoad;
            data.save()
            ChatLib.chat(` &3SubSplits &9WorldLoad ${Data.onWorldLoad ? "&aon" : "&coff"}`);
            break;
    
        case "move":
            if (!x) return ChatLib.chat(` &3SubSplits &9is currently at &bx&7: &8(&7${Data.x}&8) &by&7: &8(&7${Data.y}&8) &bScale&7: &8(&7${Data.scale}&8)`)
                if (x) Data.x = x
                if (y) Data.y = y
                if (scale) Data.scale = scale
                data.save()
                ChatLib.chat(` &3SubSplits &9moved to &bx&7: &8(&7${Data.x}&8) &by&7: &8(&7${Data.y}&8) &bScale&7: &8(&7${Data.scale}&8)`)
            updateTestTitle()
            break;
    
        case "show":
            const time = x ? (x*1000) : null;
            updateTestTitle(time)
            break;
    
        case "start":
            resetSplits();
            newSubSplit()
            break;
    
        case "next":
            if (subSplit > 25) return;
            newSubSplit();
            break;

    default:
        ChatLib.chat(`&cCouldnt find /SubSplits "${command}"`)
}}).setName("subsplits")

register("worldLoad", () => {
    subSubSplits.forEach((name, i) =>{subSubSplits[i] = 0})
    subSplit = 0
    if (Data.onWorldLoad){
        overlay.unregister()
    }
    toggle2.unregister()
    toggle3.unregister()
    if (Data.onWorldLoad) {
        resetSplits()
        title = ` `
    }
})

const overlay = register("renderOverlay", () => {
    text = new Text(title, Data.x, Data.y)
    .setShadow(true).setScale(parseFloat(Data.scale))
    text.draw()
}).unregister()

let title = ` `



const Splits = {};
    ["Move", "Stun", "Dps", "Stun", "Dps", "Animation", 
    "Animation", "Crush", "Dps", "Crush", "Dps", "Animation", 
    "S1", "S2", "S3", "S4", 
    "Leaps", "Kill", "Animation", 
    "Mid", "Dps", "Dps", "Mid", "Dps", "Animation"]
    .forEach((name, i) => {Splits[i + 1] = {name, color: 
    ['&6', '&5', '&c', '&5', '&c', '&d', '&a', '&6', '&c', '&6', '&c', '&a', '&6', '&6', '&6', '&6', '&5', '&c', '&d', '&a', '&c', '&c', '&a', '&c', '&d'][i],
    display: ` `, serverStart: null, clientStart: null,};
});

let subSplit = 0

const updateSplits = register("step", () => {
    if (subSplit > 25 || subSplit == 0) return
    stormDisplay = subSplit > 6 ? `&b&lStorm` : ` `;
    termsDisplay = subSplit > 12 ? `&6&lTerminals` : ` `;
    goldorDisplay = subSplit > 16 ? `&e&lGoldor` : ` `;
    necronDisplay = subSplit > 18 ? `&c&lNecron` : ` `;

    const p = Splits[subSplit]
    const clientTime = Math.abs((p.clientStart - Date.now()) / 1000).toFixed(2);
    const serverTime = (serverTicks / 20).toFixed(2);
    p.display = `${p.color}${p.name} &b> ${p.color}${clientTime}s &8(&7${serverTime}s&8)`;
    
    title = 
`&a&lMaxor
${Splits[1].display}
${Splits[2].display}
${Splits[3].display}
${Splits[4].display}
${Splits[5].display}
${Splits[6].display}

${stormDisplay}
${Splits[7].display}
${Splits[8].display}
${Splits[9].display}
${Splits[10].display}
${Splits[11].display}
${Splits[12].display}

${termsDisplay}
${Splits[13].display}
${Splits[14].display}
${Splits[15].display}
${Splits[16].display}

${goldorDisplay}
${Splits[17].display}
${Splits[18].display}

${necronDisplay}
${Splits[19].display}
${Splits[20].display}
${Splits[21].display}
${Splits[22].display}
${Splits[23].display}
${Splits[24].display}
${Splits[25].display}
`}).setFps(60)

function updateTestTitle(time){
    const t = time ? time : 2000;
    updateSplits.unregister();
    title = [`&a&lMaxor
&6Move &b>&6 8.12s &8(&78.00s&8)
&5Stun &b>&5 2.28s &8(&72.25s&8)
&cDps &b>&c 11.52s &8(&711.25s&8)
&5Stun &b>&5 0.52s &8(&70.75s&8)
&cDps &b>&c 0.06s &8(&70.05s&8)
&dAnimation &b>&d 3.26s &8(&73.20s&8)

&b&lStorm
&aAnimation &b>&a 36.67s &8(&734.40s&8)
&6Crush &b>&6 0.43s &8(&70.40s&8)
&cDps &b>&c 0.15s &8(&70.15s&8)
&6Crush &b>&6 3.56s &8(&73.50s&8)
&cDps &b>&c 0.15s &8(&70.15s&8)
&aAnimation &b>&a 5.66s &8(&75.50s&8)

&6&lTerms
&6S1 &b>&6 12.56s &8(&710.55s&8)
&6S2 &b>&6 6.33s &8(&76.30s&8)
&6S3 &b>&6 5.82s &8(&75.60s&8)
&6S4 &b>&6 5.28s &8(&75.28s&8)

&e&lGoldor
&5Leaps &b>&5 1.24s &8(&71.20s&8)
&cKill &b>&c 5.66s &8(&75.50s&8)

&c&lNecron
&dAnimation &b>&a 4.83s &8(&74.75s&8)
&aMid &b>&a 7.67s &8(&77.40s&8)
&cDps &b>&c 3.15s &8(&73.15s&8)
&cDps &b>&a 5.67s &8(&75.40s&8)
&aMid &b>&a 13.67s &8(&713.40s&8)
&cDps &b>&c 0.15s &8(&70.15s&8)
&dAnimation &b>&a 4.37s &8(&74.30s&8)`]
    setTimeout(() => {
        title = " ";
        updateSplits.register();
    }, t);
};


function resetSplits() { 
    maxorDpsSplit = 0
    subSplit = 0
    playersInCore = 0
    alivePlayers = 0
    ignsInCore = ''
    aliveIgns = ''
    Object.keys(Splits).forEach((key) => {
        Splits[key] = { 
        name: Splits[key].name, 
        color: Splits[key].color, 
        display: ` `, 
        serverStart: null, 
        clientStart: null, 
        };
    });
}

function newSubSplit(){
    serverTicks = 0
    gate.blown = false
    gate.waiting = false
    if (subSplit > 25) resetSplits()
    subSplit++
    if (subSplit > 25) return
    Splits[subSplit].clientStart = Date.now();
}

function setSubSplit(i){
    serverTicks = 0
    subSplit = i
    if (i > 25) return
    Splits[i].clientStart = Date.now();
}

function scanWhoseInRenderAndAlive() {
    World.getAllPlayers().forEach(entity => {
        if (entity.isInvisible() || entity.getPing() !== 1) return
        if (aliveIgns.includes(entity.getName())) return
        aliveIgns = aliveIgns + ' ' + entity.getName()
        alivePlayers++
        console.log(entity.getName() + ' is alive, there are now ' + alivePlayers + ' alive players')
    })
}

// X 
const subSubSplits = [0, 0, 0, 0]// maxor, storm, terms, necron


const bossMessages = {
    checkTicks: [
        "[BOSS] Maxor: DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE.",
        "[BOSS] Storm: ENERGY HEED MY CALL!",
        "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!"],
    resetTicksAndNewSubSplit: [
        "[BOSS] Storm: Oof", 
        "[BOSS] Storm: Ouch, that hurt!"],
    newSplit: [
        "[BOSS] Maxor: YOU TRICKED ME!",
        "[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!",
        "⚠ Maxor is enraged! ⚠",
        "[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!",
        "[BOSS] Maxor: I'LL MAKE YOU REMEMBER MY DEATH!!",
        "[BOSS] Storm: THAT WAS ONLY IN MY WAY!",
        "[BOSS] Storm: Slowing me down will be your greatest accomplishment!",
        "[BOSS] Storm: This factory is too small for me!",
        "[BOSS] Storm: BEGONE PILLAR!",
        "[BOSS] Necron: That's a very impressive trick. I guess I'll have to handle this myself.",
        "[BOSS] Necron: Sometimes when you have a problem, you just need to destroy it all and start again.",
        "[BOSS] Necron: WITNESS MY RAW NUCLEAR POWER!",
        "[BOSS] Necron: ARGH!",
        "[BOSS] Necron: Let's make some space!",
        "[BOSS] Necron: All this, for nothing...",
        "                 > EXTRA STATS <"
    ]
};

const toggle2 = register("chat", (message) => {
    if (bossMessages.checkTicks.includes(message)) checkTicks.register()
    if (bossMessages.newSplit.includes(message)) newSubSplit()
    if (bossMessages.resetTicksAndNewSubSplit.includes(message)){
        serverTicks = 0;
        if (subSubSplits[2] > 3) return;
        subSubSplits[2]++;
        newSubSplit();
    }
    switch (message) {
        case "[BOSS] Storm: Pathetic Maxor, just like expected.": setSubSplit(7); break;
        case "[BOSS] Storm: I should have known that I stood no chance.": newSubSplit(12); break;
        case "[BOSS] Goldor: Who dares trespass into my domain?": setSubSplit(13); break;

        case "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.":
        case "[BOSS] Necron: You went further than any human before, congratulations.":
            leapinFrogs.unregister();
            setSubSplit(19);
            break;

        default:
            break;
    }
}).setCriteria("${message}");

const toggle1 = register("chat", () => {
    overlay.register()
    toggle2.register()
    toggle3.register()
    resetSplits();
    newSubSplit();
    setTimeout(() => scanWhoseInRenderAndAlive(), 1000);
}).setCriteria("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!");


let coreStartTime
register("chat", (action, object, completed, total) => {
    if (completed !== total) return
    if (subSplit == 16) newSubSplit()
    if (subSplit == 15) {coreStartTime = Date.now(); leapinFrogs.register() }
    gate.blown? newSubSplit() : gate.waiting = true
}).setCriteria(/.+ (activated|completed) a (terminal|device|lever)! \((\d)\/(\d)\)/)

const gate = { blown: false, waiting: false }

register("chat", () => {
    gate.waiting ? newSubSplit() : gate.blown = true 
}).setCriteria("The gate has been destroyed!")



const checkTicks = register("packetReceived", () => {
    if (serverTicks == 166 && subSubSplits[1] == 0) {
        newSubSplit()
        checkTicks.unregister()
    }
    if (serverTicks == 688 && subSubSplits[2] == 0) {
        newSubSplit()
        checkTicks.unregister()
    }
}).setFilteredClass(S32PacketConfirmTransaction).unregister()


// LEAPS


let alivePlayers = 0
let playersInCore = 0
let ignsInCore = ''
let aliveIgns = ''

register("chat", (ign) => {
    if (ign == 'You') ign = Player.getName()
    if (!aliveIgns.includes(ign)) return
    alivePlayers--
    aliveIgns = aliveIgns.replace(new RegExp(`\\s*${ign}\\s*`), ' ').trim()
    if (ignsInCore.includes(ign)) {
        playersInCore--
        ignsInCore = ignsInCore.replace(new RegExp(`\\s*${ign}\\s*`), ' ').trim()
    }
}).setCriteria(/ ☠ (\w+) .+ and became a ghost./)

register("chat", (ign) => {
    if (ign == 'You') ign = Player.getName()
    if (aliveIgns.includes(ign)) return
    alivePlayers++
    aliveIgns = aliveIgns + ' ' + ign
}).setCriteria(/ ❣ (\w+) was revived by .+/)

const leapinFrogs = register("packetReceived", () => {
    World.getAllPlayers().forEach(entity => {
        if (entity.getPing() !== 1) return // checking only this first might make it a little faster
        if (entity.isInvisible()) return
        if (!ignsInCore.includes(entity.getName()) && 
        ((entity.getX()) < 71) && ((entity.getX()) >= 39) && 
        ((entity.getY()) < 155.5) && ((entity.getY()) >= 112) && 
        ((entity.getZ()) < 118) && ((entity.getZ()) >= 54)) {
            if (subSplit == 15 || subSplit == 16) ChatLib.chat(`${entity.getName()} Entered core at &e[&a0s&e]`)
            else if (subSplit == 17) ChatLib.chat(`${entity.getName()} Entered core at &e[&a${((Date.now() - coreStartTime) / 1000).toFixed(2)}s&e]`)
            playersInCore++
            ignsInCore = ignsInCore + ' ' + entity.getName()
            if (playersInCore == alivePlayers) {
            leapinFrogs.unregister()
            newSubSplit()
            }
        } 
        if (subSplit < 16) return
        if (!ignsInCore.includes(entity.getName())) return
        if (((entity.getX()) >= 71) || ((entity.getX()) < 39) || ((entity.getY()) >= 155.5) || ((entity.getY()) < 112) || ((entity.getZ()) >= 118) || ((entity.getZ()) < 54)) {
            playersInCore--
            ignsInCore = ignsInCore.replace(new RegExp(`\\s*${entity.getName()}\\s*`), ' ').trim()
            console.log(alivePlayers + ' alive players, ' + playersInCore + ' players in core, ' + ignsInCore)
        }
    })
}).setFilteredClass(S32PacketConfirmTransaction).unregister()

/// TICKS ///

let serverTicks

const toggle3 = register("packetReceived", (packet) => {if (packet.func_148890_d() <= 0) serverTicks++}).setFilteredClass(S32PacketConfirmTransaction)


function mainToggle(bool){
    bool ? toggle1.register() : toggle1.unregister()
};mainToggle(Data.toggled)

// MAXOR
/* Intro Dialogue
[BOSS] Maxor: WELL WELL WELL LOOK WHO'S HERE!
[BOSS] Maxor: I'VE BEEN TOLD I COULD HAVE A BIT OF FUN WITH YOU.
[BOSS] Maxor: DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE.
*/

/* Stunned by laser
[BOSS] Maxor: YOU TRICKED ME!
[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!
*/

/* Enraged
⚠ Maxor is enraged! ⚠
*/

/* Maxor used a special attack
[BOSS] Maxor: Eat Wither Skulls, scum!
[BOSS] Maxor: How about you taste some rapid fire Wither Skulls!
[BOSS] Maxor: Time for me to blast you away for good!
*/

/* Maxor killed
[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!
[BOSS] Maxor: I'LL MAKE YOU REMEMBER MY DEATH!!
*/



/* Run failed
[BOSS] Maxor: FINALLY! This took way too long.
[BOSS] Maxor: Now that you're a Ghost, can you help me clean up?
*/

/* "Random Messages"
[BOSS] Maxor: YOUR WEAPONS CAN'T PIERCE THROUGH MY SHIELD!
[BOSS] Maxor: I HOPE YOU LIKE EXPLOSIONS TOO!
[BOSS] Maxor: MY MINIONS WILL HAVE TO WIPE THE FLOOR AFTER I'M DONE WITH YOU ALL!
[BOSS] Maxor: YOUR MOBILITY TRICKS DON'T WORK IN MY DOMAIN!
*/





// STORM
/* Intro Dialogue
[BOSS] Storm: Pathetic Maxor, just like expected.
[BOSS] Storm: Don't boast about beating this simple minded Wither.
[BOSS] Storm: My abilities are unparalleled, in many ways I am the last bastion.
[BOSS] Storm: The memory of your death will be your fondest, focus up!
[BOSS] Storm: The power of lightning is quite phenomenal. A single strike can vapourise a person whole.
[BOSS] Storm: I'd be happy to show you what that's like!
*/

/* Lightning
[BOSS] Storm: ENERGY HEED MY CALL!
[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!
*/

/* Storm crushed
[BOSS] Storm: Ouch, that hurt!
[BOSS] Storm: Oof
*/

/* Ability to damage over
[BOSS] Storm: THAT WAS ONLY IN MY WAY!
[BOSS] Storm: Slowing me down will be your greatest accomplishment!
[BOSS] Storm: This factory is too small for me!
*/

/* Storm killed
[BOSS] Storm: I should have known that I stood no chance.
[BOSS] Storm: At least my son died by your hands.
*/



/* Someone died to lightning
[BOSS] Storm: Fool, I'd hide under something next time if I were you!
[BOSS] Storm: Foolish, a broken pillar won't provide you any cover!
*/

/* Storm locked
[BOSS] Storm: Bahahaha! Not a single intact pillar remains!
[BOSS] Storm: Rejoice, your last moments are with me and my lightning.
*/

/* Run failed
[BOSS] Storm: FINALLY! This took way too long.
[BOSS] Storm: Now that you're a Ghost, can you help me clean up?
*/

/* "Random Messages"
[BOSS] Storm: The Age of Men is over, we are creating tens, hundreds of withers!!
[BOSS] Storm: Not just your land, but every kingdom will soon be ruled by our army of undead!
[BOSS] Storm: No more adventurers, no more heroes, death and thunder!
[BOSS] Storm: The days are numbered until I am finally unleashed again on the world!
*/