package dev.sgsim.sim;

/** How the player actually obtains a part made of a given material on this server. */
public enum PartSource {
    /** Crafting-grid part recipe: material and grade of every unit are free choices. */
    CRAFTING(false),
    /** A recipe whose output is a finished part with a fixed material list (e.g. foundry casting). */
    FIXED_RECIPE(true),
    /** The item itself counts as the part (Silent Gear "part substitute", e.g. a blaze rod as a rod). */
    SUBSTITUTE(true),
    /**
     * Silent Gear allows the material in this part, but no recipe that names it was found. Usually a
     * machine recipe that picks the material while it runs (e.g. Metalworks casting from a fluid).
     */
    OTHER(false);

    /** Grades cannot be chosen: the part comes out exactly as the route makes it. */
    public final boolean gradeLocked;

    PartSource(boolean gradeLocked) {
        this.gradeLocked = gradeLocked;
    }
}
