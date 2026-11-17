package com.engineerclient.skyblock

internal class Ench(val ultimate: Boolean, val good: Int, val max: Int)

// Name=<good><max>: good is the usual level, u marks an ultimate, X is 10.
private const val TABLE =
    "Absorb=0X;Angler=56;Aqua Affinity=11;Bane of Arthropods=57;Bank=u5;Big Brain=55;Blast Protection=57;" +
    "Blessing=56;Bobbin' Time=u5;Bug Blender=15;Caster=56;Cayenne=45;Champion=0X;Chance=35;Charm=46;Chimera=u5;" +
    "Cleave=56;Combo=u5;Compact=0X;Corruption=45;Counter-Strike=05;Critical=57;Crop Fever=u5;Cubism=56;" +
    "Cultivating=0X;Dedication=34;Delicate=05;Depth Strider=33;Divine Gift=33;Dragon Tracer=55;Drain=35;Duplex=u5;" +
    "Efficiency=5X;Ender Slayer=57;Execute=56;Experience=35;Expertise=0X;Fatal Tempo=u5;Feast=15;" +
    "Feather Falling=5X;Fire Aspect=23;Fire Protection=57;First Impression=u5;First Strike=45;Flame=22;Flash=u5;" +
    "Flowstate=u3;Forest Pledge=36;Fortune=34;Frail=57;Giant Killer=57;Gravity=56;Great Spook=01;Green Thumb=05;" +
    "Growth=57;Habanero Tactics=u5;Hardened Vitality=5X;Harvesting=56;Hecatomb=0X;Ice Cold=05;Impaling=55;" +
    "Inferno=u5;Infinite Quiver=5X;Karma=16;Knockback=22;Lapidary=05;Last Stand=u5;Legion=u5;Lethality=56;" +
    "Life Steal=35;Looting=35;Luck=57;Luck of the Sea=57;Lure=56;Magnet=56;Mana Steal=03;Missile=u5;" +
    "No Pain No Gain=u5;One For All=u1;Overload=05;Paleontologist=05;Pesterminator=46;Petalfall=15;Piercing=11;" +
    "Piscary=57;Power=57;Prismatic=05;Projectile Protection=57;Prosecute=56;Prosperity=05;Protection=57;Punch=22;" +
    "Pyroclasm=56;Quantum=35;Quick Bite=05;Rainbow=13;Reflection=05;Refrigerate=u5;Rejuvenate=05;Rend=u5;" +
    "Replenish=11;Respiration=34;Respite=55;Scavenger=36;Scuba=36;Sharpness=57;Silk Touch=11;Small Brain=35;" +
    "Smarty Pants=05;Smelting Touch=11;Smite=57;Smoldering=45;Snipe=34;Soul Eater=u5;Spiked Hook=57;Stealth=06;" +
    "Strong Vitality=5X;Sugar Rush=03;Sunset=u5;Swarm=u5;Tabasco=23;The One=u5;Thorns=34;Thunderbolt=57;" +
    "Thunderlord=57;Tidal=23;Titan Killer=57;Toxophilite=0X;Transylvanian=35;Triple-Strike=45;True Protection=01;" +
    "Turbo-Cacti=07;Turbo-Cane=07;Turbo-Carrot=07;Turbo-Cocoa=07;Turbo-Melon=07;Turbo-Moonflower=07;" +
    "Turbo-Mushrooms=07;Turbo-Potato=07;Turbo-Pumpkin=07;Turbo-Rose=07;Turbo-Sunflower=07;Turbo-Warts=07;" +
    "Turbo-Wheat=07;Ultimate Jerry=u5;Ultimate Wise=u5;Vampiric Vitality=5X;Vampirism=56;Venomous=57;Vicious=25;" +
    "Vivacious Vitality=5X;Wisdom=u5;Woodsplitter=56"

private val byKey: HashMap<String, Ench> by lazy(LazyThreadSafetyMode.NONE) {
    val out = HashMap<String, Ench>(256)
    for (entry in TABLE.split(';')) {
        val eq = entry.indexOf('=')
        val g = entry[eq + 1]
        val m = entry[eq + 2]
        out[entry.substring(0, eq).lowercase().replace("'", "")] =
            Ench(g == 'u', if (g == 'u') 0 else g - '0', if (m == 'X') 10 else m - '0')
    }
    out
}

internal fun enchantInfo(key: String): Ench? = byKey[key]
