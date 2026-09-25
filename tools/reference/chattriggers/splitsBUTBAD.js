const S32PacketConfirmTransaction = Java.type("net.minecraft.network.play.server.S32PacketConfirmTransaction");
import Settings from "../../config"
import RenderLibV2 from "../../../RenderLibV2";

let inclear = false
let tacOutside = false
if (!Settings.taccountdown == false) {
    tacOutside = true
}
let bloodCheckmark = [` `]
let indungeon = false
let purpTimerEnabled = false
let maxorSplitDisplay = 0.00
let stormSplitDisplay = 0.00
let terminalsSplitDisplay = 0.00
let goldorSplitDisplay = 0.00
let necronSplitDisplay = 0.00
let animationSplitDisplay = 0.00
let corePlayersDetect = 2
let playersInCore = 0
let maxorToggle = 2
let stormToggle = 2
let terminalsToggle = 2
let goldorToggle = 2
let necronToggle = 2
let animationToggle = 2
let rushToggle = 2
let campToggle = 2
let portalToggle = 2
let enterToggle = 2
let maxorTime = 2
let startCheckToggle = 2
let LASTPLAYERINCOREkicktime = ""
let bosssplitsrender = [
    `&aMaxor: &r&a${maxorSplitDisplay}s
&bStorm: &r&b${stormSplitDisplay}s
&6Terminals: &r&6${terminalsSplitDisplay}s
&eGoldor: &r&e${goldorSplitDisplay}s
&cNecron: &r&c${necronSplitDisplay}s
&dAnimation: &r&d${animationSplitDisplay}s`,
        ]
let maxorStartTime
let witherStartTicks = 0
let purpPadTicks
let purpPadDisplay
let rushSplitDisplay = 0
let campSplitDisplay = 0.0
let portalSplitDisplay = 0.0
let enterSplitDisplay = 0.0
let rushStartTime
let campStartTime
let portalStartTime
let clearsplitsrender = [
    `&aBlood Rush: &r&a${rushSplitDisplay}s
&cBlood Camp: &r&c${campSplitDisplay}s &a${bloodCheckmark}
&dPortal: &r&d${portalSplitDisplay}s
&6Boss Enter: &r&6${enterSplitDisplay}s`,
]
const bloodStartMessage = [
    "[BOSS] The Watcher: Congratulations, you made it through the Entrance.",
    "[BOSS] The Watcher: Ah, you've finally arrived.",
    "[BOSS] The Watcher: Ah, we meet again...",
    "[BOSS] The Watcher: So you made it this far... interesting.",
    "[BOSS] The Watcher: I'm starting to get tired of seeing you around here...",
    "[BOSS] The Watcher: Oh.. hello?",
    "[BOSS] The Watcher: Things feel a little more roomy now, eh?",
    "[BOSS] The Watcher: Ah, you've finally arrived.",
    "[BOSS] The Watcher: Ah, we meet again...",
    "[BOSS] The Watcher: So you made it this far... interesting.",
]

function updateFPS() {
    if (rushToggle == 0) {
    rushSplitDisplay = (((Date.now() - rushStartTime) / 1000).toFixed(1))
    } if (campToggle == 0) {
    campSplitDisplay = (((Date.now() - campStartTime) / 1000).toFixed(1))
    } if (portalToggle == 0) {
    portalSplitDisplay = (((Date.now() - portalStartTime) / 1000).toFixed(1))
    } if (enterToggle == 0) {
    enterSplitDisplay = (((Date.now() - rushStartTime) / 1000).toFixed(1)) 
    } if (!Settings.namedclearsplits) {
        if (enterSplitDisplay >= 60) {
            clearsplitsrender = [
                `&a${rushSplitDisplay}s\n&c${campSplitDisplay}s &a${bloodCheckmark}
&d${portalSplitDisplay}s
&6${(Math.floor((enterSplitDisplay) / 60)).toFixed(0)}m ${((((enterSplitDisplay) % 60) / 100).toFixed(4) * 100).toFixed(1)}s`,
                        ]
        } else {
            clearsplitsrender = [
                `&a${rushSplitDisplay}s
&c${campSplitDisplay}s &a${bloodCheckmark}
&d${portalSplitDisplay}s
&6${enterSplitDisplay}s`,
            ]
        }
    } else {
        if (enterSplitDisplay >= 60) {
            clearsplitsrender = [
                `&aBlood Rush: &r&a${rushSplitDisplay}s\n&cBlood Camp: &r&c${campSplitDisplay}s &a${bloodCheckmark}
&dPortal: &r&d${portalSplitDisplay}s
&6Boss Enter: &r&6${(Math.floor((enterSplitDisplay) / 60)).toFixed(0)}m ${((((enterSplitDisplay) % 60) / 100).toFixed(4) * 100).toFixed(1)}s`,
                        ]
        } else {
            clearsplitsrender = [
                `&aBlood Rush: &r&a${rushSplitDisplay}s
&cBlood Camp: &r&c${campSplitDisplay}s &a${bloodCheckmark}
&dPortal: &r&d${portalSplitDisplay}s
&6Boss Enter: &r&6${enterSplitDisplay}s`,
                        ]
        }
    } 
}

