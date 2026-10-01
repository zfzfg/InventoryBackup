package com.zfzfg.inventorybackup.managers;

import com.zfzfg.inventorybackup.InventoryBackup;
import com.zfzfg.inventorybackup.api.events.BackupDeletedEvent;
import org.bukkit.scheduler.BukkitRunnable;

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
        plugin.getApiService().background(() -> plugin.getInventoryManager().cleanOldFiles())
                .thenCompose(deleted -> plugin.getApiService().mainCall(() -> {
                    plugin.getApiService().fireDeleted(deleted, BackupDeletedEvent.Reason.EXPIRED);
                    return deleted.size();
                })).exceptionally(error -> {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Backup cleanup failed", error); return 0;
                });
    }
}
