package com.zfzfg.inventorybackup.api;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The public API of the InventoryBackup plugin.
 *
 * <p>Obtain an instance through {@link InventoryBackupProvider#get()}:
 *
 * <pre>{@code
 * InventoryBackupAPI api = InventoryBackupProvider.get();
 * api.createBackup(player, BackupRequest.of("quest", this))
 *    .thenAccept(handle -> handle.ifPresent(h ->
 *            getLogger().info("stored as " + h.id())));
 * }</pre>
 *
 * <h2>Threading</h2>
 * Every {@link CompletableFuture} returned here <b>completes on the server's
 * main thread</b>. You may call Bukkit methods directly inside
 * {@code thenAccept} / {@code thenRun} without hopping schedulers yourself. The
 * file I/O behind each call runs off the main thread, so none of these methods
 * block the server - but do not {@code join()} or {@code get()} them from the
 * main thread either, because that deadlocks against the completion hop.
 *
 * <h2>Compatibility</h2>
 * Check {@link #getApiVersion()} against {@link #API_VERSION} if you use methods
 * added after the version you compiled against. The number is incremented
 * whenever methods are added; existing behaviour is not changed underneath you.
 */
public interface InventoryBackupAPI {

    /** API revision this interface describes. Bumped on every addition. */
    int API_VERSION = 1;

    /**
     * The API revision the running plugin implements. Compare against
     * {@link #API_VERSION} to detect a plugin older than your compile target.
     */
    int getApiVersion();

    // ---------------------------------------------------------------- create

    /**
     * Backs up an online player's current inventory.
     *
     * <p>Fires {@code BackupCreateEvent} before writing - another plugin may
     * cancel it, in which case the future completes with an empty Optional.
     * Must be called from the main thread, since it reads the live inventory.
     *
     * @param player  the player to snapshot
     * @param request type, source plugin and metadata for the new backup
     * @return the created backup, or empty if it was cancelled or failed
     */
    CompletableFuture<Optional<BackupHandle>> createBackup(Player player, BackupRequest request);

    /**
     * Stores a snapshot you assembled yourself - useful for backing up an
     * offline player, or for saving something other than the player's current
     * inventory. Safe to call from any thread.
     *
     * @param ownerId   the player the backup belongs to
     * @param ownerName last known name of that player, for display; may be null
     * @param snapshot  the items, level and experience to store
     * @param request   type, source plugin and metadata for the new backup
     * @return the created backup, or empty if it was cancelled or failed
     */
    CompletableFuture<Optional<BackupHandle>> createBackup(UUID ownerId, String ownerName,
                                                          BackupSnapshot snapshot,
                                                          BackupRequest request);

    // ------------------------------------------------------------------ read

    /** All backups of a player, newest first. */
    CompletableFuture<List<BackupHandle>> listBackups(UUID ownerId);

    /**
     * Backups of a player filtered by type, newest first.
     *
     * @param type backup type; normalized like {@link BackupType#normalize(String)},
     *             or null for all types
     */
    CompletableFuture<List<BackupHandle>> listBackups(UUID ownerId, String type);

    /**
     * The most recent backup of a player.
     *
     * @param type backup type to filter by, or null for any type
     */
    CompletableFuture<Optional<BackupHandle>> getLatestBackup(UUID ownerId, String type);

    /**
     * Looks up a backup by its {@link BackupHandle#id() id}.
     *
     * @param ownerId  the owning player
     * @param backupId file name of the backup
     */
    CompletableFuture<Optional<BackupHandle>> getBackup(UUID ownerId, String backupId);

    /**
     * Reads the items of a backup.
     *
     * <p>Completes empty if the file is gone or its contents cannot be decoded -
     * the API never hands back a half-read snapshot, because applying one would
     * wipe the player's inventory.
     */
    CompletableFuture<Optional<BackupSnapshot>> loadBackup(BackupHandle handle);

    // --------------------------------------------------------------- restore

    /**
     * Restores a backup to a player.
     *
     * <p>If the target is online the backup is applied immediately
     * ({@link RestoreResult#APPLIED}). If they are offline it is queued and
     * applied on their next join ({@link RestoreResult#QUEUED_FOR_JOIN}).
     * Fires a cancellable {@code InventoryRestoreEvent} right before applying.
     *
     * <p>The backup does not have to belong to the target player - restoring one
     * player's backup onto another is allowed and is how "give this loadout to
     * everyone" flows work.
     *
     * @param targetId the player who receives the items
     * @param handle   the backup to apply
     * @param options  what to restore; {@link RestoreOptions#all()} for everything
     */
    CompletableFuture<RestoreResult> restore(UUID targetId, BackupHandle handle, RestoreOptions options);

    /**
     * Gives a player every item from the backup they are not currently carrying,
     * without touching what they already have. Only works for online players.
     *
     * @return the number of item stacks handed over, or -1 if the backup could
     *         not be read or the player is offline
     */
    CompletableFuture<Integer> giveMissingItems(UUID targetId, BackupHandle handle);

    // ------------------------------------------------------- offline restore

    /**
     * Marks a restore to run the next time the player joins, even if they are
     * online right now. Persisted, so it survives a restart. Replaces any
     * restore already queued for that player.
     *
     * @return true once the entry has been stored
     */
    CompletableFuture<Boolean> queueRestoreOnJoin(UUID targetId, BackupHandle handle, RestoreOptions options);

    /** The restore waiting for this player, if any. */
    CompletableFuture<Optional<PendingRestore>> getPendingRestore(UUID targetId);

    /**
     * Drops a queued restore.
     *
     * @return true if there was one to drop
     */
    CompletableFuture<Boolean> cancelPendingRestore(UUID targetId);

    // ---------------------------------------------------------------- delete

    /**
     * Deletes a backup. Fires {@code BackupDeletedEvent} afterwards.
     *
     * @return true if the file existed and was removed
     */
    CompletableFuture<Boolean> deleteBackup(BackupHandle handle);

    /**
     * Deletes all backups of a player.
     *
     * @param type restrict to one backup type, or null for all
     * @return how many files were deleted
     */
    CompletableFuture<Integer> deleteBackups(UUID ownerId, String type);

    // ----------------------------------------------------------------- misc

    /**
     * Opens the read-only preview GUI for a backup. Main thread only.
     *
     * <p>Returns immediately; the window opens once the file has been read, so a
     * {@code true} return means "accepted", not "already open".
     *
     * @return false if called off the main thread or with a null argument
     */
    boolean openPreview(Player viewer, BackupHandle handle);

    /**
     * Resolves a player name to a UUID using the plugin's own name index, which
     * is filled on join and never blocks on a web request.
     *
     * <p>Only knows players who have joined this server, or who had backups at
     * the time the storage was migrated to UUID folders.
     */
    CompletableFuture<Optional<UUID>> resolvePlayerId(String name);

    /** Last name seen for a player, or empty if they are unknown. */
    CompletableFuture<Optional<String>> resolvePlayerName(UUID playerId);
}
