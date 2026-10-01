package com.zfzfg.inventorybackup.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A restore that is waiting for its target player to come online.
 *
 * @see InventoryBackupAPI#queueRestoreOnJoin(UUID, BackupHandle, RestoreOptions)
 */
public final class PendingRestore {

    private final UUID targetId;
    private final BackupHandle handle;
    private final RestoreOptions options;
    private final Instant queuedAt;
    private final String sourcePlugin;

    public PendingRestore(UUID targetId, BackupHandle handle, RestoreOptions options,
                          Instant queuedAt, String sourcePlugin) {
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.handle = Objects.requireNonNull(handle, "handle");
        this.options = options == null ? RestoreOptions.all() : options;
        this.queuedAt = queuedAt == null ? Instant.now() : queuedAt;
        this.sourcePlugin = sourcePlugin;
    }

    /** The player who will receive the restore on their next join. */
    public UUID targetId() {
        return targetId;
    }

    /** The backup to apply - not necessarily owned by the target player. */
    public BackupHandle handle() {
        return handle;
    }

    public RestoreOptions options() {
        return options;
    }

    public Instant queuedAt() {
        return queuedAt;
    }

    /** Plugin that queued this restore, or null. */
    public String sourcePlugin() {
        return sourcePlugin;
    }

    @Override
    public String toString() {
        return "PendingRestore{" + targetId + " <- " + handle.id() + "}";
    }
}
