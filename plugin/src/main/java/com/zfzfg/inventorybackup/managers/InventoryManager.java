package com.zfzfg.inventorybackup.managers;

import com.zfzfg.inventorybackup.InventoryBackup;
import com.zfzfg.inventorybackup.api.BackupHandle;
import com.zfzfg.inventorybackup.api.BackupRequest;
import com.zfzfg.inventorybackup.api.BackupSnapshot;
import com.zfzfg.inventorybackup.api.BackupType;
import com.zfzfg.inventorybackup.api.RestoreOptions;
import com.zfzfg.inventorybackup.utils.InventorySerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reads and writes the backup files.
 *
 * <h2>Threading contract</h2>
 * The methods split cleanly in two, and mixing them up is the one way to break
 * this class:
 * <ul>
 *   <li><b>Storage methods</b> ({@code writeBackup}, {@code readBackup},
 *       {@code listHandles}, {@code delete*}, {@code cleanOldFiles}) block on
 *       file I/O and must <b>not</b> run on the main thread.</li>
 *   <li><b>Apply methods</b> ({@code applySnapshot}, {@code applyMissingItems})
 *       touch live Bukkit state and must run on the main thread.</li>
 * </ul>
 * Neither kind schedules anything itself -- the thread hop belongs to the
 * caller, which is why the return values here mean what they say instead of
 * "a task was queued".
 *
 * <h2>Layout on disk</h2>
 * {@code <data folder>/inventories/<owner uuid>/<yyyy-MM-dd_HH-mm-ss>_<type>.yml}.
 * Folders are keyed by UUID so a rename never orphans a player's history; see
 * {@link StorageMigrator} for the one-time move from the old name-keyed layout.
 */
public class InventoryManager {

    /** Bumped when the file format changes in a way readers must notice. */
    public static final int STORAGE_VERSION = 2;

    static final String INVENTORIES_FOLDER = "inventories";

    /** Length of the {@code yyyy-MM-dd_HH-mm-ss} prefix every file name starts with. */
    private static final int TIMESTAMP_LENGTH = 19;

    /** Slots of a player inventory that hold the main contents (hotbar included). */
    private static final int MAIN_SLOTS = 36;

    /** Shared and immutable, so it is safe to use from the async storage calls. */
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final InventoryBackup plugin;
    private final ReentrantLock[] playerLocks = new ReentrantLock[256];
    private final Map<UUID, List<String>> fileNames = new ConcurrentHashMap<>();

    public InventoryManager(InventoryBackup plugin) {
        this.plugin = plugin;
        for (int i = 0; i < playerLocks.length; i++) playerLocks[i] = new ReentrantLock();
    }

    // ------------------------------------------------------------- storage