function startClearSplitUpdates() {
    clearSplitUpdating = updateFPS
}

function stopClearSplitUpdates() {
    clearInterval(clearSplitUpdating)
}

register("step", () => {
    try {
        if (enterToggle == 0) {
            updateFPS()
        }
        } catch (e) {}
}).setFps(10);


const overlayTriggerBossSplits = register("renderOverlay", () => {
    if (!Settings.bosssplits) return
    const [bosssplitX, bosssplitY] = [Settings.bosssplitsX, Settings.bosssplitsY];
    Renderer.drawStringWithShadow((bosssplitsrender), (bosssplitX * Renderer.screen.getWidth()), (bosssplitY * Renderer.screen.getHeight()))  }).unregister();

const overlayTriggerClearSplits = register("renderOverlay", () => {
    const [clearsplitsX, clearsplitsY] = [Settings.clearsplitsX, Settings.clearsplitsY];
    if (!Settings.clearsplits) return
    Renderer.drawStringWithShadow((clearsplitsrender), (clearsplitsX * Renderer.screen.getWidth()), (clearsplitsY * Renderer.screen.getHeight()))  }).unregister();

const overlayTriggerPurpCountdown = register("renderOverlay", () => {
    if (!Settings.purplepadtimer) return
    Renderer.drawStringWithShadow((purpPadDisplay), ((Renderer.screen.getWidth() / 2) - 9), ((Renderer.screen.getHeight() / 2) + 25))  }).unregister();

const dungeonStartSound = register(`soundPlay`, () => {
    if (startCheckToggle === 0) {
        indungeon = true
        witherStartTicks = 0
        rushToggle = 0
        enterToggle = 0
        rushStartTime = (Date.now() + 100)
        overlayTriggerClearSplits.register()
        startClearSplitUpdates()
    }
}).setCriteria("mob.enderdragon.growl")

function toggleStartCheck() {
    startCheckToggle = 2
}


////////////////////////////////////////////////////////////////////


















const overlayTriggerTac = register("renderOverlay", () => {
    if (!tacOutside == false) {
        if (witherTicks >= -1) {
        overlayTriggerTac.unregister();}
        if (!tacOutside || !Settings.tactimertoggle) return;
        Renderer.drawStringWithShadow('&3' + (((witherTicks) / -20).toFixed(1)), ((Renderer.screen.getWidth() / 2) - 7), ((Renderer.screen.getHeight() / 2) + 25))
    } else {
        if (witherTicks >= -1) {
        overlayTriggerTac.unregister();}
        if (!inclear || !Settings.tactimertoggle) return;
        Renderer.drawStringWithShadow('&3' + (((witherTicks) / -20).toFixed(1)), ((Renderer.screen.getWidth() / 2) - 7), ((Renderer.screen.getHeight() / 2) + 25))
    } 
}).unregister();

  register(`soundPlay`, () => {
    const item = Player.getHeldItem();
    if(!item.getName().includes('Tactical Insertion') && (witherTicks > 0)) return
    witherTicks = -58
    overlayTriggerTac.register()
}).setCriteria("fire.ignite")





































