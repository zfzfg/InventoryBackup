package com.zfzfg.inventorybackup.managers;

import com.zfzfg.inventorybackup.InventoryBackup;
import com.zfzfg.inventorybackup.api.RestoreOptions;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Restores that are waiting for their target player to come online.
 *
 * <p>Kept in memory and mirrored to {@code pending-restores.yml}, so queueing is
 * instant from any thread and the queue survives a restart -- a restore promised
 * to an offline player should not evaporate because the server rebooted first.
 *
 * <p>Only the <i>reference</i> to the backup is stored (owner plus file name),
 * never a copy of the items. The handle is rebuilt from the backup file when the
 * player joins; if the backup has been deleted in the meantime, the entry is
 * marked failed rather than applying something stale.
 */
public final class PendingRestoreStore {

    private static final String FILE_NAME = "pending-restores.yml";

    private final InventoryBackup plugin;
    private final File file;

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private long revision;
    private long savedRevision;
    private final Object persistenceLock = new Object();

    public PendingRestoreStore(InventoryBackup plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
    }

    /** Reads the queue from disk. Call once during enable. */
    public synchronized void load() {
        entries.clear();
        if (!file.exists()) {
            return;
        }

        YamlConfiguration config = new YamlConfiguration();
        try { config.load(file); }
        catch (Exception error) { throw new IllegalStateException("Cannot load PendingRestoreStore safely; original file preserved", error); }
        for (String rawTarget : config.getKeys(false)) {
            UUID target = InventoryManager.parseUuid(rawTarget);
            ConfigurationSection section = config.getConfigurationSection(rawTarget);
            if (target == null || section == null) {
                continue;
            }

            UUID owner = InventoryManager.parseUuid(String.valueOf(section.getString("owner")));
            String backupId = section.getString("backup");
            if (owner == null || backupId == null) {
                continue;
            }

            Entry loaded = new Entry(target, owner, backupId,
                    readOptions(section.getConfigurationSection("options")),
                    section.getLong("queued-at", System.currentTimeMillis()),
                    section.getString("source-plugin"));
            loaded.failure = section.getString("failure");
            entries.put(target, loaded);
        }
    }

    /** Queues a restore, replacing whatever was queued for that player before. */
    public synchronized void put(UUID target, UUID owner, String backupId, RestoreOptions options, String sourcePlugin) {
        entries.put(target, new Entry(target, owner, backupId,
                options == null ? RestoreOptions.all() : options,
                System.currentTimeMillis(), sourcePlugin));
        revision++;
    }

    public synchronized Optional<Entry> get(UUID target) {
        Entry entry = entries.get(target);
        if (entry != null && expired(entry)) { entries.remove(target); revision++; return Optional.empty(); }
        return Optional.ofNullable(entry);
    }
    private boolean expired(Entry entry) {
        int days = plugin.getConfig().getInt("pending-restore-expiry-days", 30);
        return days > 0 && entry.queuedAt() < System.currentTimeMillis() - days * 86400000L;
    }
    public synchronized boolean removeIfSame(UUID target, Entry entry) {
        boolean removed = entries.remove(target, entry); if (removed) revision++; return removed;
    }
    public synchronized void failIfSame(UUID target, Entry entry, String reason) {
        if (entries.get(target) == entry) { entry.failure = reason; revision++; }
    }
    public synchronized boolean references(UUID owner, String backupId) {
        return entries.values().stream().anyMatch(entry -> !expired(entry) && entry.owner().equals(owner) && entry.backupId().equals(backupId));
    }
    public synchronized void removeBackup(UUID owner, String backupId) {
        if (entries.values().removeIf(entry -> entry.owner().equals(owner) && entry.backupId().equals(backupId))) {
            revision++;
            plugin.getLogger().warning("Pending restores cancelled because backup was explicitly deleted: " + backupId);
        }
    }

