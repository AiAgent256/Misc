package net.aiagent256.stormhold.house;

import net.minecraft.util.RandomSource;

/** Original names in a vaguely Westerosi register. */
public final class NameGen {
    private static final String[] HOUSE_A = {"Var", "Dun", "Hal", "Cor", "Thar", "Ash", "Bel", "Mor", "Gal", "Ren",
            "Os", "Tor", "Val", "Wen", "Har", "Bryn", "Cal", "Ed", "Fen", "Lor", "Mar", "Ser", "Wyl", "Rus", "Ost"};
    private static final String[] HOUSE_B = {"row", "mere", "loway", "vane", "wick", "ford", "mont", "fell", "dane",
            "rick", "ton", "worth", "more", "ley", "by", "wyn", "stead", "holt", "mark", "ard", "court", "brook"};
    private static final String[] PLACE_A = {"Raven", "Grey", "Storm", "Winter", "Iron", "Wolf", "Thorn", "Black",
            "Ash", "Frost", "High", "Stone", "Red", "Night", "Oak", "Silver", "Hollow", "Bear", "Gold", "Mist", "Crow",
            "Dun", "Elder", "Hag", "Salt", "Sea", "Sun", "Dawn", "Cold", "Wyvern", "Briar", "Kings", "Riven"};
    private static final String[] PLACE_B = {"hold", "fell", "watch", "keep", "guard", "crag", "mere", "gate",
            "spire", "rock", "wall", "haven", "ford", "burn", "march", "hall", "moot", "reach", "mount", "garth"};
    private static final String[] FIRST = {"Aldric", "Bertram", "Cedric", "Doran", "Edmund", "Garrick", "Harlan",
            "Ivor", "Lucan", "Merrick", "Osric", "Percival", "Roderick", "Severin", "Tybalt", "Ulric", "Wendel",
            "Alaric", "Godwin", "Hadric", "Leowin", "Marten", "Orrin", "Quentyn", "Rowan", "Torvald", "Willem"};

    public static String house(RandomSource r) {
        return HOUSE_A[r.nextInt(HOUSE_A.length)] + HOUSE_B[r.nextInt(HOUSE_B.length)];
    }

    public static String place(RandomSource r, int tier) {
        String n = PLACE_A[r.nextInt(PLACE_A.length)] + PLACE_B[r.nextInt(PLACE_B.length)];
        return switch (tier) {
            case 1 -> n + " Tower";
            case 2 -> n;
            default -> r.nextBoolean() ? n : n + " Castle";
        };
    }

    private static final String[] COMMON_FIRST = {"Tom", "Wat", "Hob", "Dickon", "Jankin", "Piers", "Gib", "Hodge", "Will",
            "Rob", "Alys", "Maud", "Joan", "Cecily", "Agnes", "Edith", "Mabel", "Ysolde", "Hawise", "Tilda", "Osric", "Wymond"};
    private static final java.util.Map<String, String[]> BYNAMES = java.util.Map.of(
            "farmer", new String[]{"Plowman", "Hayward", "Thresher", "Ackerman", "Sower"},
            "shepherd", new String[]{"Shepherd", "Fleece", "Hurdle", "Wether"},
            "butcher", new String[]{"Butcher", "Flesher", "Slaughter", "Lamb"},
            "fletcher", new String[]{"Fletcher", "Bowyer", "Arrowsmith", "Stringer"},
            "toolsmith", new String[]{"Smith", "Pick", "Delver", "Collier"},
            "mason", new String[]{"Mason", "Hewer", "Sawyer", "Wright"},
            "cleric", new String[]{"Priest", "Clerk", "Sexton", "Chaplain"},
            "leatherworker", new String[]{"Tanner", "Skinner", "Currier", "Glover"},
            "fisherman", new String[]{"Fisher", "Netter", "Boatwright", "Eeler"},
            "none", new String[]{"Cotter", "Carter", "Tiler", "Thatcher", "Miller", "Webster", "Dyer", "Cooper", "Chandler"});

    /** A commoner's name: a plain first name and a byname from the trade. */
    public static String commoner(RandomSource r, String profession) {
        String[] by = BYNAMES.getOrDefault(profession, BYNAMES.get("none"));
        return COMMON_FIRST[r.nextInt(COMMON_FIRST.length)] + " " + by[r.nextInt(by.length)];
    }

    public static String first(RandomSource r) {
        return FIRST[r.nextInt(FIRST.length)];
    }

    private NameGen() {
    }
}
