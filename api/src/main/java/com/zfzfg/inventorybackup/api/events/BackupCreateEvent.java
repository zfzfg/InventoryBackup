package com.zfzfg.inventorybackup.api.events;

import com.zfzfg.inventorybackup.api.BackupRequest;
import com.zfzfg.inventorybackup.api.BackupSnapshot;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired just before a backup is written to disk - including the automatic one
 * taken when a player dies.
 *
 * <p>Cancel it to stop the backup entirely, or change the {@link BackupRequest}
 * to retype it or attach your own metadata:
 *
 * <pre>{@code
 * @EventHandler
 * public void onBackupCreate(BackupCreateEvent event) {
 *     if (arena.contains(event.getOwnerId())) {
 *         event.setCancelled(true);          // no arena deaths in the archive
 *     } else {
 *         event.setRequest(event.getRequest()
 *                 .withType("world-" + worldName));
 *     }
 * }
 * }</pre>
 */
public class BackupCreateEvent extends InventoryBackupEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID ownerId;
    private final String ownerName;
    private final BackupSnapshot snapshot;
    private BackupRequest request;
    private boolean cancelled;

    public BackupCreateEvent(UUID ownerId, String ownerName, BackupSnapshot snapshot, BackupRequest request) {
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.snapshot = snapshot;
        this.request = request;
    }

    /** The player the backup belongs to. */
    public UUID getOwnerId() {
        return ownerId;
    }

    /** Last known name of that player; may be null. */
    public String getOwnerName() {
        return ownerName;
    }

    /** The data about to be stored. Read-only. */
    public BackupSnapshot getSnapshot() {
        return snapshot;
    }

    /** Type, source plugin and metadata of the backup. */
    public BackupRequest getRequest() {
        return request;
    }

    /**
     * Replaces the request, letting you change the type or add metadata. Use
     * {@link BackupRequest#withType(String)} to derive one from the current
     * request rather than dropping what another listener already set.
     */
    public void setRequest(BackupRequest request) {
        if (request != null) {
            this.request = request;
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
