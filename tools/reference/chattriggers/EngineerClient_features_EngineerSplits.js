import data  from "../data"
const Data = data.splits

// 3:28.2 pb run: /splits pace 0 61 4.2 25.5 45.5 31 6.2 30.3 4.5
// 3:48.2 casual: /splits pace 14 63 4.2 25.5 45.5 35 7.2 30.3 4.5
// 3:59.2 casual: /splits pace 17 63 4.2 25.5 45.5 42 7.2 30.3 4.5

function formatTime(seconds) {return `${Math.floor(seconds / 60)}m ${(seconds % 60).toFixed(1)}`;}

register("worldLoad", () => {
    phase = 0
    if (Data.onWorldLoad) {
        resetSplits()
        title = ` `
    }
})

register("command", (command, open, blood, portal, maxor, storm, terms, goldor, necron, animation) => { 
    switch (command) {
    case undefined: ;
    case "": 
    ChatLib.chat(`\n                         &cEngineer&bSplits\n`);

    new TextComponent(`                            ${Data.toggled ? "&a" : "&c"}&nToggled ${Data.toggled ? "on" : "off"}`)
    .setHover("show_text", `/Splits toggle`).setClick("run_command", `/Splits toggle`).chat()

    ChatLib.chat(`
&3 /Splits&b worldload:&9 Hides splits on worldload
&3 /SubSplits&b move &bx&7: &8(&7${Data.x}&8) &by&7: &8(&7${Data.y}&8) &bScale&7: &8(&7${Data.scale}&8)&b:&9 Changes position the SubSplits display
&3 /Splits&b show &8(&7time&8):&9 Shows splits with value set for pace for a default of 2s
&3 /Splits&b start:&9 Starts splits
&3 /Splits&b next:&9 Starts the next split
&3 /Splits&b pace &7(&aOpen&7) (&cBlood&7) (&dPortal&7) (&aMaxor&7) (&bStorm&7) (&6Terms&7) (&eGoldor&7) (&cNecron&7) (&dAnimation&7/&dDragons&7)&b:&9 /Splits show will have your current pace
`);
// couldnt figure out how to make .setFps work with a variable so i just removed it
// &3 /Splits&b fps &7(&bfps&7)&b:&9 Changes how often the splits gui updates
        break;

    // case "fps":
    //     if (!open) return ChatLib.chat("&c what are you doing?")
    //     Data.fps = parseInt(open);
    //     ChatLib.chat(` &3Splits &9fps set to &7(&b${Data.fps}&7)`)
    //     break;

    case "toggle":
        Data.toggled = !Data.toggled;
        data.save()
        mainToggle(Data.toggled)
        ChatLib.chat(` &3Splits &9toggled ${Data.toggled ? "&aon" : "&coff"}`);
        break;

    case "worldload":
        Data.onWorldLoad = !Data.onWorldLoad;
        data.save()
        ChatLib.chat(` &3Splits &9WorldLoad ${Data.onWorldLoad ? "&aon" : "&coff"}`);
        break;

    case "show":
        const time = open ? (open*1000) : null;
        updateTestTitle(time)
        break;

    case "move":
        if (!open) return ChatLib.chat(` &3SectionTimes &9is currently at &bx&7: &8(&7${Data.x}&8) &by&7: &8(&7${Data.y}&8) &bScale&7: &8(&7${Data.scale}&8)`)
        if (open) Data.x = open
        if (blood) Data.y = blood
        if (portal) Data.scale = portal
        data.save()
        ChatLib.chat(` &3SectionTimes &9moved to &bx&7: &8(&7${Data.x}&8) &by&7: &8(&7${Data.y}&8) &bScale&7: &8(&7${Data.scale}&8)`)
        updateTestTitle()
        break;


    case "start":
        phase = 0;
        newPhase();
        break;

    case "next":
        if (phase > 9) return;
        newPhase();
        break;

    case "pace":
            Data.open = parseFloat(open);     Data.blood = parseFloat(blood);   Data.portal = parseFloat(portal)
            Data.maxor = parseFloat(maxor);   Data.storm = parseFloat(storm);   Data.terms = parseFloat(terms)
            Data.goldor = parseFloat(goldor); Data.necron = parseFloat(necron); Data.animation = parseFloat(animation)
        pacePace = Data.open + Data.blood + Data.portal + Data.maxor + 
        Data.storm + Data.terms + Data.goldor + Data.necron + Data.animation
        enterPace = Data.open + Data.blood + Data.portal
        ChatLib.chat(`\n         &6Current Pace\n
 &3Pace &b> &3${formatTime(pacePace)}s\n &aOpen &b> &a${Data.open}s\n &cBlood &b> &c${Data.blood}s 
 &dPortal &b> &d${Data.portal}s\n &9Enter &b> &9${formatTime(enterPace)}s\n &5Maxor &b> &5${Data.maxor}s
 &bStorm &b> &b${Data.storm}s\n &6Terms &b> &6${Data.terms}s\n &eGoldor &b> &e${Data.goldor}s
 &cNecron &b> &c${Data.necron}s\n &d${floor} &b> &d${Data.animation}s`)
 data.save()
        updateTestTitle()
        break;

    default:
        ChatLib.chat(`&ccouldnt find /splits "${command}"`)
}}).setName("splits2")

