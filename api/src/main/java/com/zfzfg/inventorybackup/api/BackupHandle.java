package com.zfzfg.inventorybackup.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A lightweight reference to one stored backup - everything except the items.
 *
 * <p>Handles are what {@link InventoryBackupAPI#listBackups(UUID)} returns and
 * what every other API method takes. Loading the actual items costs a file read,
 * so it happens only when you ask for it through
 * {@link InventoryBackupAPI#loadBackup(BackupHandle)}.
 *
 * <p>Instances are immutable and safe to keep across ticks. The {@link #id()} is
 * stable for the lifetime of the backup, so it is the right thing to persist in
 * your own storage.
 */
public final class BackupHandle {

    private final String id;
    private final UUID ownerId;
    private final String ownerName;
    private final Instant createdAt;
    private final String type;
    private final String sourcePlugin;
    private final Map<String, String> metadata;

    /**
     * @param id           file name of the backup, e.g. {@code 2026-08-09_12-00-00_quest.yml}
     * @param ownerId      UUID of the player the backup belongs to
     * @param ownerName    last known name of that player, for display only
     * @param createdAt    when the backup was written
     * @param type         normalized backup type, see {@link BackupType}
     * @param sourcePlugin name of the plugin that created it, or null
     * @param metadata     free-form key/value pairs, may be null
     */
    public BackupHandle(String id, UUID ownerId, String ownerName, Instant createdAt,
                        String type, String sourcePlugin, Map<String, String> metadata) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.ownerName = ownerName;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.type = Objects.requireNonNull(type, "type");
        this.sourcePlugin = sourcePlugin;
        this.metadata = metadata == null || metadata.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /** File name of the backup - unique per owner, stable, safe to persist. */
    public String id() {
        return id;
    }

    /** UUID of the player this backup belongs to. */
    public UUID ownerId() {
        return ownerId;
    }

    /** Last known name of the owner, or null if it was never recorded. */
    public String ownerName() {
        return ownerName;
    }

    /** When the backup was written. */
    public Instant createdAt() {
        return createdAt;
    }

    /** Normalized backup type, e.g. {@code death}, {@code manual}, {@code quest}. */
    public String type() {
        return type;
    }

    /** Name of the plugin that created this backup, or null if unknown. */
    public String sourcePlugin() {
        return sourcePlugin;
    }

    /** Immutable metadata that was attached at creation time; never null. */
    public Map<String, String> metadata() {
        return metadata;
    }

    /** Convenience accessor for a single metadata value, or null. */
    public String metadata(String key) {
        return metadata.get(key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BackupHandle)) {
            return false;
        }
        BackupHandle other = (BackupHandle) o;
        return id.equals(other.id) && ownerId.equals(other.ownerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId);
    }

    @Override
    public String toString() {
        return "BackupHandle{" + ownerId + "/" + id + ", type=" + type + "}";
    }
}
