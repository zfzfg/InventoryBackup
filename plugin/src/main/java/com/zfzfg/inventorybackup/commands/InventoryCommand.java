package com.zfzfg.inventorybackup.commands;

import com.zfzfg.inventorybackup.InventoryBackup;
import com.zfzfg.inventorybackup.api.BackupHandle;
import com.zfzfg.inventorybackup.api.BackupRequest;
import com.zfzfg.inventorybackup.api.BackupType;
import com.zfzfg.inventorybackup.api.RestoreOptions;
import com.zfzfg.inventorybackup.api.RestoreResult;
import com.zfzfg.inventorybackup.api.events.BackupDeletedEvent;
import com.zfzfg.inventorybackup.api.impl.InventoryBackupService;
import com.zfzfg.inventorybackup.i18n.LanguageManager;
import com.zfzfg.inventorybackup.update.UpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * The {@code /inv} command.
 *
 * <p>Everything here runs through {@link InventoryBackupService}, the same entry
 * point third-party plugins use. That is deliberate: it means the API cannot
 * drift away from what the commands do, and an admin action fires the same
 * events a plugin action does.
 */
public class InventoryCommand implements CommandExecutor, TabCompleter {

    /**
     * Erstes Argument, das kein Spielername ist. Ein Spieler mit einem dieser
     * Namen ist ueber /inv &lt;name&gt; ... nicht erreichbar -- der Preis dafuer,
     * dass die Verwaltungsbefehle ohne eigenes Praefix auskommen.
     */
    private static final List<String> SUBCOMMANDS =
            Arrays.asList("backup", "version", "lang", "reload");

    private static final List<String> ACTIONS = Arrays.asList(
            "restore", "show", "givemissing", "list", "delete", "pending", "cancelpending");

    private final InventoryBackup plugin;

    public InventoryCommand(InventoryBackup plugin) {
        this.plugin = plugin;
    }

    private static boolean isSubcommand(String arg) {
        return SUBCOMMANDS.stream().anyMatch(sub -> sub.equalsIgnoreCase(arg));
    }

    /**
     * Handles the /inv command.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("inventorybackup.use")) {
            sender.sendMessage(plugin.getMessage("no-permission"));
            return true;
        }

        // /inv ohne Argumente: hier raus, bevor irgendwer args[0] anfasst.
        if (args.length == 0) {
            sender.sendMessage(plugin.getMessage("invalid-usage"));
            return true;
        }

        // Handle version command (/inv version)
        if (args.length == 1 && args[0].equalsIgnoreCase("version")) {
            handleVersion(sender);
            return true;
        }

        // Verwaltungsbefehle vor der Laengenpruefung: /inv reload und /inv lang
        // kommen auch mit einem einzigen Argument aus.
        if (args[0].equalsIgnoreCase("reload")) {
            handleReload(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("lang")) {
            handleLanguage(sender, args);
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.getMessage("invalid-usage"));
            return true;
        }

        // Handle backup commands
        if (args[0].equalsIgnoreCase("backup")) {
            handleBackup(sender, args);
            return true;
        }

        // Handle player-specific commands
        String playerName = args[0];
        String action = args[1].toLowerCase();

        switch (action) {
            case "restore":
                if (!require(sender, "inventorybackup.restore") || !requireFile(sender, args)) {
                    return true;
                }
                handleRestore(sender, playerName, args[2]);
                break;

            case "show":
                if (!require(sender, "inventorybackup.show") || !requireFile(sender, args)) {
                    return true;
                }
                handleShow(sender, playerName, args[2]);
                break;

            case "givemissing":
                if (!require(sender, "inventorybackup.givemissing") || !requireFile(sender, args)) {
                    return true;
                }
                handleGiveMissing(sender, playerName, args[2]);
                break;

            case "list":
                if (!require(sender, "inventorybackup.show")) {
                    return true;
                }
                handleList(sender, playerName);
                break;

            case "delete":
                if (!require(sender, "inventorybackup.restore") || !requireFile(sender, args)) {
                    return true;
                }
                handleDelete(sender, playerName, args[2]);
                break;

            case "pending":
                if (!require(sender, "inventorybackup.pending")) {
                    return true;
                }
                handlePending(sender, playerName);
                break;

            case "cancelpending":
                if (!require(sender, "inventorybackup.pending")) {
                    return true;
                }
                handleCancelPending(sender, playerName);
                break;

            default:
                sender.sendMessage(plugin.getMessage("invalid-usage"));
                break;
        }

        return true;
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(plugin.getMessage("no-permission"));
        return false;
    }

    private boolean requireFile(CommandSender sender, String[] args) {
        if (args.length >= 3) {
            return true;
        }
        sender.sendMessage(plugin.getMessage("invalid-usage"));
        return false;
    }

    /**
     * Turns a name into the UUID its backups are filed under.
     *
     * @return empty after telling the sender why, so callers can just return
     */
    private Optional<UUID> resolve(CommandSender sender, String playerName) {
        Optional<UUID> id = plugin.getApiService().resolveNow(playerName);
        if (!id.isPresent()) {
            sender.sendMessage(plugin.getMessage("player-unknown", "player", playerName));
        }
        return id;
    }