    /** @return true if there was an entry to remove */
    public synchronized boolean remove(UUID target) {
        boolean removed = entries.remove(target) != null;
        if (removed) {
            revision++;
        }
        return removed;
    }

    /**
     * Drops entries older than the configured expiry.
     *
     * @return how many were dropped
     */
    public synchronized int purgeExpired() {
        int days = plugin.getConfig().getInt("pending-restore-expiry-days", 30);
        if (days <= 0) {
            return 0;
        }

        long cutoff = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);
        int before = entries.size();
        entries.values().removeIf(entry -> entry.queuedAt() < cutoff);

        int removed = before - entries.size();
        if (removed > 0) {
            revision++;
        }
        return removed;
    }

    /** Writes the queue if anything changed. Blocking; call off the main thread. */
    public void saveIfDirty() {
        synchronized (persistenceLock) {
            final long snapshotRevision;
            final String document;
            synchronized (this) {
                if (savedRevision == revision) return;
                snapshotRevision = revision;
                YamlConfiguration config = new YamlConfiguration();
                config.options().setHeader(List.of(
                        "Restores waiting for their target player to join.",
                        "Managed by the plugin - deleting this file just drops the queue."));

                for (Entry entry : entries.values()) {
                    ConfigurationSection section = config.createSection(entry.target().toString());
                    section.set("owner", entry.owner().toString());
                    section.set("backup", entry.backupId());
                    section.set("queued-at", entry.queuedAt());
                    section.set("failure", entry.failure);
                    if (entry.sourcePlugin() != null) {
                        section.set("source-plugin", entry.sourcePlugin());
                    }
                    writeOptions(section.createSection("options"), entry.options());
                }

                document = config.saveToString();
            }
            try {
                com.zfzfg.inventorybackup.utils.AtomicFiles.write(file.toPath(), document);
                synchronized (this) { savedRevision = snapshotRevision; }
            } catch (IOException e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Cannot persist PendingRestoreStore", e);
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    private static RestoreOptions readOptions(ConfigurationSection section) {
        if (section == null) {
            return RestoreOptions.all();
        }
        return RestoreOptions.builder()
                .contents(section.getBoolean("contents", true))
                .armor(section.getBoolean("armor", true))
                .offhand(section.getBoolean("offhand", true))
                .level(section.getBoolean("level", true))
                .exp(section.getBoolean("exp", true))
                .clearBefore(section.getBoolean("clear-before", true))
                .dropOverflow(section.getBoolean("drop-overflow", true))
                .build();
    }

    private static void writeOptions(ConfigurationSection section, RestoreOptions options) {
        section.set("contents", options.contents());
        section.set("armor", options.armor());
        section.set("offhand", options.offhand());
        section.set("level", options.level());
        section.set("exp", options.exp());
        section.set("clear-before", options.clearBefore());
        section.set("drop-overflow", options.dropOverflow());
    }

    /** One queued restore, as stored. The backup handle is resolved on use. */
    public static final class Entry {

        private volatile String failure;
        public String failure() { return failure; }
        private final UUID target;
        private final UUID owner;
        private final String backupId;
        private final RestoreOptions options;
        private final long queuedAt;
        private final String sourcePlugin;

        Entry(UUID target, UUID owner, String backupId, RestoreOptions options,
              long queuedAt, String sourcePlugin) {
            this.target = target;
            this.owner = owner;
            this.backupId = backupId;
            this.options = options;
            this.queuedAt = queuedAt;
            this.sourcePlugin = sourcePlugin;
        }

        /** The player who will receive the restore. */
        public UUID target() {
            return target;
        }

        /** The player the backup belongs to - not necessarily the target. */
        public UUID owner() {
            return owner;
        }

        public String backupId() {
            return backupId;
        }

        public RestoreOptions options() {
            return options;
        }

        public long queuedAt() {
            return queuedAt;
        }

        public String sourcePlugin() {
            return sourcePlugin;
        }
    }
}
