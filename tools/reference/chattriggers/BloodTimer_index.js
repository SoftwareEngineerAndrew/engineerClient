import Settings from "./config"

const S32PacketConfirmTransaction = Java.type("net.minecraft.network.play.server.S32PacketConfirmTransaction");

const bloodStartMessages = [
    "[BOSS] The Watcher: Things feel a little more roomy now, eh?",
    "[BOSS] The Watcher: Oh.. hello?",
    "[BOSS] The Watcher: I'm starting to get tired of seeing you around here...", 
    "[BOSS] The Watcher: You've managed to scratch and claw your way here, eh?", 
    "[BOSS] The Watcher: So you made it this far... interesting.", 
    "[BOSS] The Watcher: Ah, we meet again...", 
    "[BOSS] The Watcher: Ah, you've finally arrived.",
]

bloodStartTime = Date.now()
display = false
let bloodStartTicks = 0
let displayText = " "
let bloodMovePredictionTicks = 0

register("chat", (message) => {
    if (!bloodStartMessages.includes(message)) return
    bloodStartTime = Date.now()
    bloodStartTicks = 0
    bloodServerTicks.register()
}).setCriteria("${message}")

const bloodServerTicks = register('packetReceived', () => {
    bloodStartTicks++
}).setFilteredClass(S32PacketConfirmTransaction).unregister()

register("chat", () => {
    const bloodMove = ((Math.floor((Date.now() - bloodStartTime)/10)/100) + 0.10).toFixed(2)
    const bloodMoveTicks = (bloodStartTicks*0.05+0.1).toFixed(2)
    const bloodMoveLag = (bloodMove - bloodMoveTicks)

    if (bloodMoveTicks >= 31 && bloodMoveTicks <= 33.99) bloodMovePredictionTicks = (36 + (bloodMoveLag/2) - 0.6).toFixed(2)
    if (bloodMoveTicks >= 28 && bloodMoveTicks <= 30.99) bloodMovePredictionTicks = (33 + (bloodMoveLag/2) - 0.6).toFixed(2)
    if (bloodMoveTicks >= 25 && bloodMoveTicks <= 27.99) bloodMovePredictionTicks = (30 + (bloodMoveLag/2) - 0.6).toFixed(2)
    if (bloodMoveTicks >= 22 && bloodMoveTicks <= 24.99) bloodMovePredictionTicks = (27 + (bloodMoveLag/2) - 0.6).toFixed(2)
    if (bloodMoveTicks >= 1 && bloodMoveTicks <= 21.99) bloodMovePredictionTicks = (24 + (bloodMoveLag/2) - 0.6).toFixed(2)
    if (bloodMovePredictionTicks < 20 || bloodMovePredictionTicks > 40) bloodMovePredictionTicks = "Invalid Prediction"
    if (Settings.clientChat) ChatLib.chat(`&cMove Prediction&b: &3${bloodMovePredictionTicks} Seconds`)
    if (Settings.party) ChatLib.command("pc Blood Move Prediction: " + bloodMovePredictionTicks)
    displayText = `&3${bloodMovePredictionTicks}`
    bloodOverlay.register()
    setTimeout(() => {
        bloodOverlay.unregister()
        displayText = `&cKill Mobs`
    }, 1000)
    setTimeout(() => {
        if (Settings.clientChat) ChatLib.chat(`&cKill Blood Mobs!`)
        if (Settings.party) ChatLib.command("pc Kill Blood Mobs!")
        bloodOverlay.register()
    }, (parseFloat(((bloodMovePredictionTicks - bloodMoveTicks) * 1000) - 150).toFixed(2)))
    setTimeout(() => {
        bloodOverlay.unregister()
        bloodServerTicks.unregister()
    }, (parseFloat(((bloodMovePredictionTicks - bloodMoveTicks) * 1000) + 850).toFixed(2)))
}).setCriteria("[BOSS] The Watcher: Let's see how you can handle this.")

const bloodOverlay = register("renderOverlay", () => {
	if (!Settings.title) return
	const scale = 3
	Renderer.scale(scale)
	Renderer.drawStringWithShadow(displayText, (Renderer.screen.getWidth() / scale - Renderer.getStringWidth(displayText)) / 2, Renderer.screen.getHeight() / scale / 2 - 20)
}).unregister()

register("command", () => {
    Settings.openGUI();
}).setName("blood")