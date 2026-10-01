package com.zfzfg.inventorybackup.managers;

import com.zfzfg.inventorybackup.InventoryBackup;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps player names to UUIDs and back, without ever touching the network.
 *
 * <p>Backups are stored under the owner's UUID, but commands, tab completion and
 * the API's {@code resolvePlayerId} all start from a name.
 * {@code Bukkit.getOfflinePlayer(String)} would answer that, but it can fire a
 * blocking Mojang lookup from whatever thread asked -- not something to do on a
 * tab completion. So the plugin keeps its own index instead, filled from three
 * places: every player join, every backup that gets written, and the one-time
 * storage migration.
 *
 * <p>Consequence worth knowing: only players this server has actually seen are
 * resolvable. That is the honest answer anyway -- a name the server has never
 * seen has no backups either.
 */
public final class PlayerIndex {

    private static final String FILE_NAME = "names.yml";

    private final InventoryBackup plugin;
    private final File file;

    private final Map<String, UUID> byName = new ConcurrentHashMap<>();
    private final Map<UUID, String> byId = new ConcurrentHashMap<>();

    /** Revisions let a completed write acknowledge only the snapshot it persisted. */
    private long revision;
    private long savedRevision;
    private final Object persistenceLock = new Object();

    public PlayerIndex(InventoryBackup plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
    }

    /** Reads the index from disk. Call once during enable. */
    public synchronized void load() {
        byName.clear();
        byId.clear();

        if (!file.exists()) {
            return;
        }

        YamlConfiguration config = new YamlConfiguration();
        try { config.load(file); }
        catch (Exception error) { throw new IllegalStateException("Cannot load PlayerIndex safely; original file preserved", error); }
        ConfigurationSection players = config.getConfigurationSection("players");
        if (players == null) {
            return;
        }

        for (String rawId : players.getKeys(false)) {
            UUID id = InventoryManager.parseUuid(rawId);
            String name = players.getString(rawId);
            if (id != null && name != null && !name.isEmpty()) {
                byId.put(id, name);
                byName.put(name.toLowerCase(Locale.ROOT), id);
            }
        }
    }

    /**
     * Records a name/UUID pair. Cheap and safe to call on every join -- the file
     * is only rewritten when something actually changed.
     */
    public synchronized void remember(UUID id, String name) {
        if (id == null || name == null || name.isEmpty()) {
            return;
        }

        String previous = byId.put(id, name);
        if (previous != null && !previous.equalsIgnoreCase(name)) {
            // Player renamed: drop the stale name so it stops resolving to them.
            byName.remove(previous.toLowerCase(Locale.ROOT), id);
        }
        UUID replaced = byName.put(name.toLowerCase(Locale.ROOT), id);

        if (!id.equals(replaced) || !name.equals(previous)) {
            revision++;
        }
    }

    /** The UUID behind a name, if this server has seen it. */
    public Optional<UUID> lookup(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    /** The last name seen for a UUID. */
    public Optional<String> nameOf(UUID id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    /** The last name seen for a UUID, falling back to the UUID itself. */
    public String displayName(UUID id) {
        return nameOf(id).orElseGet(() -> String.valueOf(id));
    }

    /** Every name in the index, for tab completion. */
    public List<String> knownNames() {
        return new ArrayList<>(byId.values());
    }

    /** Writes the index if anything changed. Blocking; call off the main thread. */
    public void saveIfDirty() {
        synchronized (persistenceLock) {
            final long snapshotRevision;
            final String document;
            synchronized (this) {
                if (savedRevision == revision) return;
                snapshotRevision = revision;
                YamlConfiguration config = new YamlConfiguration();
                config.options().setHeader(List.of(
                        "Player name index. Rebuilt automatically - safe to delete,",
                        "but names of players who have not joined since will stop resolving."));

                ConfigurationSection players = config.createSection("players");
                byId.forEach((id, name) -> players.set(id.toString(), name));

                document = config.saveToString();
            }
            try {
                com.zfzfg.inventorybackup.utils.AtomicFiles.write(file.toPath(), document);
                synchronized (this) { savedRevision = snapshotRevision; }
            } catch (IOException e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Cannot persist PlayerIndex", e);
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

}