const overlay = register("renderOverlay", () => {
    if (!Data.toggled) return
    text1 = new Text(
        title, Data.x, Data.y)
        .setShadow(true).setScale(Data.scale)
    text1.draw()
})

let floor = "Animation"

let testtitle
title = testtitle

let title = ` `

const Splits = {};
["Open", "Blood", "Portal", "Maxor", "Storm", "Terms", "Goldor", "Necron", "Animation"].forEach((name, i) => {
    Splits[i + 1] = {name, color: ['&a', '&c', '&d', '&5', '&b', '&6', '&e', '&c', '&d'][i],display: ` `, serverStart: null, serverEnd: 2, clientStart: null, clientEnd: null};
});

function newPhase(){
    if (phase == 0) {
        resetSplits(); phase++
        resetTicks(); Splits[1].clientStart = Date.now();
    } else {
        if (Splits[phase].display){
            ChatLib.chat(Splits[phase].display)
            Splits[phase].clientEnd = Date.now();
            Splits[phase].serverEnd = serverTicks;
        }
        resetTicks();
        phase++
        if (phase > 9) return
        Splits[phase].clientStart = Date.now();
    }
}

const paceDefault = [0, Data.open, Data.blood, Data.portal, Data.maxor,
Data.storm, Data.terms, Data.goldor, Data.necron, Data.animation]

let paceClient = [``, ``, ``, ``, ``, ``, ``, ``, ``]
let paceServer = [``, ``, ``, ``, ``, ``, ``, ``, ``]

let lastPhase = 1
let lastServerTicks = 0
function addSplits() {
    const result = [` `, ` `, ` `, ` `]
    p = phase
    if (p < 1) return
    if (p > lastPhase){
        if (p == 1){
            paceClient[p] = Math.abs((Splits[p].clientStart - Date.now())/1000)
            paceServer[p] = (lastServerTicks/20) } 
        else {
            paceClient[p-1] = Math.abs((Splits[p-1].clientStart - Date.now())/1000)
            paceServer[p-1] = (lastServerTicks/20)
        }
        lastPhase++
        if (lastPhase > 9) lastPhase = 1
    }
    lastServerTicks = serverTicks
    paceClient[p] = Math.max(Math.abs((Splits[p].clientStart - Date.now())/1000), paceDefault[p])
    paceServer[p] = Math.max((serverTicks/20), paceDefault[p])
    
    if (!paceClient[1]) return
    result[1] = (paceClient.reduce((a, c) => a + c, 0)).toFixed(2)
    result[2] = (paceServer.reduce((a, c) => a + c, 0)).toFixed(2)
    result[3] = (paceClient.slice(1, 4).reduce((a, c) => a + c, 0)).toFixed(2)
    result[4] = (paceServer.slice(1, 4).reduce((a, c) => a + c, 0)).toFixed(2)
    return result;
}

