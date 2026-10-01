package com.zfzfg.inventorybackup.api.events;

import com.zfzfg.inventorybackup.api.BackupHandle;
import org.bukkit.event.HandlerList;

/**
 * Fired after a backup has been deleted - through the API, through
 * {@code /inv <player> delete}, or by the automatic cleanup task.
 *
 * <p>Not cancellable: by the time it fires the file is already gone. Use it to
 * drop your own references to the backup id.
 */
public class BackupDeletedEvent extends InventoryBackupEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final BackupHandle handle;
    private final Reason reason;

    public BackupDeletedEvent(BackupHandle handle, Reason reason) {
        this.handle = handle;
        this.reason = reason;
    }

    /** The backup that was removed. Its file no longer exists. */
    public BackupHandle getHandle() {
        return handle;
    }

    public Reason getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /** Why the backup went away. */
    public enum Reason {

        /** Deleted through the API. */
        API,

        /** Deleted with {@code /inv <player> delete}. */
        COMMAND,

        /** Removed by the scheduled cleanup because it aged out. */
        EXPIRED
    }
}