/////////////////////////////////////////////////////////////////////

register("chat", (message) => {
    if (message === "Starting in 4 seconds.") {
        inclear = true
    }
}).setCriteria("${message}")

register("chat", (message) => {
    if (message === "Starting in 1 second.") {
    startCheckToggle = 0
    setTimeout(toggleStartCheck, 3000)
    }
}).setCriteria("${message}")

register("chat", (message) => {
    if (!bloodStartMessage.includes(message)) return
    rushToggle = 2
    campToggle = 0
    campStartTime = (Date.now())
}).setCriteria("${message}")

register("chat", (message) => {
    if (message === "[BOSS] The Watcher: You have proven yourself. You may pass.") {
    campToggle = 2
    portalStartTime = (Date.now())
    portalToggle = 0
    startClearSplitUpdates()
    }
}).setCriteria("${message}")

register("chat", (message) => {
    if (message === "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!") {
    inclear = false
    portalToggle = 2
    enterToggle = 2
    startClearSplitUpdates()
    stopClearSplitUpdates()
    }
}).setCriteria("${message}")

register("chat", (message) => {
    if (message === "[BOSS] The Watcher: That will be enough for now.") {
        bloodCheckmark = ["✔"]
        startClearSplitUpdates()
    }
}).setCriteria("${message}")




register("chat", (message) => {
    if (!dungeonStartMessage.includes(message)) return
    maxorSplitDisplay = 0.00
    stormSplitDisplay = 0.00
    terminalsSplitDisplay = 0.00
    goldorSplitDisplay = 0.00
    necronSplitDisplay = 0.00
    animationSplitDisplay = 0.00
    overlayTriggerBossSplits.register()
 }).setCriteria("${message}");

const unloadTrigger = register("worldUnload", () => {
    inclear = false
    tacOutside = false
    if (!Settings.taccountdown == false) {
        tacOutside = true
    }
    indungeon = false
    purpTimerEnabled = false
    maxorSplitDisplay = 0.00
    stormSplitDisplay = 0.00
    terminalsSplitDisplay = 0.00
    goldorSplitDisplay = 0.00
    necronSplitDisplay = 0.00
    animationSplitDisplay = 0.00
    corePlayersDetect = 2
    playersInCore = 0
    maxorToggle = 2
    stormToggle = 2
    terminalsToggle = 2
    goldorToggle = 2
    necronToggle = 2
    animationToggle = 2
    rushToggle = 2
    campToggle = 2
    portalToggle = 2
    enterToggle = 2
    rushSplitDisplay = -1.0
    campSplitDisplay = 0.0
    portalSplitDisplay = 0.0
    enterSplitDisplay = -1.0
    startCheckToggle = 2
    bloodCheckmark = [` `]
    overlayTriggerTac.unregister();
    overlayTriggerClearSplits.unregister();
	overlayTriggerBossSplits.unregister();
    overlayTriggerPurpCountdown.unregister();
})

let witherTicks = 0
let stormTime
let stormStartTime
let necronTime
let necronStartTime
let terminalsTime
let terminalsStartTime
let goldorTime
let goldorStartTime

const maxorMessages = [
    "[BOSS] Maxor: I'VE BEEN TOLD I COULD HAVE A BIT OF FUN WITH YOU.",
    "[BOSS] Maxor: DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE.",
    "[BOSS] Maxor: YOUR WEAPONS CAN'T PIERCE THROUGH MY SHIELD!",
    "[BOSS] Maxor: I HOPE YOU LIKE EXPLOSIONS TOO!",
    "[BOSS] Maxor: MY MINIONS WILL HAVE TO WIPE THE FLOOR AFTER I'M DONE WITH YOU ALL!",
    "[BOSS] Maxor: YOUR MOBILITY TRICKS DON'T WORK IN MY DOMAIN!",
    "[BOSS] Maxor: Eat Wither Skulls, scum!",
    "[BOSS] Maxor: How about you taste some rapid fire Wither Skulls!",
    "[BOSS] Maxor: Time for me to blast you away for good!",
    "[BOSS] Maxor: YOU TRICKED ME!",
    "[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!",
    "[BOSS] Maxor: FINALLY! This took way too long.",
    "[BOSS] Maxor: Now that you're a Ghost, can you help me clean up?",
    "[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!",
    "[BOSS] Maxor: I'LL MAKE YOU REMEMBER MY DEATH!!",
]

