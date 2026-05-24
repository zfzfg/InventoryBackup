package com.zfzfg.inventorybackup;

import com.zfzfg.inventorybackup.commands.InventoryCommand;
import com.zfzfg.inventorybackup.listeners.PlayerDeathListener;
import com.zfzfg.inventorybackup.listeners.PlayerDamageListener;
import com.zfzfg.inventorybackup.managers.FileCleanupTask;
import com.zfzfg.inventorybackup.managers.InventoryManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class InventoryBackup extends JavaPlugin {
    
    private InventoryManager inventoryManager;
    private FileCleanupTask cleanupTask;
    private PlayerDamageListener damageListener;
    
    @Override
    public void onEnable() {
        // Create plugin folder and inventories subfolder
        saveDefaultConfig();
        
        File inventoriesFolder = new File(getDataFolder(), "inventories");
        if (!inventoriesFolder.exists()) {
            inventoriesFolder.mkdirs();
        }
        
        // Initialize managers
        inventoryManager = new InventoryManager(this);
        
        // Register listeners
        damageListener = new PlayerDamageListener(this);
        getServer().getPluginManager().registerEvents(damageListener, this);
        getServer().getPluginManager().registerEvents(new PlayerDeathListener(this), this);
        
        // Register commands
        if (getCommand("inv") != null) {
            InventoryCommand invCmd = new InventoryCommand(this);
            getCommand("inv").setExecutor(invCmd);
            getCommand("inv").setTabCompleter(invCmd);
        }
        
        // Start cleanup task
        startCleanupTask();
        
        // Clean damage cache periodically (default 30 seconds)
        int cacheIntervalSeconds = getConfig().getInt("cache-cleanup-interval", 30);
        long cacheIntervalTicks = cacheIntervalSeconds * 20L;
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (damageListener != null) {
                damageListener.cleanOldCache();
            }
        }, cacheIntervalTicks, cacheIntervalTicks);
        
        getLogger().info("InventoryBackup has been enabled!");
    }
    
    @Override
    public void onDisable() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        getLogger().info("InventoryBackup has been disabled!");
    }
    
    private void startCleanupTask() {
        int autoDays = getConfig().getInt("auto-delete-days", 30);
        
        if (autoDays > 0) {
            int intervalHours = getConfig().getInt("cleanup-interval-hours", 24);
            long intervalTicks = intervalHours * 60 * 60 * 20L; // Convert hours to ticks
            
            cleanupTask = new FileCleanupTask(this);
            cleanupTask.runTaskTimerAsynchronously(this, intervalTicks, intervalTicks);
            
            getLogger().info("File cleanup task started. Will run every " + intervalHours + " hours.");
        } else {
            getLogger().info("Auto-deletion is disabled.");
        }
    }

    public PlayerDamageListener getDamageListener() {
        return damageListener;
    }
    
    public InventoryManager getInventoryManager() {
        return inventoryManager;
    }
    
    public String getMessage(String path) {
        String prefix = getConfig().getString("messages.prefix", "&8[&6InvBackup&8]&r");
        String message = getConfig().getString("messages." + path, path);
        return colorize(prefix + " " + message);
    }
    
    public String colorize(String message) {
        return message.replace("&", "§");
    }
}
