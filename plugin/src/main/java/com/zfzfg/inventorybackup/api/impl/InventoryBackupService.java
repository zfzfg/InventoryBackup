package com.zfzfg.inventorybackup.api.impl;

import com.zfzfg.inventorybackup.InventoryBackup;
import com.zfzfg.inventorybackup.api.BackupHandle;
import com.zfzfg.inventorybackup.api.BackupRequest;
import com.zfzfg.inventorybackup.api.BackupSnapshot;
import com.zfzfg.inventorybackup.api.InventoryBackupAPI;
import com.zfzfg.inventorybackup.api.PendingRestore;
import com.zfzfg.inventorybackup.api.RestoreOptions;
import com.zfzfg.inventorybackup.api.RestoreResult;
import com.zfzfg.inventorybackup.api.events.BackupCreateEvent;
import com.zfzfg.inventorybackup.api.events.BackupCreatedEvent;
import com.zfzfg.inventorybackup.api.events.BackupDeletedEvent;
import com.zfzfg.inventorybackup.api.events.InventoryRestoreEvent;
import com.zfzfg.inventorybackup.api.events.InventoryRestoredEvent;
import com.zfzfg.inventorybackup.gui.PreviewHolder;
import com.zfzfg.inventorybackup.managers.InventoryManager;
import com.zfzfg.inventorybackup.managers.PendingRestoreStore;
import com.zfzfg.inventorybackup.managers.PlayerIndex;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The {@link InventoryBackupAPI} implementation.
 *
 * <p>Deliberately thin: it owns the threading and the events, and leaves the
 * actual work to {@link InventoryManager}. The plugin's own commands and
 * listeners go through here as well, so a backup taken by {@code /inv backup}
 * fires exactly the same events as one taken by a third-party plugin.
 *
 * <h2>How the threading promise is kept</h2>
 * {@link #io(Supplier)} runs blocking work on an async thread and then completes
 * the future <b>on the main thread</b>. Because {@code CompletableFuture} runs
 * dependent stages on whichever thread completes the future, every
 * {@code thenApply} chained here - and every {@code thenAccept} a consumer
 * attaches - lands on the main thread too. That is what makes it safe for
 * callers to touch Bukkit inside their callbacks.
 */
public final class InventoryBackupService implements InventoryBackupAPI {

    private final InventoryBackup plugin;
    private final InventoryManager inventories;
    private final PlayerIndex index;
    private final PendingRestoreStore pending;
    private final java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(
            2, 2, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1024),
            runnable -> { Thread thread = new Thread(runnable, "InventoryBackup-IO"); thread.setDaemon(true); return thread; },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final java.util.Set<CompletableFuture<?>> outstanding = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closing;
    public <T> CompletableFuture<T> mainCall(Supplier<T> work) { return onMain(work); }
    public <T> CompletableFuture<T> background(Supplier<T> work) { return io(work); }
    public void shutdown() {
        closing = true;
        outstanding.forEach(future -> future.completeExceptionally(new IllegalStateException("InventoryBackup is shutting down")));
        executor.shutdown();
        try { if (!executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); executor.shutdownNow(); }
    }
    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        outstanding.add(future); future.whenComplete((value, error) -> outstanding.remove(future)); return future;
    }

    public InventoryBackupService(InventoryBackup plugin, InventoryManager inventories,
                                  PlayerIndex index, PendingRestoreStore pending) {
        this.plugin = plugin;
        this.inventories = inventories;
        this.index = index;
        this.pending = pending;
    }

    @Override
    public int getApiVersion() {
        return API_VERSION;
    }

    // ---------------------------------------------------------------- create

    @Override
    public CompletableFuture<Optional<BackupHandle>> createBackup(Player player, BackupRequest request) {
        if (player == null || request == null) {
            return failed(new IllegalArgumentException("player and request must not be null"));
        }
        if (!Bukkit.isPrimaryThread()) {
            return failed(new IllegalStateException(
                    "createBackup(Player, ...) reads the live inventory and must be called from the "
                            + "main thread. Build a BackupSnapshot yourself and use the UUID overload "
                            + "if you need to call this asynchronously."));
        }
        return createBackup(player.getUniqueId(), player.getName(), BackupSnapshot.of(player), request);
    }

    @Override
    public CompletableFuture<Optional<BackupHandle>> createBackup(UUID ownerId, String ownerName,
                                                                  BackupSnapshot snapshot,
                                                                  BackupRequest request) {
        if (ownerId == null || snapshot == null || request == null) {
            return failed(new IllegalArgumentException("ownerId, snapshot and request must not be null"));
        }

        String name = ownerName != null ? ownerName : index.nameOf(ownerId).orElse(null);

        return onMain(() -> {
            BackupCreateEvent event = new BackupCreateEvent(ownerId, name, snapshot, request);
            Bukkit.getPluginManager().callEvent(event);
            // null signals "cancelled" - the event's request otherwise carries
            // any retyping or metadata a listener added.
            return event.isCancelled() ? null : event.getRequest();
        }).thenCompose(effective -> {
            if (effective == null) {
                return CompletableFuture.completedFuture(Optional.<BackupHandle>empty());
            }
            return io(() -> {
                index.remember(ownerId, name);
                return inventories.writeBackup(ownerId, name, snapshot, effective);
            }).thenCompose(handle -> onMain(() -> {
                handle.ifPresent(h -> Bukkit.getPluginManager().callEvent(new BackupCreatedEvent(h)));
                return handle;
            }));
        });
    }

    // ------------------------------------------------------------------ read

    @Override
    public CompletableFuture<List<BackupHandle>> listBackups(UUID ownerId) {
        return listBackups(ownerId, null);
    }

    @Override
    public CompletableFuture<List<BackupHandle>> listBackups(UUID ownerId, String type) {
        if (ownerId == null) {
            return failed(new IllegalArgumentException("ownerId must not be null"));
        }
        return io(() -> inventories.listHandles(ownerId, type));
    }

    @Override
    public CompletableFuture<Optional<BackupHandle>> getLatestBackup(UUID ownerId, String type) {
        // listHandles already sorts newest first.
        return listBackups(ownerId, type)
                .thenApply(handles -> handles.isEmpty()
                        ? Optional.<BackupHandle>empty()
                        : Optional.of(handles.get(0)));
    }

    @Override
    public CompletableFuture<Optional<BackupHandle>> getBackup(UUID ownerId, String backupId) {
        if (ownerId == null || backupId == null) {
            return failed(new IllegalArgumentException("ownerId and backupId must not be null"));
        }
        return io(() -> inventories.readHandle(ownerId, backupId));
    }

    @Override
    public CompletableFuture<Optional<BackupSnapshot>> loadBackup(BackupHandle handle) {
        if (handle == null) {
            return failed(new IllegalArgumentException("handle must not be null"));
        }
        return io(() -> inventories.readBackup(handle.ownerId(), handle.id()));
    }

    // --------------------------------------------------------------- restore

    @Override
    public CompletableFuture<RestoreResult> restore(UUID targetId, BackupHandle handle, RestoreOptions options) {
        if (targetId == null || handle == null) {
            return failed(new IllegalArgumentException("targetId and handle must not be null"));
        }

        RestoreOptions effective = options == null ? RestoreOptions.all() : options;

        return io(() -> inventories.readBackup(handle.ownerId(), handle.id()))
                .thenCompose(snapshot -> onMain(() -> {
                    if (!snapshot.isPresent()) {
                        return RestoreResult.NOT_FOUND;
                    }
                    Player player = Bukkit.getPlayer(targetId);
                    if (player == null) {
                        // Offline: park it rather than dropping it on the floor.
                        return RestoreResult.QUEUED_FOR_JOIN;
                    }
                    return applyNow(player, snapshot.get(), effective);
                })).thenCompose(result -> result == RestoreResult.QUEUED_FOR_JOIN
                        ? queueRestoreOnJoin(targetId, handle, effective).thenApply(saved -> RestoreResult.QUEUED_FOR_JOIN)
                        : CompletableFuture.completedFuture(result)).exceptionally(error -> {
                    Throwable cause = error;
                    while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
                    if (cause instanceof InventoryManager.BackupReadException failure) return failure.result;
                    throw new java.util.concurrent.CompletionException(cause);
                });
    }

    /**
     * Applies a snapshot to an online player, firing the restore events around
     * it. <b>Main thread only.</b> Shared with the join listener so a queued
     * restore behaves exactly like an immediate one.
     */
    public RestoreResult applyNow(Player player, BackupSnapshot snapshot, RestoreOptions options) {
        if (!Bukkit.isPrimaryThread()) {
            return RestoreResult.FAILED;
        }

        InventoryRestoreEvent event = new InventoryRestoreEvent(player, snapshot, options);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return RestoreResult.CANCELLED;
        }

        try { InventoryManager.validateSnapshot(event.getSnapshot()); }
        catch (IllegalArgumentException error) { return RestoreResult.INVALID_BACKUP; }
        if (event.getOptions() == null) return RestoreResult.FAILED;
        if (!inventories.hasSpace(player, event.getSnapshot(), event.getOptions())) return RestoreResult.INSUFFICIENT_SPACE;
        if (!inventories.applySnapshot(player, event.getSnapshot(), event.getOptions())) {
            return RestoreResult.FAILED;
        }

        Bukkit.getPluginManager().callEvent(
                new InventoryRestoredEvent(player, event.getSnapshot(), event.getOptions()));
        return RestoreResult.APPLIED;
    }

    @Override
    public CompletableFuture<Integer> giveMissingItems(UUID targetId, BackupHandle handle) {
        if (targetId == null || handle == null) {
            return failed(new IllegalArgumentException("targetId and handle must not be null"));
        }

        return io(() -> inventories.readBackup(handle.ownerId(), handle.id()))
                .thenCompose(snapshot -> onMain(() -> {
                    Player player = Bukkit.getPlayer(targetId);
                    if (!snapshot.isPresent() || player == null) {
                        return -1;
                    }
                    return inventories.applyMissingItems(player, snapshot.get());
                }));
    }

    // -------------------------------------------------------- offline queue

    @Override
    public CompletableFuture<Boolean> queueRestoreOnJoin(UUID targetId, BackupHandle handle, RestoreOptions options) {
        if (targetId == null || handle == null) {
            return failed(new IllegalArgumentException("targetId and handle must not be null"));
        }
        return io(() -> {
            inventories.queueRestore(targetId, handle, options == null ? RestoreOptions.all() : options, null);
            return Boolean.TRUE;
        });
    }

    /**
     * Queues a restore and flushes it to disk in the background.
     *
     * <p>The write is not deferred to the periodic save: a promise made to an
     * offline player should not be lost because the server went down before the
     * next flush.
     */
    public void queue(UUID targetId, BackupHandle handle, RestoreOptions options, String sourcePlugin) {
        io(() -> {
            inventories.queueRestore(targetId, handle, options, sourcePlugin);
            return Boolean.TRUE;
        }).exceptionally(error -> { plugin.getLogger().log(java.util.logging.Level.SEVERE, "Cannot persist pending restore", error); return false; });
    }

    @Override
    public CompletableFuture<Optional<PendingRestore>> getPendingRestore(UUID targetId) {
        if (targetId == null) {
            return failed(new IllegalArgumentException("targetId must not be null"));
        }

        return io(() -> pending.get(targetId).flatMap(entry ->
                // Resolving the handle here means a pending restore whose backup
                // has since been deleted reports as absent instead of blowing up
                // on join.
                inventories.readHandle(entry.owner(), entry.backupId())
                        .map(handle -> new PendingRestore(entry.target(), handle, entry.options(),
                                Instant.ofEpochMilli(entry.queuedAt()), entry.sourcePlugin()))));
    }

    @Override
    public CompletableFuture<Boolean> cancelPendingRestore(UUID targetId) {
        if (targetId == null) {
            return failed(new IllegalArgumentException("targetId must not be null"));
        }
        boolean removed = pending.remove(targetId);
        return io(() -> {
            pending.saveIfDirty();
            return removed;
        });
    }

    // ---------------------------------------------------------------- delete

    @Override
    public CompletableFuture<Boolean> deleteBackup(BackupHandle handle) {
        return deleteBackup(handle, BackupDeletedEvent.Reason.API);
    }

    /** Deletes a backup, attributing it to a specific reason in the event. */
    public CompletableFuture<Boolean> deleteBackup(BackupHandle handle, BackupDeletedEvent.Reason reason) {
        if (handle == null) {
            return failed(new IllegalArgumentException("handle must not be null"));
        }
        return io(() -> { boolean deleted = inventories.deleteBackup(handle.ownerId(), handle.id()); pending.saveIfDirty(); return deleted; })
                .thenCompose(deleted -> onMain(() -> {
                    if (deleted) {
                        Bukkit.getPluginManager().callEvent(new BackupDeletedEvent(handle, reason));
                    }
                    return deleted;
                }));
    }

    @Override
    public CompletableFuture<Integer> deleteBackups(UUID ownerId, String type) {
        return deleteBackups(ownerId, type, BackupDeletedEvent.Reason.API);
    }

    /** Deletes a player's backups, attributing them to a specific reason. */
    public CompletableFuture<Integer> deleteBackups(UUID ownerId, String type, BackupDeletedEvent.Reason reason) {
        if (ownerId == null) {
            return failed(new IllegalArgumentException("ownerId must not be null"));
        }
        return io(() -> { List<BackupHandle> deleted = inventories.deleteBackups(ownerId, type); pending.saveIfDirty(); return deleted; })
                .thenCompose(deleted -> onMain(() -> {
                    fireDeleted(deleted, reason);
                    return deleted.size();
                }));
    }

    /** Fires {@code BackupDeletedEvent} for a batch. <b>Main thread only.</b> */
    public void fireDeleted(List<BackupHandle> handles, BackupDeletedEvent.Reason reason) {
        for (BackupHandle handle : handles) {
            Bukkit.getPluginManager().callEvent(new BackupDeletedEvent(handle, reason));
        }
    }

    // ------------------------------------------------------------------ misc

    @Override
    public boolean openPreview(Player viewer, BackupHandle handle) {
        if (viewer == null || handle == null || !Bukkit.isPrimaryThread()) {
            return false;
        }

        loadBackup(handle).thenCompose(snapshot -> onMain(() -> {
            if (!snapshot.isPresent() || !viewer.isOnline()) {
                return false;
            }
            String title = plugin.getLanguageManager().getInventoryTitle("gui-title",
                    "player", displayName(handle));
            viewer.openInventory(PreviewHolder.build(snapshot.get(), title));
            viewer.sendMessage(plugin.getMessage("inventory-level",
                    "player", displayName(handle),
                    "level", String.valueOf(snapshot.get().level())));
            return true;
        })).exceptionally(error -> { plugin.getLogger().log(java.util.logging.Level.WARNING, "Cannot open backup preview", error); return false; });

        return true;
    }

    private String displayName(BackupHandle handle) {
        return handle.ownerName() != null ? handle.ownerName() : index.displayName(handle.ownerId());
    }

    @Override
    public CompletableFuture<Optional<UUID>> resolvePlayerId(String name) {
        return onMain(() -> resolveNow(name));
    }

    /** Synchronous name lookup for the plugin's own commands. */
    public Optional<UUID> resolveNow(String name) {
        Player online = name == null ? null : Bukkit.getPlayerExact(name);
        if (online != null) {
            index.remember(online.getUniqueId(), online.getName());
            return Optional.of(online.getUniqueId());
        }
        return index.lookup(name);
    }

    @Override
    public CompletableFuture<Optional<String>> resolvePlayerName(UUID playerId) {
        return onMain(() -> index.nameOf(playerId));
    }

    // -------------------------------------------------------------- plumbing

    /**
     * Runs blocking work off the main thread and completes the future back on
     * the main thread.
     */
    private <T> CompletableFuture<T> io(Supplier<T> work) {
        CompletableFuture<T> future = track(new CompletableFuture<>());

        if (closing || !plugin.isEnabled()) {
            future.completeExceptionally(new IllegalStateException("InventoryBackup is disabled"));
            return future;
        }

        try { executor.execute(() -> {
            if (closing) { future.completeExceptionally(new IllegalStateException("InventoryBackup is shutting down")); return; }
            T value = null;
            Throwable error = null;
            try {
                value = work.get();
            } catch (Throwable t) {
                error = t;
            }
            completeOnMain(future, value, error);
        }); } catch (java.util.concurrent.RejectedExecutionException error) { future.completeExceptionally(error); }

        return future;
    }

    /** Runs work on the main thread, completing the future there. */
    private <T> CompletableFuture<T> onMain(Supplier<T> work) {
        if (closing || !plugin.isEnabled()) return failed(new IllegalStateException("InventoryBackup is disabled"));
        if (Bukkit.isPrimaryThread()) {
            try {
                return CompletableFuture.completedFuture(work.get());
            } catch (Throwable t) {
                return failed(t);
            }
        }

        CompletableFuture<T> future = track(new CompletableFuture<>());
        if (closing || !plugin.isEnabled()) {
            future.completeExceptionally(new IllegalStateException("InventoryBackup is disabled"));
            return future;
        }

        try { plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                if (closing || !plugin.isEnabled()) throw new IllegalStateException("InventoryBackup is disabled");
                future.complete(work.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }); } catch (RuntimeException error) { future.completeExceptionally(error); }
        return future;
    }

    private <T> void completeOnMain(CompletableFuture<T> future, T value, Throwable error) {
        Runnable complete = () -> {
            if (closing || !plugin.isEnabled()) { future.completeExceptionally(new IllegalStateException("InventoryBackup is disabled")); return; }
            if (error != null) {
                future.completeExceptionally(error);
            } else {
                future.complete(value);
            }
        };

        if (Bukkit.isPrimaryThread()) {
            complete.run();
            return;
        }

        if (closing || !plugin.isEnabled()) {
            // Shutting down, so there is no main thread left to hop to.
            // Fail rather than complete: a dependent stage would otherwise run
            // off-thread and start touching Bukkit during disable.
            future.completeExceptionally(new IllegalStateException(
                    "InventoryBackup was disabled before the result could be delivered"));
            return;
        }

        try { plugin.getServer().getScheduler().runTask(plugin, complete); }
        catch (RuntimeException failure) { future.completeExceptionally(failure); }
    }

    private <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = track(new CompletableFuture<>());
        future.completeExceptionally(error);
        return future;
    }
}
