package com.zfzfg.inventorybackup.api.events;

import org.bukkit.event.Event;

/**
 * Base class for every event InventoryBackup fires.
 *
 * <p>All of them are called on the main thread, so listeners may touch the world
 * freely. They live in the plugin jar, which means you can listen for them
 * without compiling against the API module at all - only the events, not the
 * {@code InventoryBackupAPI} interface, are needed for that.
 */
public abstract class InventoryBackupEvent extends Event {

    protected InventoryBackupEvent() {
        super(false);
    }
}
