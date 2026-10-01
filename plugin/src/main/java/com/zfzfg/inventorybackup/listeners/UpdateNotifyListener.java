package com.zfzfg.inventorybackup.listeners;

import com.zfzfg.inventorybackup.InventoryBackup;
import com.zfzfg.inventorybackup.update.UpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Informiert berechtigte Spieler beim Betreten des Servers ueber verfuegbare Updates.
 * Liest ausschliesslich das gecachte Ergebnis des UpdateCheckers (kein HTTP-Request beim Join).
 */
public final class UpdateNotifyListener implements Listener {

    private final InventoryBackup plugin;

    public UpdateNotifyListener(InventoryBackup plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("update-check.enabled", true)
                || !plugin.getConfig().getBoolean("update-check.notify-admins-on-join", true)) {
            return;
        }

        // Permission statt isOp() -- so kann man es in LuckPerms gezielt vergeben.
        if (!event.getPlayer().hasPermission("inventorybackup.update.notify")) {
            return;
        }

        UpdateChecker checker = plugin.getUpdateChecker();
        // NUR den Cache lesen. Kein Abruf hier -- sonst feuert jeder Login
        // einen HTTP-Request und man laeuft ins Rate-Limit.
        if (checker == null || !checker.isUpdateAvailable()) {
            return;
        }

        // Kurz warten (20 Ticks = 1s), damit die Meldung nicht im Motd-Block untergeht.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = event.getPlayer();
            if (player == null || !player.isOnline()) {
                return;
            }

            player.sendMessage(plugin.getMessage("update-available",
                    "current", checker.getCurrentVersion(),
                    "latest", checker.getLatestVersion(),
                    "version", checker.getLatestVersion()));
            player.sendMessage(plugin.getMessage("update-download",
                    "url", checker.getDownloadUrl()));
        }, 20L);
    }
}