function resetSplits() { 
    paceClient = paceClient.map((_, i) => paceDefault[i])
    paceServer = paceServer.map((_, i) => paceDefault[i])
    phase = 0
    lastPhase = 0
    Object.keys(Splits).forEach((key) => {
        Splits[key] = { 
        name: Splits[key].name, 
        color: Splits[key].color, 
        display: ` `, 
        serverStart: null, 
        serverEnd: null, 
        clientStart: null, 
        clientEnd: null 
        };
    });
}

let phase = 0
const updateSplits = register("step", () => {
    fixTicks()
    if (phase > 9 || phase == 0) return
    
    const clear = [` `, ` `]
    if (phase > 3) {clear[1] = [1, 2, 3].reduce((sum, i) => sum + Math.abs((Splits[i].clientStart - Splits[i].clientEnd) / 1000), 0).toFixed(2);
        clear[2] = [1, 2, 3].reduce((sum, i) => sum + Splits[i].serverEnd, 0) / 20;}

    const pace = addSplits()
    const p = Splits[phase]
    const timeDiff = Math.abs((p.clientStart - Date.now()) / 1000).toFixed(2);
    const serverTime = (serverTicks / 20).toFixed(2);
    if (phase < 9) p.display = `${p.color}${p.name} &b> ${p.color}${timeDiff}s &8(&7${serverTime}s&8)`;
    else p.display = `${p.color}${floor} &b> ${p.color}${timeDiff}s &8(&7${serverTime}s&8)`;
    
    title = 
`&3Pace &b> &3${formatTime(pace[1])}s&8(&7${formatTime(pace[2])}s&8)
${Splits[1].display}
${Splits[2].display}
${Splits[3].display}
&9Enter &b> &9${formatTime(pace[3])}s&8(&7${formatTime(pace[4])}s&8) 
${Splits[4].display}
${Splits[5].display}
${Splits[6].display}
${Splits[7].display}
${Splits[8].display}
${Splits[9].display}`}).setFps(60)



function updateTestTitle(time){
    const t = time ? time : 2000;
    pacePace = Data.open + Data.blood + Data.portal + Data.maxor + 
    Data.storm + Data.terms + Data.goldor + Data.necron + Data.animation
    enterPace = Data.open + Data.blood + Data.portal
    testtitle = 
    [`&3Pace &b> &3${formatTime(pacePace)}s &8(&7${formatTime(pacePace)}s&8)
&aOpen &b> &a${Data.open}s &8(&7${Data.open}s&8)
&cBlood &b> &c${Data.blood}s &8(&7${Data.blood}s&8)
&dPortal &b> &d${Data.portal}s &8(&7${Data.portal}s&8)
&9Enter &b> &9${formatTime(enterPace)}s &8(&7${formatTime(enterPace)}s&8)
&5Maxor &b> &5${Data.maxor}s &8(&7${Data.maxor}s&8)
&bStorm &b> &b${Data.storm}s &8(&7${Data.storm}s&8)
&6Terms &b> &6${Data.terms}s &8(&7${Data.terms}s&8)
&eGoldor &b> &e${Data.goldor}s &8(&7${Data.goldor}s&8)
&cNecron &b> &c${Data.necron}s &8(&7${Data.necron}s&8)
&d${floor} &b> &d${Data.animation}s &8(&7${Data.animation}s&8)`];

updateSplits.unregister();
    title = testtitle;
    setTimeout(() => {
        title = " ";
        updateSplits.register();
    }, t);
};//updateTestTitle()
//use if testing


/// START MESSAGES ///