const stormMessages = [
    "[BOSS] Storm: Don't boast about beating this simple minded Wither.",
    "[BOSS] Storm: My abilities are unparalleled, in many ways I am the last bastion.",
    "[BOSS] Storm: The memory of your death will be your fondest, focus up!",
    "[BOSS] Storm: The power of lightning is quite phenomenal. A single strike can vaporize a person whole.",
    "[BOSS] Storm: I'd be happy to show you what that's like!",
    "[BOSS] Storm: THAT WAS ONLY IN MY WAY!",
    "[BOSS] Storm: Slowing me down will be your greatest accomplishment!",
    "[BOSS] Storm: This factory is too small for me!",
    "[BOSS] Storm: Bahahaha! Not a single intact pillar remains!",
    "[BOSS] Storm: Rejoice, your last moments are with me and my lightning.",
    "[BOSS] Storm: Fool, I'd hide under something next time if I were you!",
    "[BOSS] Storm: Foolish, a broken pillar won't provide you any cover!",
    "[BOSS] Storm: The Age of Men is over, we are creating tens, hundreds of withers!!",
    "[BOSS] Storm: Not just your land, but every kingdom will soon be ruled by our army of undead!",
    "[BOSS] Storm: No more adventurers, no more heroes, death and thunder!",
    "[BOSS] Storm: The days are numbered until I am finally unleashed again on the world!",
    "[BOSS] Storm: FINALLY! This took way too long.",
    "[BOSS] Storm: Now that you're a Ghost, can you help me clean up?",
    "[BOSS] Storm: I should have known that I stood no chance.",
    "[BOSS] Storm: At least my son died by your hands.",
    "[BOSS] Storm: Ouch, that hurt!",
    "[BOSS] Storm: Oof",
    "⚠ Storm is enraged! ⚠",
]

const necronMessages = [
    "[BOSS] Necron: I won't allow you to destroy it all now.",
    "[BOSS] Necron: I'm afraid, your journey ends now.",
    "[BOSS] Necron: My master and I spent centuries building this factory...and this army.",
    "[BOSS] Necron: Goodbye.",
    "[BOSS] Necron: That's a very impressive trick. I guess I'll have to handle this myself.",
    "[BOSS] Necron: Fight for your life!",
    "[BOSS] Necron: Show me how you beat Storm!!!",
    "[BOSS] Necron: Not just your Village, but the whole world will end!",
    "[BOSS] Necron: I - Necron - was destined to rule over Mankind! Dead or Alive!",
    "[BOSS] Necron: The Catacombs made you stronger, but can you beat me?!",
    "[BOSS] Necron: You merely adopted the Catacombs! I was molded by them!",
    "[BOSS] Necron: Sometimes when you have a problem, you just need to destroy it all and start again.",
    "[BOSS] Necron: WITNESS MY RAW NUCLEAR POWER!",
    "[BOSS] Necron: BOOOOOOOOOOOOOOOOOOOOOOOOOM",
    "[BOSS] Necron: HASTA LA VISTA, BABY!",
    "[BOSS] Necron: IF YOU WANT TO STAY COOL, DON'T LOOK!",
    "[BOSS] Necron: ARGH!",
    "[BOSS] Necron: Let's make some space!",
    "[BOSS] Necron: I ensure you, these blades are far sharper than Goldor's ever were.",
    "[BOSS] Necron: FINALLY! This took way too long.",
    "[BOSS] Necron: Now that you're a Ghost, can you help me clean up?",
    "[BOSS] Necron: I understand your words now, my master.",
    "[BOSS] Necron: The Catacombs... are no more.",
]

const maxorStartMessage = [
    "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!",
]


const stormStartMessage = [
"[BOSS] Storm: Pathetic Maxor, just like expected.",
]

const necronStartMessage = [
    "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.",
    "[BOSS] Necron: You went further than any human before, congratulations.",
]

const maxorEndMessage = [
    "[BOSS] Storm: Pathetic Maxor, just like expected.",
]

