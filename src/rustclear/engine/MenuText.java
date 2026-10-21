package net.rustclear.mod.engine;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import net.minecraft.world.item.ItemStack;

/**
 * Hypixel's menu text and icons: Roman numerals, progress bars, short numbers ("4.9M", "281.1k"),
 * and the player-head icons of the SkyBlock menus (texture ids from the recordings, rebuilt
 * without the uploader's profile so no player name ships).
 */
final class MenuText {
    private MenuText() {
    }

    /** The head icons, by the menu item they draw (menus-skyblock.md §1, §2, §7). */
    static final String STATS = "70630eeb759365fdd7e6cbdd7553625fa36b20f081398a6006ae2a7ac964bcf3";
    static final String LEVELING = "3255327dd8e90afad681a19231665bea2bd06065a09d77ac1408837f9e0b242";
    static final String BAGS = "1a11a7f11bcd5784903c5201d08261c4df8379109d6e611c1cd3ededf031afed";
    static final String BANK = "e36e94f6c34a35465fce4a90f2e25976389eb9709a12273574ff70fd4daa6852";
    static final String FAST_TRAVEL = "35f4b40cef9e017cd4112d26b62557f8c1d5b189da2e99534222bc8cec7d9196";
    static final String DUNGEONEERING = "9b56895b9659896ad647f58599238af532d46db9c1b0389b8bbeb70999dab33d";
    static final String CATACOMBS = "17b8ef7e8e0af19399cc32485f2ee47b2bb4d5802c71b3021f4d5d6b5f3afe03";
    static final String ESSENCE = "1e5574a4ef94ef4ca300d02f644d98be07dbfba7df78808e8a9a021b067d996d";
    static final String RNG_METERS = "9b56895b9659896ad647f58599238af532d46db9c1b0389b8bbeb70999dab33d";
    static final String SACK_OF_SACKS = "80a077e248d142772ea800864f8c578b9d36885b29daf836b64a706882b6ec10";
    static final String POTION_BAG = "2fc626b3a12d099c44da04b5f15c95f2e360e0789bccecf842ef09c72b93d842";
    static final String QUIVER = "44e1843df8f91a7033e4c810f10b003e07ade5100079ac44df74a4e46a489447";
    static final String ACCESSORY_BAG = "1a11a7f11bcd5784903c5201d08261c4df8379109d6e611c1cd3ededf031afed";
    static final String TIME_POCKET = "d7fbeabd0e91bc4fece5026441df08312f0de4f333739724ae256ea67211bade";
    static final String DUNGEON_SACK = "fb96c585ccd35f073da38d165cb9bb18ff136f1a184eee3f44725354640ebbd4";
    /** F1-F6 then M1-M7 RNG meters (F7's icon is a wither skeleton skull). */
    static final String[] RNG = {
        "12716ecbf5b8da00b05f316ec6af61e8bd02805b21eb8e440151468dc656549c", "7de7bbbdf22bfe17980d4e20687e386f11d59ee1db6f8b4762391b79a5ac532d",
        "9971cee8b833a62fc2a612f3503437fdf93cad692d216b8cf90bbb0538c47dd8", "8b6a72138d69fbbd2fea3fa251cabd87152e4f1c97e5f986bf685571db3cc0",
        "c1007c5b7114abec734206d4fc613da4f3a0e99f71ff949cedadc99079135a0b", "fa06cb0c471c1c9bc169af270cd466ea701946776056e472ecdaeb49f0f4a4dc",
        "e2af226c4e2444e8cecd03824de14367df3813d19592cd3779059d9e4decc8c", "4bfb891e118bae7286ca4b09f30200372b9e7898ade7056dd17859424d1b5e79",
        "3ac2e58f3beb5195e57e0bc76b0417b152c90a1d4c0e06059701468191415086", "875758f7b09a721951ac57bd180d0b09eb9918c1219c8db2e8405e5b916489b1",
        "fc3c88da2007b4b6bfb4b26af04312450b0f2611ec1612953d1dc87a508d454b", "673344a1f574ddb8263977dcbfe7da5be901db90148caee3a6f1ebd5e8dc7ebb",
        "4a87c66e4cb6badac204a5f6efec7097e7bf1b0e198951756a6a4e6b73fc8b45"};
    /** Catacombs Profile: Entrance, Floor I-VII, then Master Mode Floor I-VII. */
    static final String[] FLOORS = {
        "5a2f67500a65f3ce79d34ec150de93df8f60ebe52e248f5e1cdb69b0726256f7", "5720917cda0567442617f2721e88be9d2ffbb0b26a3f4c2fe21655814d4f4476",
        "5720917cda0567442617f2721e88be9d2ffbb0b26a3f4c2fe21655814d4f4476", "3ce69d2ddcc81c9fc2e9948c92003eb0f7ebf0e7e952e801b7f2069dcee76d85",
        "76965e3fd619de6b0a7ce1673072520a9360378e1cb8c19d4baf0c86769d3764", "33a0246d51350b770de27f1a1925b11cece57b3c9b209e2b6bf275d32d0154c3",
        "a1a81f9b0a1375681ae7d9df65e4d3723b1f0fa7443e76dd3bc63dd3864ff343", "6a40675d333adcca75b35b6ba29b4fd7e5eaeb998395295385b494128715dab3",
        "1eb5b21af330af122b268b7aa390733bd1b699b4d0923233ecd24f81e08b9bce", "32292e4e0fa62667256ef8da0f01982a996499f4d5d894bd058c3e6f3d2fb2d9",
        "c969f6b148648aa8d027228a52fb5a3ca1ee84dc76e47851f14db029a730a8a3", "d7b69021f9c09647dfd9b34df3deaff70cfc740f6a26f612dd47503fc34c97f0",
        "d65cbce40e60e7a59a87fa8f4ecb6ccfc1514338c262614bf33739a6263f5405", "ac3d45ff672988b46358137be32c3c9adfd9a629195c2b64814a93499dd6606",
        "f0c687a2026b69344b5addbbefa381d154d6bc80c8c01745311bfbbf8bbf009c"};

