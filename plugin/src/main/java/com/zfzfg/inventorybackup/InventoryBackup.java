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

        // Clean damage cache periodically (default 30 seconds)
        int cacheIntervalSeconds = getConfig().getInt("cache-cleanup-interval", 30);
        long cacheIntervalTicks = cacheIntervalSeconds * 20L;
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (damageListener != null) {
                damageListener.cleanOldCache();
            }
        }, cacheIntervalTicks, cacheIntervalTicks);

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

        // Null when onEnable failed before the service was built.
        if (apiService != null) {
            getServer().getServicesManager().unregister(InventoryBackupAPI.class, apiService);
        }

        // Synchronous on purpose: the scheduler is already shutting down, so a
        // task scheduled here would never run and the queue would be lost.
        if (playerIndex != null) {
            playerIndex.saveIfDirty();
        }
        if (pendingRestores != null) {
            pendingRestores.saveIfDirty();
        }

        if (languageManager != null) {
            getLogger().info(languageManager.getConsoleMsg("plugin-disabled"));
        }
    }

    private void startCleanupTask() {
        int autoDays = getConfig().getInt("auto-delete-days", 30);

        if (autoDays > 0) {
            int intervalHours = getConfig().getInt("cleanup-interval-hours", 24);
            long intervalTicks = intervalHours * 60L * 60 * 20;

            cleanupTask = new FileCleanupTask(this);
            cleanupTask.runTaskTimerAsynchronously(this, intervalTicks, intervalTicks);

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
        persistenceTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            playerIndex.saveIfDirty();

            int expired = pendingRestores.purgeExpired();
            if (expired > 0) {
                getLogger().info(languageManager.getConsoleMsg(
                        "pending-expired", "count", String.valueOf(expired)));
            }
            pendingRestores.saveIfDirty();
        }, PERSISTENCE_FLUSH_TICKS, PERSISTENCE_FLUSH_TICKS);
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
        reloadConfig();
        languageManager.reload();
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
