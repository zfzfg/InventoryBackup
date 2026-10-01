package com.zfzfg.inventorybackup;

import com.zfzfg.inventorybackup.api.InventoryBackupAPI;
import com.zfzfg.inventorybackup.api.impl.InventoryBackupService;
import com.zfzfg.inventorybackup.commands.InventoryCommand;
import com.zfzfg.inventorybackup.i18n.LanguageManager;
import com.zfzfg.inventorybackup.listeners.PlayerDamageListener;
import com.zfzfg.inventorybackup.listeners.PlayerDeathListener;
import com.zfzfg.inventorybackup.listeners.PlayerSessionListener;
import com.zfzfg.inventorybackup.listeners.PreviewGuiListener;
import com.zfzfg.inventorybackup.listeners.UpdateNotifyListener;
import com.zfzfg.inventorybackup.managers.FileCleanupTask;
import com.zfzfg.inventorybackup.managers.InventoryManager;
import com.zfzfg.inventorybackup.managers.PendingRestoreStore;
import com.zfzfg.inventorybackup.managers.PlayerIndex;
import com.zfzfg.inventorybackup.managers.StorageMigrator;
import com.zfzfg.inventorybackup.update.UpdateChecker;
import org.bukkit.ChatColor;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;

public class InventoryBackup extends JavaPlugin {

    /** How often the name index and the pending queue are flushed to disk. */
    private static final long PERSISTENCE_FLUSH_TICKS = 20L * 60 * 5;

    private volatile org.bukkit.configuration.file.FileConfiguration activeConfig;
    private BukkitTask cacheTask;
    private InventoryManager inventoryManager;
    private FileCleanupTask cleanupTask;
    private BukkitTask persistenceTask;
    private PlayerDamageListener damageListener;
    private UpdateChecker updateChecker;
    private LanguageManager languageManager;
    private PlayerIndex playerIndex;
    private PendingRestoreStore pendingRestores;
    private InventoryBackupService apiService;

    @Override
    public void onEnable() {
        // Create plugin folder and inventories subfolder
        saveDefaultConfig();
        try {
            org.bukkit.configuration.file.YamlConfiguration candidate = new org.bukkit.configuration.file.YamlConfiguration();
            candidate.load(new File(getDataFolder(), "config.yml"));
            validateConfig(candidate);
            activeConfig = candidate;
            backupBeforeFormatUpgrade();
        } catch (Exception error) {
            getLogger().log(java.util.logging.Level.SEVERE, "Cannot initialize InventoryBackup safely", error);
            getServer().getPluginManager().disablePlugin(this); return;
        }

        // Sprachtexte: erst die Altbestaende aus der config.yml holen,
        // dann das Bundle laden -- sonst laeuft die Migration ins Leere.
        languageManager = new LanguageManager(this);
        languageManager.migrateFromConfig();
        languageManager.load();

        File inventoriesFolder = new File(getDataFolder(), "inventories");
        if (!inventoriesFolder.exists()) {
            inventoriesFolder.mkdirs();
        }

        // Initialize managers
        inventoryManager = new InventoryManager(this);
        playerIndex = new PlayerIndex(this);
        playerIndex.load();
        pendingRestores = new PendingRestoreStore(this);
        pendingRestores.load();

        // Storage layout first: everything below assumes UUID-keyed folders, and
        // the migration is what makes that true for an upgraded server.
        new StorageMigrator(this, inventoryManager, playerIndex).migrateIfNeeded();
        playerIndex.saveIfDirty();

        apiService = new InventoryBackupService(this, inventoryManager, playerIndex, pendingRestores);
        getServer().getServicesManager().register(
                InventoryBackupAPI.class, apiService, this, ServicePriority.Normal);

        // Register listeners
        damageListener = new PlayerDamageListener();
        getServer().getPluginManager().registerEvents(damageListener, this);
        getServer().getPluginManager().registerEvents(new PlayerDeathListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerSessionListener(this), this);
        getServer().getPluginManager().registerEvents(new PreviewGuiListener(), this);
        getServer().getPluginManager().registerEvents(new UpdateNotifyListener(this), this);

        // Register commands
        if (getCommand("inv") != null) {
            InventoryCommand invCmd = new InventoryCommand(this);
            getCommand("inv").setExecutor(invCmd);
            getCommand("inv").setTabCompleter(invCmd);
        }

        // Start cleanup task
        startCleanupTask();
        startPersistenceTask();

        startCacheTask();
        apiService.background(() -> { inventoryManager.initializeFileNames(); return true; });

        // Clean damage cache periodically (default 30 seconds)
        // Der Checker wird IMMER angelegt -- sonst ist er null, sobald jemand
        // check-on-startup abschaltet, und der version-Befehl stuerzt ab.
        // Nur der automatische Start-Abruf haengt an der Konfiguration.
        updateChecker = new UpdateChecker(
                this,
                getConfig().getString("update-check.modrinth-project-id", "rpKY25cW"),
                getConfig().getString("update-check.contact", "zfzfg@sterra.online"),
                getConfig().getBoolean("update-check.stable-only", true));

        if (getConfig().getBoolean("update-check.enabled", true)
                && getConfig().getBoolean("update-check.check-on-startup", true)) {
            updateChecker.check(getConfig().getLong("update-check.startup-delay-ticks", 20L), null);
        }

        getLogger().info(languageManager.getConsoleMsg("api-registered",
                "version", String.valueOf(InventoryBackupAPI.API_VERSION)));
        getLogger().info(languageManager.getConsoleMsg("plugin-enabled"));
    }

