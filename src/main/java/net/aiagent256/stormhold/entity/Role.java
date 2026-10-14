package net.aiagent256.stormhold.entity;

public enum Role {
    LORD(false, 0, 200, 0.29, 4.0, 0.6, 100),
    CAPTAIN(false, 0, 60, 0.30, 3.0, 0.4, 25),
    KNIGHT(false, 0, 32, 0.30, 2.0, 0.25, 8),
    ARCHER(true, 28, 24, 0.29, 1.0, 0.0, 6),
    CROSSBOWMAN(true, 24, 26, 0.28, 1.0, 0.1, 7),
    COURIER(false, 0, 24, 0.31, 0.0, 0.0, 0);

    public final boolean ranged;
    public final int range;
    public final double health;
    public final double speed;
    public final double damage;
    public final double knockbackResist;
    public final int xp;

    Role(boolean ranged, int range, double health, double speed, double damage, double kbr, int xp) {
        this.ranged = ranged;
        this.range = range;
        this.health = health;
        this.speed = speed;
        this.damage = damage;
        this.knockbackResist = kbr;
        this.xp = xp;
    }

    public static final Role[] SOLDIERS = {LORD, CAPTAIN, KNIGHT, ARCHER, CROSSBOWMAN};
    public static final Role[] TROOPS = {CAPTAIN, KNIGHT, ARCHER, CROSSBOWMAN};

    /** Rough fighting value used for abstract battles. */
    public double strength() {
        return switch (this) {
            case LORD -> 10;
            case CAPTAIN -> 4;
            case KNIGHT -> 2;
            case CROSSBOWMAN -> 1.8;
            case ARCHER -> 1.5;
            case COURIER -> 0;
        };
    }
}
