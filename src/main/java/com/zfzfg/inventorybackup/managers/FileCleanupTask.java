package com.zfzfg.inventorybackup.managers;

import com.zfzfg.inventorybackup.InventoryBackup;
import org.bukkit.scheduler.BukkitRunnable;

public class FileCleanupTask extends BukkitRunnable {
    
    private final InventoryBackup plugin;
    
    public FileCleanupTask(InventoryBackup plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public void run() {
        plugin.getLogger().info("Running scheduled inventory cleanup...");
        
        int deletedCount = plugin.getInventoryManager().cleanOldFiles();
        
        if (deletedCount > 0) {
            plugin.getLogger().info("Cleaned up " + deletedCount + " old inventory files.");
        } else {
            plugin.getLogger().info("No old inventory files to clean up.");
        }
    }
}