const stormEndMessage = [
"[BOSS] Goldor: Who dares trespass into my domain?",
]

const necronEndMessage = [
    "                        The Catacombs - Floor VII",
    "                  Master Mode Catacombs - Floor VII",
]

const terminalsStartMessage = [
    "[BOSS] Goldor: Who dares trespass into my domain?",
]

const terminalEndMessage = [
    "The Core entrance is opening!",
]

const animationStartMessage = [
    "[BOSS] Necron: All this, for nothing...",
]

const goldorEndMessage = [
    "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.",
    "[BOSS] Necron: You went further than any human before, congratulations.",
]

const dungeonStartMessage = [
    "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
]

register('packetReceived', () => {
    playersInCore = 0
    totalPlayers = 0
    witherStartTicks++
    if (maxorToggle === 0) {
    maxorSplitDisplay = ((witherStartTicks / 20).toFixed(2))
    } if (stormToggle === 0) {
    stormSplitDisplay = ((witherStartTicks / 20).toFixed(2))
    purpPadTicks = ((((witherStartTicks) * -1) + 644) / 20).toFixed(2)
    purpPadDisplay = [
    `&a${purpPadTicks}`,
        ]
    } if (terminalsToggle === 0) {
    terminalsSplitDisplay = ((Date.now() - terminalsStartTime) / 1000).toFixed(2)
    } if (goldorToggle === 0) {
    goldorSplitDisplay = ((witherStartTicks / 20).toFixed(2))
    } if (necronToggle === 0) {
    necronSplitDisplay = ((witherStartTicks / 20).toFixed(2))
    } if (animationToggle === 0) {
    animationSplitDisplay = ((witherStartTicks / 20).toFixed(2)) }
    if ((corePlayersDetect) === 0) {
        World.getAllPlayers().forEach(entity => {
        if (entity.isInvisible() || entity.getPing() !== 1) return
        totalPlayers++
        if (((entity.getX()) < 69) && ((entity.getX()) > 40) && ((entity.getZ()) < 118) && ((entity.getZ()) >= 55)){
            playersInCore++
        }
        if (totalPlayers == (playersInCore + 1)){
            if (
                ((entity.getX()) < 69) && ((entity.getX()) > 40) && 
                ((entity.getZ()) < 118) && ((entity.getZ()) >= 55)){
                return
            } else if (
                ((entity.getX()) < 134) && ((entity.getX()) > -8) && 
                ((entity.getY()) < 254) && ((entity.getY()) > 0) &&
                ((entity.getZ()) < 147) && ((entity.getZ()) > -8)){
                LASTPLAYERINCOREkicktime = entity.getName()
            }
        }
    });
    if ((playersInCore) === (totalPlayers)) {
        ChatLib.chat(`&6IT TOOK &e${LASTPLAYERINCOREkicktime} &3${(Date.now() - goldorStartTime) / 1000} &6Seconds and &3${witherStartTicks}&6 ticks TO GET INTO CORE!! TIME TO KICK...`)
        if (Settings.KICKTHISGUY){
            ChatLib.command(`pc IT TOOK ${LASTPLAYERINCOREkicktime} ${(Date.now() - goldorStartTime) / 1000} Seconds and ${witherStartTicks} ticks TO GET INTO CORE!! TIME TO KICK...`)
        }
        corePlayersDetect = 2
    }
}
    witherTicks++

    if (Settings.namedbosssplits){
bosssplitsrender = [
`&aMaxor: &r&a${maxorSplitDisplay}s
&bStorm: &r&b${stormSplitDisplay}s
&6Terminals: &r&6${terminalsSplitDisplay}s
&eGoldor: &r&e${goldorSplitDisplay}s
&cNecron: &r&c${necronSplitDisplay}s
&dAnimation: &r&d${animationSplitDisplay}s`,
    ]
} else {
bosssplitsrender = [
`&a${maxorSplitDisplay}s
&b${stormSplitDisplay}s
&6${terminalsSplitDisplay}s
&e${goldorSplitDisplay}s
&c${necronSplitDisplay}s
&d${animationSplitDisplay}s`,
    ]
}
purpPadDisplay = [
`&a${purpPadTicks}`,
    ]
    if (purpPadTicks <= 0.00) {
    purpTimerEnabled = false
    overlayTriggerPurpCountdown.unregister()
    }
}).setFilteredClass(S32PacketConfirmTransaction)

