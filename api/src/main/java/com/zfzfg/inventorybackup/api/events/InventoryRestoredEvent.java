package com.zfzfg.inventorybackup.api.events;

import com.zfzfg.inventorybackup.api.BackupSnapshot;
import com.zfzfg.inventorybackup.api.RestoreOptions;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Fired after a backup has been written back to a player.
 *
 * <p>The snapshot and options here are the ones that were actually applied,
 * including any changes {@link InventoryRestoreEvent} listeners made.
 */
public class InventoryRestoredEvent extends InventoryBackupEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final BackupSnapshot snapshot;
    private final RestoreOptions options;

    public InventoryRestoredEvent(Player player, BackupSnapshot snapshot, RestoreOptions options) {
        this.player = player;
        this.snapshot = snapshot;
        this.options = options;
    }

    public Player getPlayer() {
        return player;
    }

    /** What was applied. */
    public BackupSnapshot getSnapshot() {
        return snapshot;
    }

    /** Which parts of it were applied. */
    public RestoreOptions getOptions() {
        return options;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
