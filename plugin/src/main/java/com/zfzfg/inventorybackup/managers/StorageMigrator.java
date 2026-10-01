package com.zfzfg.inventorybackup.managers;

import com.zfzfg.inventorybackup.InventoryBackup;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Moves the backup archive from name-keyed folders to UUID-keyed folders.
 *
 * <p>Up to 0.0.7 backups lived in {@code inventories/<PlayerName>/}, which meant
 * a rename orphaned a player's entire history. Every backup file has always
 * carried the owner's {@code uuid} inside it, so the migration reads that and
 * renames the folder -- no Mojang lookup involved.
 *
 * <p>Runs once, synchronously during enable, and records
 * {@code storage-version: 2} in config.yml when done. Nothing is ever deleted:
 * a folder whose UUID cannot be determined is parked under
 * {@code inventories/_unmigrated/} for an admin to look at.
 *
 * <p>The file shuffling lives in {@link #migrateFolders(File, BiConsumer)},
 * which knows nothing about the plugin -- that is what makes it testable, and
 * why this class only logs what that method reports back.
 */
public final class StorageMigrator {

    /** Folder that collects what could not be resolved. Never a valid UUID. */
    static final String UNMIGRATED_FOLDER = "_unmigrated";

    private static final String VERSION_KEY = "storage-version";

    private final InventoryBackup plugin;
    private final InventoryManager inventories;
    private final PlayerIndex index;

    public StorageMigrator(InventoryBackup plugin, InventoryManager inventories, PlayerIndex index) {
        this.plugin = plugin;
        this.inventories = inventories;
        this.index = index;
    }

    /**
     * Migrates the archive if it has not been migrated yet.
     *
     * @return the number of player folders that were moved
     */
    public int migrateIfNeeded() {
        if (plugin.getConfig().getInt(VERSION_KEY, 1) >= InventoryManager.STORAGE_VERSION) {
            return 0;
        }

        File root = inventories.inventoriesFolder();
        if (pendingFolders(root).length == 0) {
            markDone();
            return 0;
        }

        plugin.getLogger().info(plugin.getLanguageManager().getConsoleMsg(
                "storage-migration-start", "count", String.valueOf(pendingFolders(root).length)));

        Report report = migrateFolders(root, index::remember);

        report.moved().forEach((name, id) -> plugin.getLogger().info(
                plugin.getLanguageManager().getConsoleMsg("storage-migration-moved",
                        "player", name, "uuid", id.toString())));

        report.parked().forEach(name -> plugin.getLogger().warning(
                plugin.getLanguageManager().getConsoleMsg("storage-migration-unresolved",
                        "player", name)));

        report.failed().forEach((name, error) -> plugin.getLogger().severe(
                plugin.getLanguageManager().getConsoleMsg("storage-migration-failed",
                        "player", name, "error", error)));

        plugin.getLogger().info(plugin.getLanguageManager().getConsoleMsg(
                "storage-migration-finished",
                "migrated", String.valueOf(report.moved().size()),
                "skipped", String.valueOf(report.parked().size() + report.failed().size())));

        // Parked folders are preserved for inspection; failed moves remain retryable.
        if (report.failed().isEmpty()) markDone();
        return report.moved().size();
    }

    // ------------------------------------------------------- testable core

    /**
     * Renames every name-keyed folder under {@code root} to its owner's UUID.
     *
     * @param root     the {@code inventories} folder
     * @param nameSink called with (uuid, name) for every folder that resolved,
     *                 so the caller can feed its name index
     * @return what happened, for the caller to log
     */
    static Report migrateFolders(File root, BiConsumer<UUID, String> nameSink) {
        Report report = new Report();

        for (File folder : pendingFolders(root)) {
            String playerName = folder.getName();
            try {
                UUID owner = readOwner(folder);
                if (owner == null) {
                    park(root, folder);
                    report.parked.add(playerName);
                    continue;
                }

                File target = new File(root, owner.toString());
                if (target.exists()) {
                    // Both layouts present for the same player - merge instead
                    // of clobbering, since either side may hold backups the
                    // other lacks.
                    mergeInto(folder, target);
                } else {
                    Files.move(folder.toPath(), target.toPath());
                }

                nameSink.accept(owner, playerName);
                report.moved.put(playerName, owner);
            } catch (IOException | RuntimeException e) {
                report.failed.put(playerName, String.valueOf(e.getMessage()));
            }
        }

        return report;
    }

    /** Folders that still need work: not a UUID, not the parking folder. */
    private static File[] pendingFolders(File root) {
        File[] folders = root.listFiles(file -> file.isDirectory());
        if (folders == null) {
            return new File[0];
        }
        return Arrays.stream(folders)
                .filter(folder -> !UNMIGRATED_FOLDER.equals(folder.getName()))
                .filter(folder -> InventoryManager.parseUuid(folder.getName()) == null)
                .toArray(File[]::new);
    }

    /** Reads the owner UUID out of the first backup file that names one. */
    private static UUID readOwner(File folder) {
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return null;
        }
        for (File file : files) {
            String raw = YamlConfiguration.loadConfiguration(file).getString("uuid");
            UUID owner = raw == null ? null : InventoryManager.parseUuid(raw);
            if (owner != null) {
                return owner;
            }
        }
        return null;
    }

    private static void mergeInto(File source, File target) throws IOException {
        File[] files = source.listFiles();
        if (files != null) {
            for (File file : files) {
                File destination = new File(target, file.getName());
                // Same file name in both folders means the same timestamp and
                // type. Keep both by renaming rather than picking a winner.
                for (int counter = 2; destination.exists(); counter++) {
                    destination = new File(target, "merged-" + counter + "-" + file.getName());
                }
                Files.move(file.toPath(), destination.toPath());
            }
        }
        source.delete();
    }

    private static void park(File root, File folder) throws IOException {
        File parking = new File(root, UNMIGRATED_FOLDER);
        if (!parking.exists() && !parking.mkdirs()) {
            throw new IOException("could not create " + parking);
        }
        Files.move(folder.toPath(), new File(parking, folder.getName()).toPath());
    }

    private void markDone() {
        plugin.getConfig().set(VERSION_KEY, InventoryManager.STORAGE_VERSION);
        plugin.saveConfig();
    }

    /** What one migration run did, so the caller can translate and log it. */
    static final class Report {

        private final Map<String, UUID> moved = new LinkedHashMap<>();
        private final List<String> parked = new ArrayList<>();
        private final Map<String, String> failed = new LinkedHashMap<>();

        /** Player name to the UUID folder their backups now live in. */
        Map<String, UUID> moved() {
            return moved;
        }

        /** Names whose UUID could not be determined; moved to _unmigrated. */
        List<String> parked() {
            return parked;
        }

        /** Names that hit an I/O error, mapped to the error message. */
        Map<String, String> failed() {
            return failed;
        }
    }
}