    @Override
    public void onDisable() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        if (persistenceTask != null) {
            persistenceTask.cancel();
        }

        if (cacheTask != null) cacheTask.cancel();
        if (updateChecker != null) updateChecker.close();
        getServer().getScheduler().cancelTasks(this);
        if (apiService != null) apiService.shutdown();

        // Null when onEnable failed before the service was built.
        if (apiService != null) {
            getServer().getServicesManager().unregister(InventoryBackupAPI.class, apiService);
        }

        // Synchronous on purpose: the scheduler is already shutting down, so a
        // task scheduled here would never run and the queue would be lost.
        if (playerIndex != null) {
            try { playerIndex.saveIfDirty(); } catch (RuntimeException error) { getLogger().log(java.util.logging.Level.SEVERE, "Final name index save failed", error); }
        }
        if (pendingRestores != null) {
            try { pendingRestores.saveIfDirty(); } catch (RuntimeException error) { getLogger().log(java.util.logging.Level.SEVERE, "Final pending save failed", error); }
        }

        if (languageManager != null) {
            getLogger().info(languageManager.getConsoleMsg("plugin-disabled"));
        }
    }

    private void startCacheTask() {
        int cacheIntervalSeconds = getConfig().getInt("cache-cleanup-interval", 30);
        long cacheIntervalTicks = cacheIntervalSeconds * 20L;
        cacheTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (damageListener != null) {
                damageListener.cleanOldCache();
            }
        }, cacheIntervalTicks, cacheIntervalTicks);

    }

    private void startCleanupTask() {
        int autoDays = getConfig().getInt("auto-delete-days", 30);

        if (autoDays > 0) {
            int intervalHours = getConfig().getInt("cleanup-interval-hours", 24);
            long intervalTicks = intervalHours * 60L * 60 * 20;

            cleanupTask = new FileCleanupTask(this);
            cleanupTask.runTaskTimer(this, intervalTicks, intervalTicks);

            getLogger().info(languageManager.getConsoleMsg("cleanup-task-started",
                    "hours", String.valueOf(intervalHours)));
        } else {
            getLogger().info(languageManager.getConsoleMsg("cleanup-disabled"));
        }
    }

    /**
     * Flushes the name index and the pending queue. Both are cheap no-ops unless
     * something changed, so this runs regardless of the cleanup settings -
     * losing the queue because auto-deletion is off would be a nasty surprise.
     */
    private void startPersistenceTask() {
        persistenceTask = getServer().getScheduler().runTaskTimer(this, () -> apiService.background(() -> {
            playerIndex.saveIfDirty();

            int expired = pendingRestores.purgeExpired();
            if (expired > 0) {
                getLogger().info(languageManager.getConsoleMsg(
                        "pending-expired", "count", String.valueOf(expired)));
            }
            pendingRestores.saveIfDirty();
            return true;
        }).exceptionally(error -> { getLogger().log(java.util.logging.Level.WARNING, "Persistence flush failed", error); return false; }), PERSISTENCE_FLUSH_TICKS, PERSISTENCE_FLUSH_TICKS);
    }

    public PlayerDamageListener getDamageListener() {
        return damageListener;
    }

    public InventoryManager getInventoryManager() {
        return inventoryManager;
    }

    /**
     * The developer API. Other plugins should go through
     * {@link com.zfzfg.inventorybackup.api.InventoryBackupProvider} instead of
     * casting to this class.
     */
    public InventoryBackupService getApiService() {
        return apiService;
    }

    public PlayerIndex getPlayerIndex() {
        return playerIndex;
    }

    public PendingRestoreStore getPendingRestores() {
        return pendingRestores;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    /**
     * Laedt config.yml und Sprachbundle neu -- Grundlage von /inv reload und
     * /inv lang.
     */
    public void reloadAll() {
        org.bukkit.configuration.file.YamlConfiguration candidate = new org.bukkit.configuration.file.YamlConfiguration();
        try { candidate.load(new File(getDataFolder(), "config.yml")); validateConfig(candidate); }
        catch (Exception error) { throw new IllegalArgumentException("Configuration rejected; previous settings retained", error); }
        org.bukkit.configuration.file.FileConfiguration previous = activeConfig;
        activeConfig = candidate;
        try { languageManager.reload(); }
        catch (RuntimeException error) { activeConfig = previous; languageManager.reload(); throw error; }
        if (cleanupTask != null) { cleanupTask.cancel(); cleanupTask = null; }
        if (cacheTask != null) cacheTask.cancel();
        startCleanupTask(); startCacheTask();
        if (updateChecker != null) updateChecker.close();
        updateChecker = new UpdateChecker(this, getConfig().getString("update-check.modrinth-project-id", "rpKY25cW"),
                getConfig().getString("update-check.contact", "zfzfg@sterra.online"), getConfig().getBoolean("update-check.stable-only", true));
    }

    @Override
    public org.bukkit.configuration.file.FileConfiguration getConfig() {
        return activeConfig == null ? super.getConfig() : activeConfig;
    }

    @Override
    public void saveConfig() {
        if (activeConfig == null) { super.saveConfig(); return; }
        try { com.zfzfg.inventorybackup.utils.AtomicFiles.write(new File(getDataFolder(), "config.yml").toPath(), activeConfig.saveToString()); }
        catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    private static void validateConfig(org.bukkit.configuration.file.FileConfiguration config) {
        java.util.Map<String, Integer> minimums = java.util.Map.of("auto-delete-days", 0, "cleanup-interval-hours", 1,
                "cache-cleanup-interval", 1, "pending-restore-expiry-days", 0);
        java.util.Map<String, Integer> defaults = java.util.Map.of("auto-delete-days", 30, "cleanup-interval-hours", 24,
                "cache-cleanup-interval", 30, "pending-restore-expiry-days", 30);
        minimums.forEach((key, minimum) -> {
            if (!config.contains(key)) config.set(key, defaults.get(key));
            if (!config.isInt(key) || config.getInt(key) < minimum || config.getInt(key) > 365000)
                throw new IllegalArgumentException("Invalid " + key);
        });
        for (String key : java.util.List.of("save-on-death", "notify-on-backup", "notify-ops-only", "update-check.enabled",
                "update-check.check-on-startup", "update-check.notify-admins-on-join", "update-check.stable-only")) {
            if (!config.contains(key)) config.set(key, true);
            if (!config.isBoolean(key)) throw new IllegalArgumentException("Invalid boolean: " + key);
        }
        if (!java.util.List.of("en", "de").contains(config.getString("language", "en"))) throw new IllegalArgumentException("Invalid language");
        if (config.contains("update-check.startup-delay-ticks") && (!(config.get("update-check.startup-delay-ticks") instanceof Number)
                || config.getLong("update-check.startup-delay-ticks") < 0 || config.getLong("update-check.startup-delay-ticks") > 72000))
            throw new IllegalArgumentException("Invalid update delay");
        if (!config.getString("update-check.modrinth-project-id", "rpKY25cW").matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Invalid Modrinth project id");
        String contact = config.getString("update-check.contact", "zfzfg@sterra.online");
        if (contact.contains("\r") || contact.contains("\n")) throw new IllegalArgumentException("Invalid contact");
    }

    private void backupBeforeFormatUpgrade() throws java.io.IOException {
        java.nio.file.Path marker = getDataFolder().toPath().resolve(".nbt-format-backup-complete");
        if (java.nio.file.Files.exists(marker)) return;
        java.nio.file.Path archive = getDataFolder().toPath().resolve("pre-nbt-upgrade-" + System.currentTimeMillis() + ".zip");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(archive));
                java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(getDataFolder().toPath())) {
            for (java.nio.file.Path path : paths.filter(java.nio.file.Files::isRegularFile).toList()) {
                if (path.equals(archive) || path.getFileName().toString().startsWith("pre-nbt-upgrade-")) continue;
                if (java.nio.file.Files.isSymbolicLink(path)) throw new java.io.IOException("Cannot safely back up symlink " + path);
                zip.putNextEntry(new java.util.zip.ZipEntry(getDataFolder().toPath().relativize(path).toString().replace('\\', '/')));
                java.nio.file.Files.copy(path, zip); zip.closeEntry();
            }
        }
        com.zfzfg.inventorybackup.utils.AtomicFiles.write(marker, archive.getFileName().toString());
    }

    /**
     * Spielernachricht mit Prefix.
     *
     * @param replacements Paare aus Platzhaltername und Wert, z. B.
     *                     {@code "player", name} fuer {@code {player}}
     */
    public String getMessage(String path, String... replacements) {
        return languageManager.getMessage(path, replacements);
    }


    public String colorize(String message) {
        if (message == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
