package com.zfzfg.inventorybackup.api.events;

import com.zfzfg.inventorybackup.api.BackupSnapshot;
import com.zfzfg.inventorybackup.api.RestoreOptions;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/**
 * Fired immediately before a backup is written back to a player.
 *
 * <p>Cancel it to block the restore, or swap the snapshot to filter what the
 * player gets back:
 *
 * <pre>{@code
 * @EventHandler
 * public void onRestore(InventoryRestoreEvent event) {
 *     ItemStack[] contents = event.getSnapshot().contents();
 *     for (int i = 0; i < contents.length; i++) {
 *         if (isBanned(contents[i])) {
 *             contents[i] = null;
 *         }
 *     }
 *     event.setSnapshot(event.getSnapshot().withItems(
 *             contents, event.getSnapshot().armor(), event.getSnapshot().offhand()));
 * }
 * }</pre>
 *
 * <p>For a queued offline restore this fires on join, not when it was queued.
 */
public class InventoryRestoreEvent extends InventoryBackupEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private BackupSnapshot snapshot;
    private RestoreOptions options;
    private boolean cancelled;

    public InventoryRestoreEvent(Player player, BackupSnapshot snapshot, RestoreOptions options) {
        this.player = player;
        this.snapshot = snapshot;
        this.options = options;
    }

    /** The player about to receive the items. */
    public Player getPlayer() {
        return player;
    }

    /** The data about to be applied. */
    public BackupSnapshot getSnapshot() {
        return snapshot;
    }

    /** Replaces what will be applied. Ignored if null. */
    public void setSnapshot(BackupSnapshot snapshot) {
        if (snapshot != null) {
            this.snapshot = snapshot;
        }
    }

    /** Which parts of the snapshot will be written. */
    public RestoreOptions getOptions() {
        return options;
    }

    /** Narrows or widens what gets restored. Ignored if null. */
    public void setOptions(RestoreOptions options) {
        if (options != null) {
            this.options = options;
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
