package com.zfzfg.inventorybackup.api;

/**
 * Controls what a restore actually writes back to the player.
 *
 * <p>{@link #all()} restores everything and is what {@code /inv <player> restore}
 * uses. Pick the parts you want when you only care about some of them:
 *
 * <pre>{@code
 * RestoreOptions options = RestoreOptions.builder()
 *         .contents(true).armor(true)
 *         .level(false).exp(false)   // leave XP alone
 *         .build();
 * }</pre>
 */
public final class RestoreOptions {

    private static final RestoreOptions ALL = builder().build();

    private final boolean contents;
    private final boolean armor;
    private final boolean offhand;
    private final boolean level;
    private final boolean exp;
    private final boolean clearBefore;
    private final boolean dropOverflow;

    private RestoreOptions(Builder builder) {
        this.contents = builder.contents;
        this.armor = builder.armor;
        this.offhand = builder.offhand;
        this.level = builder.level;
        this.exp = builder.exp;
        this.clearBefore = builder.clearBefore;
        this.dropOverflow = builder.dropOverflow;
    }

    /** Restore everything, replacing whatever the player is carrying. */
    public static RestoreOptions all() {
        return ALL;
    }

    /** Restore items only, leaving level and experience untouched. */
    public static RestoreOptions itemsOnly() {
        return builder().level(false).exp(false).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean contents() {
        return contents;
    }

    public boolean armor() {
        return armor;
    }

    public boolean offhand() {
        return offhand;
    }

    public boolean level() {
        return level;
    }

    public boolean exp() {
        return exp;
    }

    /**
     * Whether the player's current inventory is emptied first. With this off,
     * restored items are added to the existing inventory instead of replacing it.
     */
    public boolean clearBefore() {
        return clearBefore;
    }

    /** Whether items that no longer fit are dropped at the player's feet. */
    public boolean dropOverflow() {
        return dropOverflow;
    }

    /** True if this would not write anything at all. */
    public boolean isNoop() {
        return !contents && !armor && !offhand && !level && !exp;
    }

    /** Starts a builder pre-filled with this instance's values. */
    public Builder toBuilder() {
        return new Builder()
                .contents(contents).armor(armor).offhand(offhand)
                .level(level).exp(exp)
                .clearBefore(clearBefore).dropOverflow(dropOverflow);
    }

    @Override
    public String toString() {
        return "RestoreOptions{contents=" + contents + ", armor=" + armor
                + ", offhand=" + offhand + ", level=" + level + ", exp=" + exp
                + ", clearBefore=" + clearBefore + ", dropOverflow=" + dropOverflow + "}";
    }

    public static final class Builder {

        private boolean contents = true;
        private boolean armor = true;
        private boolean offhand = true;
        private boolean level = true;
        private boolean exp = true;
        private boolean clearBefore = true;
        private boolean dropOverflow = true;

        public Builder contents(boolean contents) {
            this.contents = contents;
            return this;
        }

        public Builder armor(boolean armor) {
            this.armor = armor;
            return this;
        }

        public Builder offhand(boolean offhand) {
            this.offhand = offhand;
            return this;
        }

        public Builder level(boolean level) {
            this.level = level;
            return this;
        }

        public Builder exp(boolean exp) {
            this.exp = exp;
            return this;
        }

        public Builder clearBefore(boolean clearBefore) {
            this.clearBefore = clearBefore;
            return this;
        }

        public Builder dropOverflow(boolean dropOverflow) {
            this.dropOverflow = dropOverflow;
            return this;
        }

        public RestoreOptions build() {
            return new RestoreOptions(this);
        }
    }
}
