package com.zfzfg.inventorybackup.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * The actual contents of a backup: items, armour, offhand, level and experience.
 *
 * <p>Obtained from {@link InventoryBackupAPI#loadBackup(BackupHandle)}, or built
 * yourself with {@link #of(Player)} / the constructor when you want to store
 * something the player is not currently wearing.
 *
 * <p>The arrays are defensively copied on the way in and on the way out, so a
 * snapshot never shares state with a live inventory. Every {@link ItemStack} is also cloned.
 */
public final class BackupSnapshot {

    private final BackupHandle handle;
    private final ItemStack[] contents;
    private final ItemStack[] armor;
    private final ItemStack offhand;
    private final int level;
    private final float exp;

    /**
     * @param handle   the handle this snapshot was loaded from, or null when the
     *                 snapshot has not been written yet
     * @param contents main inventory contents, Bukkit slot order
     * @param armor    armour contents in Bukkit order (boots, leggings, chestplate, helmet)
     * @param offhand  offhand item, may be null
     * @param level    experience level
     * @param exp      progress towards the next level, 0.0 to 1.0
     */
    public BackupSnapshot(BackupHandle handle, ItemStack[] contents, ItemStack[] armor,
                          ItemStack offhand, int level, float exp) {
        this.handle = handle;
        this.contents = copy(contents);
        this.armor = copy(armor);
        this.offhand = offhand == null ? null : offhand.clone();
        this.level = level;
        this.exp = exp;
    }

    /**
     * Takes a snapshot of a player's current inventory. Must be called from the
     * main thread.
     *
     * @param player the player to read
     * @return a snapshot with no handle attached
     */
    public static BackupSnapshot of(Player player) {
        PlayerInventory inv = player.getInventory();
        return new BackupSnapshot(
                null,
                inv.getStorageContents(),
                inv.getArmorContents(),
                inv.getItemInOffHand(),
                player.getLevel(),
                player.getExp());
    }

    private static ItemStack[] copy(ItemStack[] source) {
        if (source == null) return new ItemStack[0];
        ItemStack[] result = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) result[i] = source[i] == null ? null : source[i].clone();
        return result;
    }

    /** The stored backup this came from, or null if it was built in memory. */
    public BackupHandle handle() {
        return handle;
    }

    /** Main inventory contents; a fresh copy on every call. */
    public ItemStack[] contents() {
        return copy(contents);
    }

    /** Armour contents in Bukkit order; a fresh copy on every call. */
    public ItemStack[] armor() {
        return copy(armor);
    }

    /** Offhand item, or null. */
    public ItemStack offhand() {
        return offhand == null ? null : offhand.clone();
    }

    /** Experience level. */
    public int level() {
        return level;
    }

    /** Progress towards the next level, 0.0 to 1.0. */
    public float exp() {
        return exp;
    }

    /**
     * Returns a copy of this snapshot with different items - useful inside
     * {@code InventoryRestoreEvent} when you want to filter what gets restored.
     */
    public BackupSnapshot withItems(ItemStack[] contents, ItemStack[] armor, ItemStack offhand) {
        return new BackupSnapshot(handle, contents, armor, offhand, level, exp);
    }

    /** Returns a copy of this snapshot bound to the given handle. */
    public BackupSnapshot withHandle(BackupHandle handle) {
        return new BackupSnapshot(handle, contents, armor, offhand, level, exp);
    }
}