    private void handleBackup(CommandSender sender, String[] args) {
        if (!require(sender, "inventorybackup.backup")) {
            return;
        }

        BackupRequest request = BackupRequest.of(BackupType.MANUAL, plugin);
        InventoryBackupService api = plugin.getApiService();

        if (args[1].equalsIgnoreCase("all")) {
            List<Player> targets = new ArrayList<>(Bukkit.getOnlinePlayers());
            if (targets.isEmpty()) {
                sendBackupNotification(sender, "backup-all-created", "count", "0");
                return;
            }

            // One future per player, reported once they have all landed, so the
            // count is the number that actually made it to disk.
            List<CompletableFuture<Boolean>> writes = targets.stream()
                    .map(player -> api.createBackup(player, request).thenApply(backup -> backup.isPresent()))
                    .collect(Collectors.toList());

            CompletableFuture.allOf(writes.toArray(new CompletableFuture[0]))
                    .thenRun(() -> {
                        long count = writes.stream().filter(f -> f.getNow(false)).count();
                        sendBackupNotification(sender, "backup-all-created",
                                "count", String.valueOf(count));
                    })
                    .exceptionally(error -> report(sender, error));
        } else {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(plugin.getMessage("player-not-found"));
                return;
            }

            api.createBackup(target, request)
                    .thenAccept(handle -> {
                        if (handle.isPresent()) {
                            sendBackupNotification(sender, "backup-created", "player", target.getName());
                        }
                    })
                    .exceptionally(error -> report(sender, error));
        }
    }

    private void sendBackupNotification(CommandSender sender, String messageKey, String placeholder, String value) {
        if (!plugin.getConfig().getBoolean("notify-on-backup", true)) {
            return;
        }

        boolean opsOnly = plugin.getConfig().getBoolean("notify-ops-only", true);
        boolean shouldNotify = opsOnly ? sender.isOp() : (sender.hasPermission("inventorybackup.notify") || sender.isOp());

        if (shouldNotify) {
            sender.sendMessage(plugin.getMessage(messageKey, placeholder, value));
        }
    }

    private void handleRestore(CommandSender sender, String playerName, String fileName) {
        Optional<UUID> owner = resolve(sender, playerName);
        if (!owner.isPresent()) {
            return;
        }

        InventoryBackupService api = plugin.getApiService();
        withBackup(sender, owner.get(), fileName, handle ->
                api.restore(owner.get(), handle, RestoreOptions.all())
                        .thenAccept(result -> sender.sendMessage(restoreMessage(result, playerName)))
                        .exceptionally(error -> report(sender, error)));
    }

    private String restoreMessage(RestoreResult result, String playerName) {
        switch (result) {
            case APPLIED:
                return plugin.getMessage("inventory-restored", "player", playerName);
            case QUEUED_FOR_JOIN:
                return plugin.getMessage("restore-queued", "player", playerName);
            case INVALID_BACKUP: return plugin.getMessage("restore-invalid");
            case INCOMPATIBLE_VERSION: return plugin.getMessage("restore-incompatible");
            case INSUFFICIENT_SPACE: return plugin.getMessage("restore-space");
            case FAILED: return plugin.getMessage("operation-failed");
            case CANCELLED:
                return plugin.getMessage("restore-cancelled", "player", playerName);
            default:
                return plugin.getMessage("no-inventory-found");
        }
    }

    private void handleShow(CommandSender sender, String playerName, String fileName) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getMessage("player-only"));
            return;
        }

        Player viewer = (Player) sender;
        Optional<UUID> owner = resolve(sender, playerName);
        if (!owner.isPresent()) {
            return;
        }

        withBackup(sender, owner.get(), fileName, handle -> {
            plugin.getApiService().openPreview(viewer, handle);
            sender.sendMessage(plugin.getMessage("inventory-shown", "player", playerName));
        });
    }

    private void handleGiveMissing(CommandSender sender, String playerName, String fileName) {
        Optional<UUID> owner = resolve(sender, playerName);
        if (!owner.isPresent()) {
            return;
        }

        if (Bukkit.getPlayer(owner.get()) == null) {
            sender.sendMessage(plugin.getMessage("player-not-found"));
            return;
        }

        withBackup(sender, owner.get(), fileName, handle ->
                plugin.getApiService().giveMissingItems(owner.get(), handle)
                        .thenAccept(given -> {
                            if (given < 0) {
                                sender.sendMessage(plugin.getMessage("no-inventory-found"));
                            } else {
                                sender.sendMessage(plugin.getMessage("missing-items-given",
                                        "count", String.valueOf(given), "player", playerName));
                            }
                        })
                        .exceptionally(error -> report(sender, error)));
    }

    private void handleList(CommandSender sender, String playerName) {
        Optional<UUID> owner = resolve(sender, playerName);
        if (!owner.isPresent()) {
            return;
        }

        plugin.getApiService().listBackups(owner.get())
                .thenAccept(backups -> {
                    if (backups.isEmpty()) {
                        sender.sendMessage(plugin.getMessage("no-inventory-found"));
                        return;
                    }

                    // Ohne Prefix: vor jeder Listenzeile waere er nur Rauschen.
                    LanguageManager languages = plugin.getLanguageManager();
                    sender.sendMessage(languages.getRaw("list-header", "player", playerName));
                    for (BackupHandle backup : backups) {
                        sender.sendMessage(languages.getRaw("list-entry",
                                "file", backup.id(),
                                "type", backup.type(),
                                "source", backup.sourcePlugin() == null ? "-" : backup.sourcePlugin()));
                    }
                })
                .exceptionally(error -> report(sender, error));
    }

    private void handleDelete(CommandSender sender, String playerName, String fileName) {
        Optional<UUID> owner = resolve(sender, playerName);
        if (!owner.isPresent()) {
            return;
        }

        InventoryBackupService api = plugin.getApiService();

        if (fileName.equalsIgnoreCase("all")) {
            api.deleteBackups(owner.get(), null, BackupDeletedEvent.Reason.COMMAND)
                    .thenAccept(count -> sender.sendMessage(plugin.getMessage("all-backups-deleted",
                            "count", String.valueOf(count), "player", playerName)))
                    .exceptionally(error -> report(sender, error));
            return;
        }

        withBackup(sender, owner.get(), fileName, handle ->
                api.deleteBackup(handle, BackupDeletedEvent.Reason.COMMAND)
                        .thenAccept(deleted -> sender.sendMessage(deleted
                                ? plugin.getMessage("backup-deleted", "filename", handle.id())
                                : plugin.getMessage("no-inventory-found")))
                        .exceptionally(error -> report(sender, error)));
    }

    private void handlePending(CommandSender sender, String playerName) {
        Optional<UUID> target = resolve(sender, playerName);
        if (!target.isPresent()) {
            return;
        }

        var stored = plugin.getPendingRestores().get(target.get()).orElse(null);
        if (stored != null && stored.failure() != null) {
            sender.sendMessage(plugin.getMessage("pending-failed", "file", stored.backupId(), "error", stored.failure()));
            return;
        }
        plugin.getApiService().getPendingRestore(target.get())
                .thenAccept(pending -> sender.sendMessage(pending
                        .map(entry -> plugin.getMessage("pending-info",
                                "player", playerName, "file", entry.handle().id()))
                        .orElseGet(() -> plugin.getMessage("pending-none", "player", playerName))))
                .exceptionally(error -> report(sender, error));
    }

    private void handleCancelPending(CommandSender sender, String playerName) {
        Optional<UUID> target = resolve(sender, playerName);
        if (!target.isPresent()) {
            return;
        }

        plugin.getApiService().cancelPendingRestore(target.get())
                .thenAccept(cancelled -> sender.sendMessage(cancelled
                        ? plugin.getMessage("pending-cancelled", "player", playerName)
                        : plugin.getMessage("pending-none", "player", playerName)))
                .exceptionally(error -> report(sender, error));
    }

    /**
     * Looks a backup up and hands it to {@code action}, or tells the sender it
     * does not exist. The callback runs on the main thread.
     */
    private void withBackup(CommandSender sender, UUID owner, String fileName,
                            java.util.function.Consumer<BackupHandle> action) {
        plugin.getApiService().getBackup(owner, fileName)
                .thenAccept(handle -> {
                    if (handle.isPresent()) {
                        action.accept(handle.get());
                    } else {
                        sender.sendMessage(plugin.getMessage("no-inventory-found"));
                    }
                })
                .exceptionally(error -> report(sender, error));
    }

    /**
     * Logs a failed future. Without this the exception disappears into the
     * CompletableFuture and the sender is left staring at nothing.
     */
    private Void report(CommandSender sender, Throwable error) {
        plugin.getLogger().severe(plugin.getLanguageManager().getConsoleMsg(
                "command-failed", "error", String.valueOf(error.getMessage())));
        if (plugin.isEnabled()) plugin.getApiService().mainCall(() -> {
            sender.sendMessage(plugin.getMessage("operation-failed")); return true;
        });
        return null;
    }

    /** /inv reload -- config.yml und Sprachbundle neu einlesen. */
    private void handleReload(CommandSender sender) {
        if (!require(sender, "inventorybackup.reload")) {
            return;
        }

        try { plugin.reloadAll(); } catch (RuntimeException error) { report(sender, error); return; }
        sender.sendMessage(plugin.getMessage("reloaded"));
    }

    /** /inv lang [en|de] -- Sprache anzeigen oder wechseln. */
    private void handleLanguage(CommandSender sender, String[] args) {
        if (!require(sender, "inventorybackup.lang")) {
            return;
        }

        LanguageManager languages = plugin.getLanguageManager();

        if (args.length < 2) {
            sender.sendMessage(plugin.getMessage("lang-current",
                    "lang", languages.getLanguage(),
                    "available", languages.getSupportedLanguagesDisplay()));
            return;
        }

        String requested = args[1];
        if (!languages.setLanguage(requested)) {
            sender.sendMessage(plugin.getMessage("lang-invalid",
                    "lang", requested,
                    "available", languages.getSupportedLanguagesDisplay()));
            return;
        }

        // setLanguage hat das Bundle bereits neu geladen, die Bestaetigung
        // kommt also schon in der neuen Sprache.
        sender.sendMessage(plugin.getMessage("lang-changed", "lang", languages.getLanguage()));
    }

    private void handleVersion(CommandSender sender) {
        String currentVersion = plugin.getDescription().getVersion();
        sender.sendMessage(plugin.getMessage("update-current", "version", currentVersion));

        UpdateChecker checker = plugin.getUpdateChecker();
        if (!plugin.getConfig().getBoolean("update-check.enabled", true) || checker == null) {
            sender.sendMessage(plugin.getMessage("update-disabled"));
            return;
        }

        sender.sendMessage(plugin.getMessage("update-checking"));

        // Callback statt geratener Wartezeit: die Ausgabe kommt, sobald das
        // Ergebnis da ist -- und niemals vorher.
        checker.check(result -> {
            if (!result.hasChecked()) {
                sender.sendMessage(plugin.getMessage("update-failed"));
            } else if (result.isUpdateAvailable()) {
                sender.sendMessage(plugin.getMessage("update-available",
                        "current", result.getCurrentVersion(),
                        "latest", result.getLatestVersion(),
                        "version", result.getLatestVersion()));
                sender.sendMessage(plugin.getMessage("update-download",
                        "url", result.getDownloadUrl()));
            } else {
                sender.sendMessage(plugin.getMessage("update-up-to-date"));
            }
        });
    }

    /**
     * Provides tab completion for the /inv command.
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(SUBCOMMANDS);
            // Offline players too: restore and delete work on them now, so a
            // completion list of online players only would hide half the archive.
            completions.addAll(plugin.getPlayerIndex().knownNames());
            addOnlinePlayers(completions);
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("backup")) {
                completions.add("all");
                addOnlinePlayers(completions);
            } else if (args[0].equalsIgnoreCase("lang")) {
                completions.addAll(plugin.getLanguageManager().getSupportedLanguages());
            } else if (!isSubcommand(args[0])) {
                completions.addAll(ACTIONS);
            }
        } else if (args.length == 3 && !isSubcommand(args[0])) {
            if (args[1].equalsIgnoreCase("delete")) {
                completions.add("all");
            }
            completions.addAll(backupFileNames(args[0]));
        }

        String prefix = args[args.length - 1].toLowerCase();
        return completions.stream()
                .distinct()
                .filter(s -> s.toLowerCase().startsWith(prefix))
                .collect(Collectors.toList());
    }

    private static void addOnlinePlayers(List<String> completions) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            completions.add(player.getName());
        }
    }

    /** Backup file names of a player, read straight off disk. */
    private List<String> backupFileNames(String playerName) {
        List<String> names = new ArrayList<>();
        Optional<UUID> owner = plugin.getApiService().resolveNow(playerName);
        if (!owner.isPresent()) {
            return names;
        }

        names.addAll(plugin.getInventoryManager().cachedFileNames(owner.get()));
        return names;
    }
}
