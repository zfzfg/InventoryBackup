package com.zfzfg.inventorybackup.listeners;

import com.zfzfg.inventorybackup.InventoryBackup;
import com.zfzfg.inventorybackup.api.RestoreResult;
import com.zfzfg.inventorybackup.managers.PendingRestoreStore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Ties a player's session to the plugin's bookkeeping.
 *
 * <p>On join it records the name/UUID pair in the {@link
 * com.zfzfg.inventorybackup.managers.PlayerIndex} and applies whatever restore
 * was queued while they were offline. On quit it releases their write lock.
 */
public class PlayerSessionListener implements Listener {

    /**
     * Applying on the very tick of the join tends to be undone by whatever the
     * server or another plugin does to the inventory right afterwards. One tick
     * of delay is enough to land last.
     */
    private static final long RESTORE_DELAY_TICKS = 1L;

    private final InventoryBackup plugin;

    public PlayerSessionListener(InventoryBackup plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getPlayerIndex().remember(player.getUniqueId(), player.getName());

        PendingRestoreStore pending = plugin.getPendingRestores();
        if (!pending.get(player.getUniqueId()).isPresent()) {
            return;
        }

        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> applyPending(player.getUniqueId()), RESTORE_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getDamageListener().removeCachedInventory(event.getPlayer().getUniqueId());
    }

    private void applyPending(UUID playerId) {
        PendingRestoreStore.Entry entry = plugin.getPendingRestores().get(playerId).orElse(null);
        Player player = plugin.getServer().getPlayer(playerId);
        if (entry == null || player == null) {
            return;
        }

        if (entry.failure() != null) return;
        plugin.getApiService().background(() -> plugin.getInventoryManager().readBackup(entry.owner(), entry.backupId()))
                .thenCompose(snapshot -> plugin.getApiService().mainCall(() -> {
                    Player current = plugin.getServer().getPlayer(playerId);
                    if (current != player || !player.isOnline()
                            || plugin.getPendingRestores().get(playerId).orElse(null) != entry) return false;
                    if (snapshot.isEmpty()) {
                        plugin.getPendingRestores().failIfSame(playerId, entry, "Backup no longer exists");
                        plugin.getLogger().warning("Pending restore backup missing: " + entry.backupId());
                        return false;
                    }
                    RestoreResult result = plugin.getApiService().applyNow(player, snapshot.get(), entry.options());
                    if (result == RestoreResult.APPLIED) {
                        plugin.getPendingRestores().removeIfSame(playerId, entry);
                        player.sendMessage(plugin.getMessage("inventory-restored", "player", player.getName()));
                    }
                    return result == RestoreResult.APPLIED;
                })).thenCompose(applied -> plugin.getApiService().background(() -> {
                    plugin.getPendingRestores().saveIfDirty(); return applied;
                })).exceptionally(error -> {
                    Throwable cause = error;
                    while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
                    if (cause instanceof com.zfzfg.inventorybackup.managers.InventoryManager.BackupReadException)
                        plugin.getPendingRestores().failIfSame(playerId, entry, cause.getMessage());
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Pending restore retained: " + entry.backupId(), cause);
                    if (plugin.isEnabled()) plugin.getApiService().background(() -> { plugin.getPendingRestores().saveIfDirty(); return true; });
                    return false;
                });
    }
}