register("chat", (message) => {
    if (message == "[BOSS] Storm: ENERGY HEED MY CALL!") {
    purpTimerEnabled = true
    overlayTriggerPurpCountdown.register()
    }
}).setCriteria("${message}")

register("chat", (message) => {
    if (message == "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!") {
    purpTimerEnabled = true
    overlayTriggerPurpCountdown.register()
    }
}).setCriteria("${message}")

register("chat", (message) => {
    if (!maxorStartMessage.includes(message)) return
    inclear = false
    maxorStartTime = Date.now()
    ChatLib.chat(`&6The server lost &3${((enterSplitDisplay) - ((witherStartTicks) / 20)).toFixed(2)} &6seconds due to server lag in clear`)
    witherTicks = 0
    witherStartTicks = 0
    maxorToggle = 0
}).setCriteria("${message}")

register("chat", (message) => {
    if (!maxorMessages.includes(message)) return
    maxorTime = Date.now()
    witherTicks = 0
    if (!Settings.tickssincelastmessage) return  
    ChatLib.chat(`&cSince Last Message&b: &3${witherTicks} Ticks &f| &cSince Start&b: &3${witherStartTicks} Ticks and ${((maxorTime - maxorStartTime) / 1000).toFixed(2)} Seconds`)
}).setCriteria("${message}")

register("chat", (message) => {
    if (!maxorEndMessage.includes(message)) return
    maxorToggle = 2
    maxorTime = Date.now()
    ChatLib.chat(`&6Maxor Took &2${witherStartTicks}&6 Ticks &8(&7${((witherStartTicks / 20).toFixed(2))}&8) &6and&b ${((maxorTime - maxorStartTime) / 1000).toFixed(2)}&6 Seconds`)
    witherTicks = 0
    witherStartTicks = 0
    stormToggle = 0
}).setCriteria("${message}")

register("chat", (message) => {
    if (!stormStartMessage.includes(message)) return
    stormStartTime = Date.now()
}).setCriteria("${message}")

register("chat", (message) => {
    if (!stormMessages.includes(message)) return
    stormTime = Date.now()
    witherTicks = 0
    if (!Settings.tickssincelastmessage) return  
    ChatLib.chat(`&cSince Last Message&b: &3${witherTicks} Ticks &f| &cSince Start&b: &3${witherStartTicks} Ticks and ${((stormTime - stormStartTime) / 1000).toFixed(2)} Seconds`)
}).setCriteria("${message}")

register("chat", (message) => {
    if (!stormEndMessage.includes(message)) return
    stormTime = Date.now()
    stormToggle = 2
    terminalsToggle = 0
    stormSplitDisplay = ((witherStartTicks / 20).toFixed(2))
    ChatLib.chat(`&6Storm Took &2${witherStartTicks}&6 Ticks &8(&7${((witherStartTicks / 20).toFixed(2))}&8) &6and &b${((stormTime - stormStartTime) / 1000).toFixed(2)}&6 Seconds`)
}).setCriteria("${message}")

register("chat", (message) => {
    if (!terminalEndMessage.includes(message)) return
    terminalsTime = Date.now()
    terminalsToggle = 2
    goldorToggle = 0
    terminalsSplitDisplay = (((terminalsTime - terminalsStartTime) / 1000).toFixed(2))
    witherTicks = 0
    witherStartTicks = 0
    goldorStartTime = Date.now()
    corePlayersDetect = 0
}).setCriteria("${message}")

register("chat", (message) => {
    if (!goldorEndMessage.includes(message)) return
    goldorTime = Date.now(),
    ChatLib.chat(`&6Goldor Took &2${witherStartTicks}&6 Ticks &8(&7${((witherStartTicks / 20).toFixed(2))}&8) &6and&b ${((goldorTime - goldorStartTime) / 1000).toFixed(2)}&6 Seconds`)
    goldorToggle = 2
    necronToggle = 0
    goldorSplitDisplay = ((witherStartTicks / 20).toFixed(2))
    witherTicks = 0
    witherStartTicks = 0
    corePlayersDetect = 2
}).setCriteria("${message}")

