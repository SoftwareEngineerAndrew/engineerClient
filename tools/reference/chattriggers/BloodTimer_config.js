import { @Vigilant, @TextProperty, @NumberProperty, @SwitchProperty, @SelectorProperty, @DecimalSliderProperty } from "../Vigilance";

@Vigilant("BloodTimer", "§cBlood Timer", {
	getCategoryComparator: () => (a, b) => {
		const categories = ["General"];
		return categories.indexOf(a.name) - categories.indexOf(b.name);
	}
})

class Settings {
    constructor() {
        this.initialize(this);
    }

	@SwitchProperty({name: "Chat Notifications",
		description: "Sends a message in only your chat when to kill.",
		category: "General"}) clientChat = true;

	@SwitchProperty({name: "Kill Title",
		description: "Shows a title on screen when to kill.",
		category: "General"}) title = false;

    @SwitchProperty({name: "Party Announce",
        description: "Tells your party when to kill.",
        category: "General"}) party = false;
}

export default new Settings;