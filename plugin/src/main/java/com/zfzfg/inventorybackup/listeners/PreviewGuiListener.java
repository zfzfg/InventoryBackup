package com.zfzfg.inventorybackup.listeners;

import com.zfzfg.inventorybackup.gui.PreviewHolder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Keeps the backup preview read-only.
 *
 * <p>The preview window holds real item stacks that belong to no container, so
 * anything a viewer manages to drag out of it is a duplicate entering the world.
 * Every interaction with the window is therefore cancelled outright - including
 * shift-clicks from the player's own inventory, which would otherwise move items
 * <i>into</i> the preview and lose them when it closes.
 */
public class PreviewGuiListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (isPreview(event.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (isPreview(event.getInventory())) {
            event.setCancelled(true);
        }
    }

    /**
     * Matches on the holder rather than the title: titles are translatable and
     * get truncated at 32 characters, so a long player name must not be able to
     * turn the protection off.
     */
    private static boolean isPreview(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof PreviewHolder;
    }
}