const startMessages = [
    "[BOSS] Livid: This Orb you see, is Thorn, or what is left of him.",
    "[BOSS] The Watcher: Congratulations, you made it through the Entrance.",
    "[BOSS] The Watcher: Ah, you've finally arrived.",
    "[BOSS] The Watcher: Ah, we meet again...",
    "[BOSS] The Watcher: So you made it this far... interesting.",
    "[BOSS] The Watcher: I'm starting to get tired of seeing you around here...",
    "[BOSS] The Watcher: Oh.. hello?",
    "[BOSS] The Watcher: Things feel a little more roomy now, eh?",
    "[BOSS] The Watcher: You have proven yourself. You may pass.",
    "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!",
    "[BOSS] Storm: Pathetic Maxor, just like expected.",
    "[BOSS] Goldor: Who dares trespass into my domain?",
    "The Core entrance is opening!",
    "[BOSS] Necron: Finally, I heard so much about you.",
    "[BOSS] Necron: You went further than any human before, congratulations.",
    "[BOSS] Necron: All this, for nothing...",
    "                 > EXTRA STATS <",
    "                  > EXTRA STATS <",
    "                   > EXTRA STATS <",
    "                    > EXTRA STATS <",
    "                     > EXTRA STATS <",
    "                      > EXTRA STATS <",
    "                       > EXTRA STATS <",
    "                        > EXTRA STATS <",
    "                         > EXTRA STATS <",
    "                          > EXTRA STATS <",
    "                           > EXTRA STATS <",
    "                            > EXTRA STATS <",
    "                             > EXTRA STATS <",
    "                              > EXTRA STATS <",
    "                               > EXTRA STATS <",
    "                                > EXTRA STATS <",
    "                                 > EXTRA STATS <",
    "                                  > EXTRA STATS <",
    "                                   > EXTRA STATS <",
    "                                    > EXTRA STATS <",
    "                                     > EXTRA STATS <",
    "                                      > EXTRA STATS <",
    "                                       > EXTRA STATS <",
    "                                        > EXTRA STATS <",
    "                                         > EXTRA STATS <",
    "                                          > EXTRA STATS <",
    "                                           > EXTRA STATS <",
    "                                            > EXTRA STATS <",
    "                                             > EXTRA STATS <",
    "                                              > EXTRA STATS <",
    "                                               > EXTRA STATS <",
    "                                                > EXTRA STATS <",
    "                                                 > EXTRA STATS <",
];

const toggle2 = register("chat", (message) => {
    if (startMessages.includes(message)) newPhase()
    if (message == "Starting in 1 second."){
        startCheck.register();
        setTimeout(() => startCheck.unregister(), 4500);}
}).setCriteria("${message}");

const startCheck = register("tick", () => {
    const lines = Scoreboard.getLines(false);lines.map((line) => {
    if ((line.getName().removeFormatting().replace(/[^\x00-\x7F]/g, "")).includes("Time Elapsed")) {startCheck.unregister()
        phase = 0
        newPhase
()}})}).unregister()

// register("chat", () => {
//     const lines = Scoreboard.getLines(false);lines.map((line) => {
//         if ((line.getName().removeFormatting().replace(/[^\x00-\x7F]/g, "")).includes("(M7)")) {
//             floor = "&8Dragons"
//         } else if ((line.getName().removeFormatting().replace(/[^\x00-\x7F]/g, "")).includes("(F7)")) {
//             floor = "Animation"
//         }
//     })
// }).setCriteria("Starting in 4 seconds.");


/// TICKS ///


const S32PacketConfirmTransaction = Java.type("net.minecraft.network.play.server.S32PacketConfirmTransaction");
const toggle3 = register("packetReceived", (packet) => {
    if (packet.func_148890_d() > 0) return
	realServerTicks++
}).setFilteredClass(S32PacketConfirmTransaction)

let realServerTicks
let clientStartTime
let serverTicks

function resetTicks(){
    clientStartTime = Date.now()
    realServerTicks = 0
    serverTicks = 0
}

let hasRun = false
function fixTicks(){
    if (realServerTicks == 0){
        hasRun = true; 
        serverTicks = (Math.abs(clientStartTime - Date.now())/50).toFixed(0)
    } else if (hasRun) {
        realServerTicks = (Math.abs(clientStartTime - Date.now())/50).toFixed(0)
        serverTicks = (Math.abs(clientStartTime - Date.now())/50).toFixed(0)
        hasRun = false; }
    else serverTicks = realServerTicks; 
}


function mainToggle(bool){
    if(bool){
        overlay.register()
        toggle2.register()
        toggle3.register()
        updateSplits.register()
    } else {
        overlay.unregister()
        toggle2.unregister()
        toggle3.unregister()
        updateSplits.register()
    }
};mainToggle(Data.toggled)