register("chat", (message) => {
    if (!necronStartMessage.includes(message)) return
    necronStartTime = Date.now()
}).setCriteria("${message}")

register("chat", (message) => {
    if (!necronMessages.includes(message)) return
    necronTime = Date.now()
    witherTicks = 0
    if (!Settings.tickssincelastmessage) return 
    ChatLib.chat(`&cSince Last Message&b: &3${witherTicks} Ticks &f| &cSince Start&b: &3${witherStartTicks} Ticks and ${((necronTime - necronStartTime) / 1000).toFixed(2)} Seconds`)
}).setCriteria("${message}")

register("chat", (message) => {
    if (!animationStartMessage.includes(message)) return
    necronTime = Date.now()
    ChatLib.chat(`&6Necron Took &2${witherStartTicks}&6 Ticks &8(&7${((witherStartTicks / 20).toFixed(2))}&8) &6and &b${((necronTime - necronStartTime) / 1000).toFixed(2)}&6 Seconds`)
    necronToggle = 2
    animationToggle = 0
    necronSplitDisplay = ((witherStartTicks / 20).toFixed(2))
    witherTicks = 0
    witherStartTicks = 0
}).setCriteria("${message}")

register("chat", (message) => {
    if (!terminalsStartMessage.includes(message)) return
    terminalsStartTime = Date.now()
}).setCriteria("${message}")

register("chat", (message) => {
    if (!necronEndMessage.includes(message)) return
    animationToggle = 2
    let maxorTotal = parseFloat(maxorSplitDisplay);
    let stormTotal = parseFloat(stormSplitDisplay);
    let terminalsTotal = parseFloat(terminalsSplitDisplay);
    let goldorTotal = parseFloat(goldorSplitDisplay);
    let necronTotal = parseFloat(necronSplitDisplay);
    let animationTotal = parseFloat(animationSplitDisplay);
    let totalBossSplits = ((maxorTotal) + (stormTotal) + (terminalsTotal) + (goldorTotal) + (necronTotal) + (animationTotal))
    animationSplitDisplay = ((witherStartTicks / 20).toFixed(2))
    ChatLib.chat(`&6Necron Animation Took &2${witherStartTicks}&6 Ticks &8(&7${((witherStartTicks / 20).toFixed(2))}&8) &6and &b${((Date.now() - necronTime) / 1000).toFixed(2)}&6 Seconds`)
    ChatLib.chat(`&6The boss fight took &3${((totalBossSplits) / 60).toFixed(0)}:${((totalBossSplits) % 60).toFixed(2)} &8(&7${(totalBossSplits * 20).toFixed(0)} ticks &8) &6and &b${((Date.now() - maxorStartTime) / 60000).toFixed(0)}:${(((Date.now() - maxorStartTime) / 1000) % 60).toFixed(2)}&6 real time`)
    ChatLib.chat(`&6The server lost &3${(((Date.now() - maxorStartTime) / 1000) - (totalBossSplits)).toFixed(2)} &6seconds due to server lag in boss`)
}).setCriteria("${message}")

const overlayTriggerWDline = register("renderWorld", () => {
    const x1 = Player.getRenderX()
    const y1 = Player.getRenderY() + 1.1
    const z1 = Player.getRenderZ()
    const x2 = ((Player.getRenderX()) - (1 * (Math.sin((Player.getYaw() + 45) * (Math.PI / 180)))))
    const y2 = Player.getRenderY() + 1.1
    const z2 = ((Player.getRenderZ()) + (1 * (Math.cos((Player.getYaw() + 45) * (Math.PI / 180)))))
    RenderLibV2.drawLine(x1, y1, z1, x2, y2, z2, 0, 1, 1, 1, false)
    if ((Player.getYaw() >= -150) && (Player.getYaw() <= -120)) {
        RenderLibV2.drawLine(x1, y1, z1, (x1 + 1), y2, z1, 0, 0, 1, 1, false)
    } if ((Player.getYaw() >= -60) && (Player.getYaw() <= -30)) {
        RenderLibV2.drawLine(x1, y1, z1, x1, y2, (z1 + 1), 0, 0, 1, 1, false)
    } if ((Player.getYaw() >= 30) && (Player.getYaw() <= 60)) {
        RenderLibV2.drawLine(x1, y1, z1, (x1 - 1), y2, z1, 0, 0, 1, 1, false)
    } if ((Player.getYaw() >= 120) && (Player.getYaw() <= 150)) {
        RenderLibV2.drawLine(x1, y1, z1, x1, y2, (z1 - 1), 0, 0, 1, 1, false)
    }
}).unregister();

