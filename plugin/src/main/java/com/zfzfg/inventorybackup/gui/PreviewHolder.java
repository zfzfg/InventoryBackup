package com.zfzfg.inventorybackup.gui;

import com.zfzfg.inventorybackup.api.BackupHandle;
import com.zfzfg.inventorybackup.api.BackupSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * The read-only backup preview window.
 *
 * <p>The preview shows real {@link ItemStack}s that are not backed by any
 * container, so every click has to be cancelled -- otherwise a viewer could pull
 * duplicated items out of an archived inventory. {@code PreviewGuiListener}
 * recognises the window by this holder; going through the holder rather than the
 * title means a renamed or truncated title can never disable the protection.
 *
 * <p>Layout: main inventory in slots 0-35, armour at 45-48 (helmet first), the
 * offhand item at 49.
 */
public final class PreviewHolder implements InventoryHolder {

    /** Slots of the preview that mirror the player's main inventory. */
    public static final int MAIN_SLOTS = 36;

    private static final int SIZE = 54;
    private static final int ARMOR_START = 45;
    private static final int OFFHAND_SLOT = 49;

    private final BackupHandle handle;
    private final Inventory inventory;

    private PreviewHolder(BackupHandle handle, String title) {
        this.handle = handle;
        this.inventory = Bukkit.createInventory(this, SIZE, title);
    }

    /**
     * Builds the preview window for a snapshot. Main thread only.
     *
     * @param snapshot the backup contents to display
     * @param title    the window title, already coloured and truncated
     * @return the filled inventory, ready to be opened
     */
    public static Inventory build(BackupSnapshot snapshot, String title) {
        PreviewHolder holder = new PreviewHolder(snapshot.handle(), title);
        Inventory gui = holder.inventory;

        ItemStack[] contents = snapshot.contents();
        for (int i = 0; i < contents.length && i < MAIN_SLOTS; i++) {
            if (contents[i] != null) {
                gui.setItem(i, contents[i]);
            }
        }

        ItemStack[] armor = snapshot.armor();
        if (armor.length >= 4) {
            gui.setItem(ARMOR_START, armor[3]);     // Helmet
            gui.setItem(ARMOR_START + 1, armor[2]); // Chestplate
            gui.setItem(ARMOR_START + 2, armor[1]); // Leggings
            gui.setItem(ARMOR_START + 3, armor[0]); // Boots
        }

        if (snapshot.offhand() != null) {
            gui.setItem(OFFHAND_SLOT, snapshot.offhand());
        }

        return gui;
    }

    /** The backup being previewed; may be null for an unsaved snapshot. */
    public BackupHandle getBackupHandle() {
        return handle;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
