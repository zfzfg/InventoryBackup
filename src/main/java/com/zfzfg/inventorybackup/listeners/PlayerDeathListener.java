package com.zfzfg.inventorybackup.listeners;

import com.zfzfg.inventorybackup.InventoryBackup;
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
        
        // Get cached inventory from damage listener
        PlayerDamageListener.CachedInventory cached = 
            plugin.getDamageListener().getCachedInventory(player.getUniqueId());
        
        if (cached != null) {
            // Save cached inventory (from before death)
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean saved = plugin.getInventoryManager().saveInventoryDirect(
                    player.getName(), 
                    player.getUniqueId(), 
                    cached.inventory, 
                    cached.armor, 
                    cached.offhand, 
                    cached.level,
                    cached.exp,
                    "death"
                );
                
                if (saved) {
                    // Only send message if player has permission
                    if (player.hasPermission("inventorybackup.notify")) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            String message = plugin.getMessage("inventory-saved")
                                    .replace("{player}", player.getName());
                            player.sendMessage(message);
                        });
                    }
                }
                
                // Clean up cache
                plugin.getDamageListener().removeCachedInventory(player.getUniqueId());
            });
        }
    }
}