const overlayTriggerWAline = register("renderWorld", () => {
    const x1 = Player.getRenderX()
    const y1 = Player.getRenderY() + 1.1
    const z1 = Player.getRenderZ()
    const x2 = ((Player.getRenderX()) - (1 * (Math.sin((Player.getYaw() - 45) * (Math.PI / 180)))))
    const y2 = Player.getRenderY() + 1.1
    const z2 = ((Player.getRenderZ()) + (1 * (Math.cos((Player.getYaw() - 45) * (Math.PI / 180)))))
    RenderLibV2.drawLine(x1, y1, z1, x2, y2, z2, 0, 1, 1, 1, false)
    if ((Player.getYaw() >= -150) && (Player.getYaw() <= -120)) {
        RenderLibV2.drawLine(x1, y1, z1, x1, y2, (z1 - 1), 0, 0, 1, 1, false)
    } if ((Player.getYaw() >= -60) && (Player.getYaw() <= -30)) {
        RenderLibV2.drawLine(x1, y1, z1, (x1 + 1), y2, z1, 0, 0, 1, 1, false)
    } if ((Player.getYaw() >= 30) && (Player.getYaw() <= 60)) {
        RenderLibV2.drawLine(x1, y1, z1, x1, y2, (z1 + 1), 0, 0, 1, 1, false)
    } if ((Player.getYaw() >= 120) && (Player.getYaw() <= 150)) {
        RenderLibV2.drawLine(x1, y1, z1, (x1 - 1), y2, z1, 0, 0, 1, 1, false)
    }
}).unregister();

function endTestSplitPos() {
    if (!indungeon == true) {
            overlayTriggerBossSplits.unregister()
            overlayTriggerPurpCountdown.unregister()
            overlayTriggerClearSplits.unregister()
            purpPadTicks = 0
    } else {
        if (!inclear == false) {
                overlayTriggerBossSplits.unregister()
        } if (!purpTimerEnabled == true) {
            overlayTriggerPurpCountdown.unregister()
        }
    }
}

register("command", () => {
    purpPadDisplay = `&a2.65`
    purpPadTicks = 2.65
    overlayTriggerBossSplits.register()
    overlayTriggerPurpCountdown.register()
    overlayTriggerClearSplits.register()
    setTimeout(() => {
        endTestSplitPos()
    }, 2000)
}).setName("testsplitpos");

register("step", () => {
    try {
        if (!Settings.strafetoggle == false) {
                WDstrafe2()
                WAstrafe()
            }
        } catch (e) {}
}).setFps(10);

/*
function WDstrafe() {
    if (Player.getPlayer().field_78900_b && Player.getPlayer().field_78902_a) {
        overlayTriggerWDline.register()
    } else {
        overlayTriggerWDline.unregister()
    }
}
*/

function WDstrafe2() {
    if (Keyboard.isKeyDown(Keyboard.KEY_W) && Keyboard.isKeyDown(Keyboard.KEY_D) && (Keyboard.isKeyDown(Keyboard.KEY_A) == false)) {
        overlayTriggerWDline.register()
    } else {
        overlayTriggerWDline.unregister()
    }
}

function WAstrafe() {
    if (Keyboard.isKeyDown(Keyboard.KEY_W) && Keyboard.isKeyDown(Keyboard.KEY_A) && (Keyboard.isKeyDown(Keyboard.KEY_D) == false)) {
        overlayTriggerWAline.register()
    } else {
        overlayTriggerWAline.unregister()
    }
}