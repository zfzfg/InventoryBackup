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

        // The inventory is already gone by the time death fires, so what gets
        // stored is the snapshot PlayerDamageListener took just before the
        // killing blow.
        PlayerDamageListener.CachedInventory cached =
            plugin.getDamageListener().getCachedInventory(player.getUniqueId());

        if (cached == null) {
            return;
        }

        BackupSnapshot snapshot = new BackupSnapshot(null,
                cached.inventory, cached.armor, cached.offhand, cached.level, cached.exp);

        // Routed through the API service rather than straight to the manager, so
        // a death backup fires the same BackupCreateEvent a plugin-made one does
        // and can be vetoed the same way.
        plugin.getApiService()
                .createBackup(player.getUniqueId(), player.getName(), snapshot,
                        BackupRequest.of(BackupType.DEATH, plugin))
                .thenAccept(handle -> {
                    if (handle.isPresent() && player.isOnline()
                            && player.hasPermission("inventorybackup.notify")) {
                        player.sendMessage(plugin.getMessage("inventory-saved",
                                "player", player.getName()));
                    }
                });

        plugin.getDamageListener().removeCachedInventory(player.getUniqueId());
    }
}
