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
        plugin.getInventoryManager().releaseLock(event.getPlayer().getUniqueId());
    }

    private void applyPending(UUID playerId) {
        PendingRestoreStore.Entry entry = plugin.getPendingRestores().get(playerId).orElse(null);
        Player player = plugin.getServer().getPlayer(playerId);
        if (entry == null || player == null) {
            return;
        }

        // Taken out of the queue before the read: a restore that cannot be read
        // is a restore that will never work, and leaving it in place would retry
        // it on every single join.
        plugin.getPendingRestores().remove(playerId);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getPendingRestores().saveIfDirty();

            plugin.getInventoryManager().readBackup(entry.owner(), entry.backupId())
                    .ifPresent(snapshot -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        RestoreResult result =
                                plugin.getApiService().applyNow(player, snapshot, entry.options());
                        if (result == RestoreResult.APPLIED) {
                            plugin.getLogger().info(plugin.getLanguageManager().getConsoleMsg(
                                    "pending-applied", "player", player.getName()));
                            player.sendMessage(plugin.getMessage("inventory-restored",
                                    "player", player.getName()));
                        }
                    }));
        });
    }
}
