

register("command", () => { 
    const main = 
    [
        { name: `&nSplits`, command: "/eSplits", hoverText: "/eSplits" },
        { name: `&nTerms`, command: "/eTerms", hoverText: "/eTerms" },
        { name: `&nMisc`, command: "/eMisc", hoverText: "/eMisc" },
    ];
    ChatLib.chat(`                        
                    &cEngineer&bClient
                     &7Click to open`) 
    main.forEach(({ name, command, hoverText }) => {new TextComponent(`\n         &5${name}`).setHover("show_text", hoverText).setClick("run_command", command).chat();});    
}).setName("EngineerClient").setAliases("e", "en", "eng", "engineer", "engineering", "engineeringitup", "enginerclient").setTabCompletions("help", "rat")

register("command", () => { 
    const splits = 
    [
        { name: `&nSplits`, command: "/Splits", hoverText: "/Splits" },
        { name: `&nSubSplits`, command: "/SubSplits", hoverText: "/SubSplits" },
        { name: `&nPurplePadTimer`, command: "/PurplePadTimer", hoverText: "/PurplePadTimer" },
    ];
    ChatLib.chat(`\n  &5&nSplits`)  
    splits.forEach(({ name, command, hoverText }) => {new TextComponent(`\n       &3${name}`).setHover("show_text", hoverText).setClick("run_command", command).chat();});   
}).setName("eSplits")

register("command", () => { 
    const terms = 
    [
        { name: `&nCoreMessage`, command: "/CoreMessage", hoverText: "/CoreMessage" },
        { name: `&nEEAlert`, command: "/EEAlert", hoverText: "/EEAlert" },
        { name: `&nEEMove`, command: "/EEMove", hoverText: "/EEMove" },
        { name: `&nTermInfo`, command: "/TermInfo", hoverText: "/TermInfo" },
        { name: `&nSectionTimes`, command: "/SectionTimes", hoverText: "/SectionTimes" },
    ];
    ChatLib.chat(`\n  &5&nTerminals`)  
    terms.forEach(({ name, command, hoverText }) => {new TextComponent(`\n       &3${name}`).setHover("show_text", hoverText).setClick("run_command", command).chat();});   
}).setName("eTerms")

register("command", () => { 
    const misc = 
    [
        { name: `&nChatCleaner`, command: "/ChatCleaner", hoverText: "/ChatCleaner" },
        { name: `&nMasks`, command: "/Masks", hoverText: "/masks" },
        { name: `&nSheepHider`, command: "/SheepHider", hoverText: "/SheepHider" },
    ];
    ChatLib.chat(`\n  &5&nMisc`)  
    misc.forEach(({ name, command, hoverText }) => {new TextComponent(`\n       &3${name}`).setHover("show_text", hoverText).setClick("run_command", command).chat();});  
}).setName("eMisc")

register("command", () =>  ChatLib.chat("  &00 &11 &22 &33 &44 &55 &66 &77 &88 &99 &aa &bb &cc &dd &ee &7&ll &nn")).setName("colors").setAliases("color", "colorr", "c")

// auto superboom (cheat)
// register("chat",()=>{const boomStack=Player.getInventory().getItems().find(a=>a?.getName()=="§9Superboom TNT")
// if(!boomStack)returnChatLib.command(`gfs superboom_tnt 64`,false);const toGiveBoom=64-boomStack.getStackSize()
// if(toGiveBoom==0)return;if(toGiveBoom!=0)ChatLib.command(`gfs superboom_tnt ${toGiveBoom}`,false) }).setCriteria
// ("Starting in 2 seconds.");