    /** A player head wearing the skin with texture id [texture], named and with lore. */
    static ItemStack head(String texture, String name, String... lore) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + texture + "\"}}}";
        String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return EngineItems.playerHead(EngineItems.profile("menu", value, null), name, lore);
    }

    private static final int[] ROMAN_VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] ROMAN = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

    static String roman(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ROMAN_VALUES.length && n > 0; i++) {
            while (n >= ROMAN_VALUES[i]) {
                sb.append(ROMAN[i]);
                n -= ROMAN_VALUES[i];
            }
        }
        return sb.isEmpty() ? "0" : sb.toString();
    }

    /**
     * Hypixel's progress bar: 25 struck-through spaces, the filled share (rounded up) in [fill] (§2
     * green, §3 leveling, §d RNG), the rest white, then the numbers ("§e77§6/§e90").
     */
    static String bar(double have, double need, String fill, String numbers) {
        int filled = need <= 0 ? 25 : (int) Math.min(25, Math.ceil(25 * Math.max(0, have) / need - 1e-9));
        StringBuilder sb = new StringBuilder();
        if (filled > 0) {
            sb.append(fill).append("§l§m").append(" ".repeat(filled));
        }
        if (filled < 25) {
            sb.append("§f§l§m").append(" ".repeat(25 - filled));
        }
        return sb.append("§r ").append(numbers).toString();
    }

    /** [have] of [need] as a bar with "§e<have>§6/§e<need short>". */
    static String bar(double have, double need, String fill) {
        return bar(have, need, fill, "§e" + decimal(have) + "§6/§e" + shortNumber(need));
    }

    /** 4.9M, 281.1k, 200M, 1k, 900: one decimal at most, trailing .0 dropped. */
    static String shortNumber(double value) {
        if (value >= 1_000_000_000) {
            return trim(value / 1_000_000_000) + "B";
        }
        if (value >= 1_000_000) {
            return trim(value / 1_000_000) + "M";
        }
        if (value >= 1_000) {
            return trim(value / 1_000) + "k";
        }
        return trim(value);
    }

    private static String trim(double value) {
        double r = Math.floor(value * 10 + 1e-9) / 10;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.format(Locale.ROOT, "%.1f", r);
    }

    /** 26,416,490.2 or 58,426,911: thousands separated, one decimal when there is one. */
    static String decimal(double value) {
        double r = Math.round(value * 10) / 10.0;
        return r == Math.rint(r) ? String.format(Locale.ROOT, "%,d", (long) r) : String.format(Locale.ROOT, "%,.1f", r);
    }

    /** 1,077.26 or 28.35 or 5: two decimals at most, trailing zeros dropped (stat lines). */
    static String decimal2(double value) {
        double r = Math.round(value * 100) / 100.0;
        String out = String.format(Locale.ROOT, "%,.2f", r);
        return out.contains(".") ? out.replaceAll("0+$", "").replaceAll("\\.$", "") : out;
    }

    /** A percent as Hypixel prints progress: one decimal, trailing .0 dropped (54.4%, 64%, 4,163.7%). */
    static String percent(double value) {
        return decimal(value);
    }
}