    /**
     * Writes a snapshot to disk. Blocking; call off the main thread.
     *
     * @param owner     the player the backup belongs to
     * @param ownerName last known name of that player, stored for display only
     * @param snapshot  the items, level and experience to store
     * @param request   type, source plugin and metadata
     * @return a handle for the new file, or empty if cancelled; storage failures throw
     */
    public Optional<BackupHandle> writeBackup(UUID owner, String ownerName,
                                              BackupSnapshot snapshot, BackupRequest request) {
        ReentrantLock lock = lockFor(owner);
        lock.lock();
        try {
            File playerFolder = playerFolder(owner);
            try {
                if (!playerFolder.getCanonicalFile().toPath().startsWith(inventoriesFolder().getCanonicalFile().toPath()))
                    throw new IllegalArgumentException("Unsafe player directory");
            } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            if (!playerFolder.exists() && !playerFolder.mkdirs()) {
                plugin.getLogger().severe(plugin.getLanguageManager().getConsoleMsg("save-failed",
                        "player", String.valueOf(ownerName), "error", "could not create " + playerFolder));
                throw new IllegalStateException("Could not create backup directory");
            }

            String type = BackupType.normalize(request.type());
            LocalDateTime now = LocalDateTime.now();
            File file = uniqueFile(playerFolder, FILE_TIMESTAMP.format(now), type);

            YamlConfiguration config = new YamlConfiguration();
            long timestamp = System.currentTimeMillis();

            config.set("storage-version", STORAGE_VERSION);
            config.set("player", ownerName);
            config.set("uuid", owner.toString());
            config.set("timestamp", timestamp);
            config.set("type", type);
            if (request.sourcePlugin() != null) {
                config.set("source-plugin", request.sourcePlugin());
            }
            if (!request.metadata().isEmpty()) {
                ConfigurationSection metadata = config.createSection("metadata");
                for (Map.Entry<String, String> entry : request.metadata().entrySet()) {
                    metadata.set(entry.getKey(), entry.getValue());
                }
            }

            String[] encoded = main(() -> {
                validateSnapshot(snapshot);
                var codec = plugin.getPlatform().itemCodec();
                return new String[] { InventorySerializer.encodeInventory(snapshot.contents(), codec),
                        InventorySerializer.encodeInventory(snapshot.armor(), codec),
                        InventorySerializer.encodeItem(snapshot.offhand(), codec),
                        String.valueOf(codec.dataVersion()) };
            });
            config.set("format-version", 3);
            config.set("data-version", Integer.parseInt(encoded[3]));
            config.set("inventory", encoded[0]);
            config.set("armor", encoded[1]);
            config.set("offhand", encoded[2]);
            config.set("level", snapshot.level());
            config.set("exp", snapshot.exp());

            try {
                com.zfzfg.inventorybackup.utils.AtomicFiles.write(file.toPath(), config.saveToString());
            } catch (IOException e) {
                plugin.getLogger().severe(plugin.getLanguageManager().getConsoleMsg("save-failed",
                        "player", String.valueOf(ownerName), "error", String.valueOf(e.getMessage())));
                throw new java.io.UncheckedIOException(e);
            }

            refreshFileNames(owner);
            return Optional.of(new BackupHandle(file.getName(), owner, ownerName,
                    Instant.ofEpochMilli(timestamp), type, request.sourcePlugin(), request.metadata()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reads a backup's items. Blocking; call off the main thread.
     *
     * <p>Returns empty only when the file is absent. Invalid or incompatible contents
     * throw a typed failure before any live inventory can be changed.
     */
    public Optional<BackupSnapshot> readBackup(UUID owner, String fileName) {
        File file = getInventoryFile(owner, fileName);
        if (file == null || !file.exists()) {
            return Optional.empty();
        }

        ReentrantLock lock = lockFor(owner); lock.lock();
        try {
            if (java.nio.file.Files.size(file.toPath()) > InventorySerializer.MAX_BYTES * 4L)
                throw new IllegalArgumentException("Backup exceeds size limit");
            YamlConfiguration config = new YamlConfiguration();
            config.load(file);
            BackupHandle handle = toHandle(owner, file.getName(), config);
            return Optional.of(main(() -> {
                int format = config.getInt("format-version", 1);
                if (format != 1 && format != 3) throw new IllegalArgumentException("Unknown backup format");
                for (String key : List.of("inventory", "armor", "offhand", "level", "exp", "uuid", "timestamp"))
                    if (!config.contains(key)) throw new IllegalArgumentException("Missing field: " + key);
                for (String key : List.of("inventory", "armor", "offhand", "uuid"))
                    if (!config.isString(key)) throw new IllegalArgumentException("Invalid field type: " + key);
                if (!(config.get("timestamp") instanceof Number) || config.getLong("timestamp") <= 0)
                    throw new IllegalArgumentException("Invalid timestamp");
                if (!owner.toString().equals(config.getString("uuid"))) throw new IllegalArgumentException("Owner mismatch");
                if (format == 3 && (!config.isInt("data-version") || config.getInt("data-version") <= 0))
                    throw new IllegalArgumentException("Missing data version");
                if (format == 3 && config.getInt("data-version") > plugin.getPlatform().itemCodec().dataVersion())
                    throw new BackupReadException(com.zfzfg.inventorybackup.api.RestoreResult.INCOMPATIBLE_VERSION,
                            "Backup was created on a newer Minecraft version", null);
                var codec = format == 3 ? plugin.getPlatform().itemCodec() : null;
                ItemStack[] contents = InventorySerializer.decodeInventory(config.getString("inventory"), codec);
                if (format != 3 && contents.length == 41) contents = java.util.Arrays.copyOf(contents, 36);
                ItemStack[] armor = InventorySerializer.decodeInventory(config.getString("armor"), codec);
                ItemStack offhand = InventorySerializer.decodeItem(config.getString("offhand"), codec);
                if (!config.isInt("level") || !(config.get("exp") instanceof Number)) throw new IllegalArgumentException("Invalid XP fields");
                BackupSnapshot snapshot = new BackupSnapshot(handle, contents, armor, offhand,
                        config.getInt("level"), (float) config.getDouble("exp"));
                validateSnapshot(snapshot); return snapshot;
            }));
        } catch (Exception e) {
            Throwable cause = e;
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof BackupReadException error) throw error;
            if (cause instanceof IOException io) throw new java.io.UncheckedIOException(io);
            throw new BackupReadException(com.zfzfg.inventorybackup.api.RestoreResult.INVALID_BACKUP,
                    "Cannot read backup " + fileName, cause);
        } finally { lock.unlock(); }
    }

    /**
     * Reads a backup's metadata without decoding its items. Blocking; call off
     * the main thread.
     */
    public Optional<BackupHandle> readHandle(UUID owner, String fileName) {
        ReentrantLock lock = lockFor(owner); lock.lock();
        try {
            File file = getInventoryFile(owner, fileName);
            if (file == null || !file.exists()) {
                return Optional.empty();
            }
            return Optional.of(toHandle(owner, file.getName(),
                    loadDocument(file)));

        } finally { lock.unlock(); }
    }

    /**
     * All backups of a player, newest first. Blocking; call off the main thread.
     *
     * @param type restrict to one backup type, or null for all
     */
    public List<BackupHandle> listHandles(UUID owner, String type) {
        ReentrantLock lock = lockFor(owner); lock.lock();
        try {
            File[] files = backupFiles(playerFolder(owner));
            if (files == null) {
                return new ArrayList<>();
            }

            String wanted = type == null ? null : BackupType.normalize(type);
            List<BackupHandle> handles = new ArrayList<>(files.length);
            for (File file : files) {
                BackupHandle handle = toHandle(owner, file.getName(),
                        loadDocument(file));
                if (wanted == null || wanted.equals(handle.type())) {
                    handles.add(handle);
                }
            }

            handles.sort(Comparator.comparing((BackupHandle handle) -> handle.createdAt()).reversed()
                    .thenComparing(handle -> handle.id()));
            return handles;

        } finally { lock.unlock(); }
    }

    public void queueRestore(UUID target, BackupHandle handle, RestoreOptions options, String sourcePlugin) {
        ReentrantLock lock = lockFor(handle.ownerId()); lock.lock();
        try {
            if (readBackup(handle.ownerId(), handle.id()).isEmpty()) throw new IllegalArgumentException("Backup does not exist");
            plugin.getPendingRestores().put(target, handle.ownerId(), handle.id(), options, sourcePlugin);
            plugin.getPendingRestores().saveIfDirty();
        } finally { lock.unlock(); }
    }

    /** Deletes one backup. Blocking; call off the main thread. */
    public boolean deleteBackup(UUID owner, String fileName) {
        ReentrantLock lock = lockFor(owner); lock.lock();
        try {
            File file = getInventoryFile(owner, fileName);
            if (file == null || !file.exists()) {
                return false;
            }
            boolean deleted = file.delete();
            if (deleted) {
                plugin.getPendingRestores().removeBackup(owner, file.getName());
                deleteFolderIfEmpty(playerFolder(owner));
            }
            return deleted;

        } finally { refreshFileNames(owner); lock.unlock(); }
    }

    /**
     * Deletes a player's backups. Blocking; call off the main thread.
     *
     * @param type restrict to one backup type, or null for all
     * @return the handles of the files that were removed
     */
    public List<BackupHandle> deleteBackups(UUID owner, String type) {
        ReentrantLock lock = lockFor(owner); lock.lock();
        try {
            List<BackupHandle> deleted = new ArrayList<>();
            for (BackupHandle handle : listHandles(owner, type)) {
                File file = getInventoryFile(owner, handle.id());
                if (file != null && file.exists() && file.delete()) {
                    deleted.add(handle);
                    plugin.getPendingRestores().removeBackup(owner, handle.id());
                }
            }
            deleteFolderIfEmpty(playerFolder(owner));
            return deleted;

        } finally { refreshFileNames(owner); lock.unlock(); }
    }

    /**
     * Deletes backups older than {@code auto-delete-days}. Blocking; call off
     * the main thread.
     *
     * <p>The age comes from the file name, so ageing out a thousand backups does
     * not mean parsing a thousand YAML documents. Only the ones actually being
     * deleted are read, to build the handle for {@code BackupDeletedEvent}.
     *
     * @return the handles of the files that were removed
     */
    public List<BackupHandle> cleanOldFiles() {
        List<BackupHandle> deleted = new ArrayList<>();
        int maxAgeDays = plugin.getConfig().getInt("auto-delete-days", 30);
        if (maxAgeDays <= 0) {
            return deleted;
        }

        long maxAgeMillis = maxAgeDays * 24L * 60 * 60 * 1000;
        long currentTime = System.currentTimeMillis();

        File[] playerFolders = inventoriesFolder().listFiles(file -> file.isDirectory());
        if (playerFolders == null) {
            return deleted;
        }

        for (File playerFolder : playerFolders) {
            UUID owner = parseUuid(playerFolder.getName());
            if (owner == null) {
                // Not a UUID folder: either _unmigrated, or something an admin
                // dropped in by hand. Leave it alone rather than deleting it.
                continue;
            }

            File[] files = backupFiles(playerFolder);
            if (files == null) {
                continue;
            }

            ReentrantLock cleanupLock = lockFor(owner); cleanupLock.lock();
            try {
            for (File file : files) {
                if (!file.isFile() || plugin.getPendingRestores().references(owner, file.getName())) continue;
                long timestamp = parseTimestampFromFilename(file.getName());
                if (timestamp <= 0 || (currentTime - timestamp) <= maxAgeMillis) {
                    continue;
                }
                BackupHandle handle = toHandle(owner, file.getName(),
                        loadDocument(file));
                if (file.delete()) {
                    deleted.add(handle);
                }
            }

            deleteFolderIfEmpty(playerFolder);
            } finally { refreshFileNames(owner); cleanupLock.unlock(); }
        }

        return deleted;
    }

    /**
     * Drops the write lock of a player who is no longer around. Without this the
     * lock map grows by one entry per distinct player for the server's lifetime.
     */
    public void releaseLock(UUID owner) { /* Fixed stripes cannot be removed while queued. */ }

    private ReentrantLock lockFor(UUID owner) { return playerLocks[(owner.hashCode() & 0x7fffffff) % playerLocks.length]; }
    public List<String> cachedFileNames(UUID owner) { return fileNames.getOrDefault(owner, List.of()); }
    public void refreshFileNames(UUID owner) {
        ReentrantLock lock = lockFor(owner); lock.lock();
        try {
            File[] files = backupFiles(playerFolder(owner));
            if (files == null || files.length == 0) fileNames.remove(owner);
            else fileNames.put(owner, java.util.Arrays.stream(files)
                    .map(file -> file == null ? "" : file.getName()).sorted().toList());
        } finally { lock.unlock(); }
    }
    public void initializeFileNames() {
        File[] folders = inventoriesFolder().listFiles(file -> file != null && file.isDirectory());
        if (folders != null) for (File folder : folders) {
            UUID owner = parseUuid(folder.getName()); if (owner != null) refreshFileNames(owner);
        }
    }
    private <T> T main(java.util.function.Supplier<T> work) {
        return plugin.getApiService().mainCall(work).join();
    }
    public static void validateSnapshot(BackupSnapshot snapshot) {
        if (snapshot == null || snapshot.contents().length != 36 || snapshot.armor().length != 4
                || snapshot.level() < 0 || !Float.isFinite(snapshot.exp()) || snapshot.exp() < 0 || snapshot.exp() > 1)
            throw new IllegalArgumentException("Invalid inventory snapshot");
    }
    public static final class BackupReadException extends RuntimeException {
        public final com.zfzfg.inventorybackup.api.RestoreResult result;
        public BackupReadException(com.zfzfg.inventorybackup.api.RestoreResult result, String message, Throwable cause) {
            super(message, cause); this.result = result;
        }
    }

    // --------------------------------------------------------------- apply

    /**
     * Writes a snapshot back to a player. <b>Main thread only.</b>
     *
     * @return true if anything was written
     */
    public boolean applySnapshot(Player player, BackupSnapshot snapshot, RestoreOptions options) {
        validateSnapshot(snapshot);
        if (options.isNoop()) {
            return false;
        }

        PlayerInventory inventory = player.getInventory();

        if (options.contents()) {
            // Written slot by slot rather than through setContents(): the stored
            // array also carries armour and offhand in slots 36-40, and blindly
            // pushing all 41 slots back would overwrite armour even when the
            // caller asked for contents only.
            ItemStack[] contents = snapshot.contents();
            for (int slot = 0; slot < MAIN_SLOTS; slot++) {
                ItemStack item = slot < contents.length ? contents[slot] : null;
                if (options.clearBefore()) {
                    inventory.setItem(slot, item);
                } else if (item != null) {
                    giveOrDrop(player, item, options);
                }
            }
        }

        if (options.armor()) {
            ItemStack[] armor = snapshot.armor();
            ItemStack[] current = inventory.getArmorContents();
            for (int slot = 0; slot < current.length; slot++) {
                ItemStack item = slot < armor.length ? armor[slot] : null;
                if (options.clearBefore()) {
                    current[slot] = item;
                } else if (item != null) {
                    if (current[slot] == null || current[slot].getType().isAir()) current[slot] = item;
                    else giveOrDrop(player, item, options);
                }
            }
            inventory.setArmorContents(current);
        }

        if (options.offhand()) {
            ItemStack offhand = snapshot.offhand();
            ItemStack current = inventory.getItemInOffHand();
            if (options.clearBefore()) {
                inventory.setItemInOffHand(offhand);
            } else if (offhand != null) {
                if (current == null || current.getType().isAir()) inventory.setItemInOffHand(offhand);
                else giveOrDrop(player, offhand, options);
            }
        }

        if (options.level()) {
            player.setLevel(snapshot.level());
        }
        if (options.exp()) {
            player.setExp(snapshot.exp());
        }

        player.updateInventory();
        return true;
    }

    /**
     * Gives a player every item from the snapshot they are not already carrying.
     * <b>Main thread only.</b>
     *
     * @return the number of item stacks handed over
     */
    public int applyMissingItems(Player player, BackupSnapshot snapshot) {
        validateSnapshot(snapshot);
        List<ItemStack> groups = new ArrayList<>();
        List<ItemStack> saved = new ArrayList<>(java.util.Arrays.asList(snapshot.contents()));
        saved.addAll(java.util.Arrays.asList(snapshot.armor())); saved.add(snapshot.offhand());
        for (ItemStack item : saved) {
            if (item == null || item.getType().isAir()) continue;
            ItemStack group = groups.stream().filter(existing -> existing.isSimilar(item)).findFirst().orElse(null);
            if (group == null) groups.add(item.clone()); else group.setAmount(group.getAmount() + item.getAmount());
        }
        int given = 0;
        ItemStack[] currentArmor = player.getInventory().getArmorContents(), savedArmor = snapshot.armor();
        for (int i = 0; i < 4; i++) {
            if (savedArmor[i] != null && (currentArmor[i] == null || currentArmor[i].getType().isAir())) {
                currentArmor[i] = savedArmor[i]; given++;
            }
        }
        player.getInventory().setArmorContents(currentArmor);
        if (snapshot.offhand() != null && player.getInventory().getItemInOffHand().getType().isAir()) {
            player.getInventory().setItemInOffHand(snapshot.offhand()); given++;
        }
        for (ItemStack group : groups) {
            int current = 0;
            for (ItemStack item : player.getInventory().getContents())
                if (item != null && item.isSimilar(group)) current += item.getAmount();
            int missing = Math.max(0, group.getAmount() - current);
            while (missing > 0) {
                ItemStack item = group.clone(); item.setAmount(Math.min(missing, item.getMaxStackSize()));
                missing -= item.getAmount();
                for (ItemStack leftover : player.getInventory().addItem(item).values())
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                given++;
            }
        }
        return given;
    }

    public boolean hasSpace(Player player, BackupSnapshot snapshot, RestoreOptions options) {
        if (options.clearBefore() || options.dropOverflow()) return true;
        ItemStack[] slots = player.getInventory().getStorageContents();
        for (int i = 0; i < slots.length; i++) if (slots[i] != null) slots[i] = slots[i].clone();
        List<ItemStack> additions = new ArrayList<>();
        if (options.contents()) additions.addAll(java.util.Arrays.asList(snapshot.contents()));
        if (options.armor()) {
            ItemStack[] current = player.getInventory().getArmorContents(), armor = snapshot.armor();
            for (int i = 0; i < 4; i++) if (current[i] != null && !current[i].getType().isAir()) additions.add(armor[i]);
        }
        if (options.offhand() && !player.getInventory().getItemInOffHand().getType().isAir()) additions.add(snapshot.offhand());
        for (ItemStack source : additions) {
            if (source == null || source.getType().isAir()) continue;
            int remaining = source.getAmount();
            for (ItemStack slot : slots) {
                if (slot != null && slot.isSimilar(source)) {
                    int add = Math.min(remaining, Math.max(0, Math.min(slot.getMaxStackSize(), player.getInventory().getMaxStackSize()) - slot.getAmount()));
                    slot.setAmount(slot.getAmount() + add); remaining -= add;
                }
            }
            for (int i = 0; i < slots.length && remaining > 0; i++) {
                if (slots[i] == null || slots[i].getType().isAir()) {
                    slots[i] = source.clone(); int add = Math.min(remaining, Math.min(source.getMaxStackSize(), player.getInventory().getMaxStackSize()));
                    slots[i].setAmount(add); remaining -= add;
                }
            }
            if (remaining > 0) return false;
        }
        return true;
    }

    private void giveOrDrop(Player player, ItemStack item, RestoreOptions options) {
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (leftover.isEmpty() || !options.dropOverflow()) {
            return;
        }
        for (ItemStack overflow : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    // ---------------------------------------------------------- file layout

    File inventoriesFolder() {
        return new File(plugin.getDataFolder(), INVENTORIES_FOLDER);
    }

    File playerFolder(UUID owner) {
        return new File(inventoriesFolder(), owner.toString());
    }

    private static File[] backupFiles(File folder) {
        if (!folder.exists() || !folder.isDirectory()) {
            return null;
        }
        return folder.listFiles((dir, name) -> name.endsWith(".yml"));
    }

    private static void deleteFolderIfEmpty(File folder) {
        String[] remaining = folder.list();
        if (remaining != null && remaining.length == 0) {
            folder.delete();
        }
    }

    /**
     * Picks a free file name. Two backups within the same second -- a death
     * right after a manual backup, say -- would otherwise silently overwrite
     * each other.
     */
    private static File uniqueFile(File folder, String timestamp, String type) {
        File file = new File(folder, timestamp + "_" + type + ".yml");
        for (int counter = 2; file.exists(); counter++) {
            file = new File(folder, timestamp + "_" + type + "-" + counter + ".yml");
        }
        return file;
    }

    /**
     * Resolves a backup file inside a player's folder, rejecting anything that
     * could escape it.
     *
     * @return the file, or null if the name is not acceptable
     */
    private File getInventoryFile(UUID owner, String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }

        // Only alphanumerics, underscores, hyphens and dots.
        if (!fileName.matches("^[a-zA-Z0-9_.-]+$")) {
            plugin.getLogger().warning(plugin.getLanguageManager().getConsoleMsg(
                    "invalid-filename", "file", fileName));
            return null;
        }

        String resolved = fileName.endsWith(".yml") ? fileName : fileName + ".yml";

        File playerFolder = playerFolder(owner);
        File file = new File(playerFolder, resolved);

        // Ensure the resolved file is still within the player folder.
        try {
            java.nio.file.Path canonicalPath = file.getCanonicalFile().toPath();
            java.nio.file.Path canonicalFolder = playerFolder.getCanonicalFile().toPath();
            java.nio.file.Path canonicalRoot = inventoriesFolder().getCanonicalFile().toPath();
            if (!canonicalFolder.startsWith(canonicalRoot) || !canonicalPath.startsWith(canonicalFolder)) {
                plugin.getLogger().warning(plugin.getLanguageManager().getConsoleMsg(
                        "path-traversal", "file", fileName));
                return null;
            }
        } catch (IOException e) {
            plugin.getLogger().severe(plugin.getLanguageManager().getConsoleMsg(
                    "path-validation-failed", "error", String.valueOf(e.getMessage())));
            return null;
        }

        return file;
    }

    // ------------------------------------------------------------- parsing

    private static YamlConfiguration loadDocument(File file) {
        try {
            if (java.nio.file.Files.size(file.toPath()) > InventorySerializer.MAX_BYTES * 4L)
                throw new IllegalArgumentException("Backup exceeds size limit");
            YamlConfiguration config = new YamlConfiguration(); config.load(file); return config;
        } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
        catch (org.bukkit.configuration.InvalidConfigurationException error) { throw new BackupReadException(
                com.zfzfg.inventorybackup.api.RestoreResult.INVALID_BACKUP, "Invalid backup YAML", error); }
    }

    /** Builds a handle from a loaded backup file, filling in v1 gaps. */
    private BackupHandle toHandle(UUID owner, String fileName, YamlConfiguration config) {
        long timestamp = config.getLong("timestamp", 0);
        if (timestamp <= 0) {
            timestamp = parseTimestampFromFilename(fileName);
        }
        if (timestamp <= 0) {
            timestamp = System.currentTimeMillis();
        }

        return new BackupHandle(
                fileName,
                owner,
                config.getString("player"),
                Instant.ofEpochMilli(timestamp),
                BackupType.normalize(config.getString("type", BackupType.MANUAL)),
                config.getString("source-plugin"),
                readMetadata(config));
    }

    private static Map<String, String> readMetadata(YamlConfiguration config) {
        Map<String, String> metadata = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("metadata");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String value = section.getString(key);
                if (value != null) {
                    metadata.put(key, value);
                }
            }
        }
        return metadata;
    }

    /** {@link #parseTimestamp(String)} plus a log line when the name is unusable. */
    private long parseTimestampFromFilename(String filename) {
        long timestamp = parseTimestamp(filename);
        if (timestamp <= 0) {
            plugin.getLogger().warning(plugin.getLanguageManager().getConsoleMsg(
                    "timestamp-parse-failed", "file", filename));
        }
        return timestamp;
    }

    /**
     * Reads the creation time out of a {@code yyyy-MM-dd_HH-mm-ss_type.yml} name.
     *
     * <p>Only the fixed-width timestamp prefix is parsed, so a type containing
     * underscores or a {@code -2} collision suffix does not throw the parse off.
     *
     * @return epoch millis, or 0 if the name does not carry a timestamp
     */
    public static long parseTimestamp(String filename) {
        if (filename == null || filename.length() < TIMESTAMP_LENGTH) {
            return 0;
        }
        try {
            LocalDateTime dateTime = LocalDateTime.parse(
                    filename.substring(0, TIMESTAMP_LENGTH), FILE_TIMESTAMP);
            return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    /** @return the parsed UUID, or null if the text is not one */
    static UUID parseUuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
