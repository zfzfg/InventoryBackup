package com.zfzfg.inventorybackup.managers;

import com.zfzfg.inventorybackup.InventoryBackup;
import com.zfzfg.inventorybackup.api.BackupHandle;
import com.zfzfg.inventorybackup.api.events.BackupDeletedEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

/**
 * Ages out backups older than {@code auto-delete-days}.
 *
 * <p>Only scheduled when auto-deletion is switched on. Runs asynchronously, so
 * the {@code BackupDeletedEvent}s it produces are handed back to the main thread
 * before firing.
 */
public class FileCleanupTask extends BukkitRunnable {

    private final InventoryBackup plugin;

    public FileCleanupTask(InventoryBackup plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        plugin.getLogger().info(plugin.getLanguageManager().getConsoleMsg("cleanup-running"));

        List<BackupHandle> deleted = plugin.getInventoryManager().cleanOldFiles();

        if (deleted.isEmpty()) {
            plugin.getLogger().info(plugin.getLanguageManager().getConsoleMsg("cleanup-nothing"));
        } else {
            plugin.getLogger().info(plugin.getLanguageManager().getConsoleMsg(
                    "cleanup-finished", "count", String.valueOf(deleted.size())));
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getApiService().fireDeleted(deleted, BackupDeletedEvent.Reason.EXPIRED));
        }
    }
}
