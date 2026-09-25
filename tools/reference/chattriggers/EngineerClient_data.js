
const pathSymbol = Symbol("path")
class PogObject {
    constructor(module, defaultObject = {}, fileName) {
        this[pathSymbol] = [module, fileName]
        let data = FileLib.read(module, fileName)
        try {
            data = data ? JSON.parse(data) : {}
        } catch (e) {
            console.error(e)
            console.log(`[PogData] Reset ${module} to default data`)
            data = {}
        }
        Object.assign(this, defaultObject, data)
    }

    save() {
        FileLib.write(
            this[pathSymbol][0],
            this[pathSymbol][1],
            JSON.stringify(this, null, 4),
            true
        )
    }
} 
// I LOVE HAVING ZERO DEPENDECIES 


const data = new PogObject("EngineerClient", {
    splits: {
        toggled: false, 
        x: "332",
        y: "21", 
        scale: "21", 
        onWorldLoad: false,
        open: "15.5", blood: "63.5", portal: "4.5", maxor: "25.5", 
        storm: "45.5", terms: "32", goldor: "6.5", necron: "30.3", animation: "4.2"
    },
    subSplits: { 
        toggled: false, 
        x: "200", 
        y: "500", 
        scale: "1", 
        onWorldLoad: false 
    },
    purpPad: { 
        toggled: false, 
        x: "500", 
        y: "500", 
        scale: "1",
        color: "&a"
    },
    masks: { 
        toggled: false, 
        x: "200",
        y: "500",
        scale: "2"
    },
    speed: { 
        toggled: false,
        x: "200", 
        y: "500",
        scale: "1",
        hide500: "1"
    },
    chatCleaner: { 
        toggled: false,
        friendJoinMessages: false,
        partyChat: false,
        dungeonSpam: false,
        randomMessages: false,
        watcherMessages: false,
        bossMessages: false
    },
    sheepHider: { 
        toggled: false,
        distance: "4"
    },
    eeAlert: { 
        toggled: false,
        x: "200", 
        y: "500",
        scale: "1"
    },
    eeMove: { 
        toggled: false,
        x: "200", 
        y: "500",
        scale: "1"
    },
    termInfo: { 
        toggled: false,
        x: "200", 
        y: "500",
        scale: "1",
        totalTerms: false,
        simple: false,
        i4: false,
        i2: false,
        i3: false,
    },
    sectionTimes: { 
        toggled: false,
        x: "200", 
        y: "500",
        scale: "1",
        color: "&3",
        showTime: "1"
    },
}, "datafile.json") 



export default data