package com.zfzfg.inventorybackup.api.events;

import com.zfzfg.inventorybackup.api.BackupHandle;
import org.bukkit.event.HandlerList;

/**
 * Fired after a backup has been written to disk successfully.
 *
 * <p>The {@link BackupHandle} is final at this point - keep its
 * {@link BackupHandle#id() id} if you want to restore this exact backup later.
 */
public class BackupCreatedEvent extends InventoryBackupEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final BackupHandle handle;

    public BackupCreatedEvent(BackupHandle handle) {
        this.handle = handle;
    }

    /** The backup that was just written. */
    public BackupHandle getHandle() {
        return handle;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
