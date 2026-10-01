package com.zfzfg.inventorybackup.listeners;

import com.zfzfg.inventorybackup.InventoryBackup;
import com.zfzfg.inventorybackup.api.BackupRequest;
import com.zfzfg.inventorybackup.api.BackupSnapshot;
import com.zfzfg.inventorybackup.api.BackupType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class PlayerDeathListener implements Listener {

    private final InventoryBackup plugin;

    public PlayerDeathListener(InventoryBackup plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("save-on-death", true)) {
            return;
        }

        Player player = event.getEntity();

        BackupSnapshot snapshot = BackupSnapshot.of(player);
        // A fresh pre-damage snapshot is a fallback only if another plugin has already cleared the inventory.
        PlayerDamageListener.CachedInventory cached = plugin.getDamageListener().getCachedInventory(player.getUniqueId());
        boolean empty = java.util.Arrays.stream(snapshot.contents()).allMatch(item -> item == null || item.getType().isAir())
                && java.util.Arrays.stream(snapshot.armor()).allMatch(item -> item == null || item.getType().isAir())
                && (snapshot.offhand() == null || snapshot.offhand().getType().isAir());
        if (empty && cached != null && System.currentTimeMillis() - cached.timestamp <= 1000)
            snapshot = new BackupSnapshot(null, cached.inventory, cached.armor, cached.offhand, cached.level, cached.exp);

        // Routed through the API service rather than straight to the manager, so
        // a death backup fires the same BackupCreateEvent a plugin-made one does
        // and can be vetoed the same way.
        plugin.getApiService()
                .createBackup(player.getUniqueId(), player.getName(), snapshot,
                        BackupRequest.of(BackupType.DEATH, plugin))
                .thenAccept(handle -> {
                    if (plugin.getConfig().getBoolean("notify-on-backup", true)
                            && (!plugin.getConfig().getBoolean("notify-ops-only", true) || player.isOp())
                            && handle.isPresent() && player.isOnline()
                            && player.hasPermission("inventorybackup.notify")) {
                        player.sendMessage(plugin.getMessage("inventory-saved",
                                "player", player.getName()));
                    }
                }).exceptionally(error -> { plugin.getLogger().log(java.util.logging.Level.SEVERE, "Death backup failed", error); return null; });

        plugin.getDamageListener().removeCachedInventory(player.getUniqueId());
    }
}
