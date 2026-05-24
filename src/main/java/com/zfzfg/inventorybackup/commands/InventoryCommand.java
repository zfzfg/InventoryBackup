package com.zfzfg.inventorybackup.commands;

import com.zfzfg.inventorybackup.InventoryBackup;
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
import java.util.stream.Collectors;

public class InventoryCommand implements CommandExecutor, TabCompleter {
    
    private final InventoryBackup plugin;
    
    public InventoryCommand(InventoryBackup plugin) {
        this.plugin = plugin;
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
                if (!sender.hasPermission("inventorybackup.restore")) {
                    sender.sendMessage(plugin.getMessage("no-permission"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(plugin.getMessage("invalid-usage"));
                    return true;
                }
                handleRestore(sender, playerName, args[2]);
                break;
                
            case "show":
                if (!sender.hasPermission("inventorybackup.show")) {
                    sender.sendMessage(plugin.getMessage("no-permission"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(plugin.getMessage("invalid-usage"));
                    return true;
                }
                handleShow(sender, playerName, args[2]);
                break;
                
            case "givemissing":
                if (!sender.hasPermission("inventorybackup.givemissing")) {
                    sender.sendMessage(plugin.getMessage("no-permission"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(plugin.getMessage("invalid-usage"));
                    return true;
                }
                handleGiveMissing(sender, playerName, args[2]);
                break;
                
            case "list":
                if (!sender.hasPermission("inventorybackup.show")) {
                    sender.sendMessage(plugin.getMessage("no-permission"));
                    return true;
                }
                handleList(sender, playerName);
                break;
                
            case "delete":
                if (!sender.hasPermission("inventorybackup.restore")) {
                    sender.sendMessage(plugin.getMessage("no-permission"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(plugin.getMessage("invalid-usage"));
                    return true;
                }
                handleDelete(sender, playerName, args[2]);
                break;
                
            default:
                sender.sendMessage(plugin.getMessage("invalid-usage"));
                break;
        }
        
        return true;
    }
    
    private void handleBackup(CommandSender sender, String[] args) {
        if (!sender.hasPermission("inventorybackup.backup")) {
            sender.sendMessage(plugin.getMessage("no-permission"));
            return;
        }
        
        if (args[1].equalsIgnoreCase("all")) {
            // Backup all online players
            int count = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (plugin.getInventoryManager().saveInventory(player, "manual")) {
                    count++;
                }
            }
            sendBackupNotification(sender, "backup-all-created", "{count}", String.valueOf(count));
        } else {
            // Backup specific player
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(plugin.getMessage("player-not-found"));
                return;
            }
            
            if (plugin.getInventoryManager().saveInventory(target, "manual")) {
                sendBackupNotification(sender, "backup-created", "{player}", target.getName());
            }
        }
    }
    
    private void sendBackupNotification(CommandSender sender, String messageKey, String placeholder, String value) {
        if (!plugin.getConfig().getBoolean("notify-on-backup", true)) {
            return;
        }
        
        boolean opsOnly = plugin.getConfig().getBoolean("notify-ops-only", true);
        boolean shouldNotify = opsOnly ? sender.isOp() : (sender.hasPermission("inventorybackup.notify") || sender.isOp());
        
        if (shouldNotify) {
            String message = plugin.getMessage(messageKey).replace(placeholder, value);
            sender.sendMessage(message);
        }
    }
    
    private void handleRestore(CommandSender sender, String playerName, String fileName) {
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage(plugin.getMessage("player-not-found"));
            return;
        }
        
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean restored = plugin.getInventoryManager().restoreInventory(target, fileName);
            
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (restored) {
                    String message = plugin.getMessage("inventory-restored")
                            .replace("{player}", target.getName());
                    sender.sendMessage(message);
                } else {
                    sender.sendMessage(plugin.getMessage("no-inventory-found"));
                }
            });
        });
    }
    
    private void handleShow(CommandSender sender, String playerName, String fileName) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players!");
            return;
        }
        
        Player viewer = (Player) sender;
        
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean shown = plugin.getInventoryManager().showInventory(viewer, playerName, fileName);
            
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (shown) {
                    String message = plugin.getMessage("inventory-shown")
                            .replace("{player}", playerName);
                    sender.sendMessage(message);
                } else {
                    sender.sendMessage(plugin.getMessage("no-inventory-found"));
                }
            });
        });
    }
    
    private void handleGiveMissing(CommandSender sender, String playerName, String fileName) {
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage(plugin.getMessage("player-not-found"));
            return;
        }
        
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            int result = plugin.getInventoryManager().giveMissingItems(target, fileName);
            
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (result < 0) {
                    sender.sendMessage(plugin.getMessage("no-inventory-found"));
                }
                // Message is sent directly to player in giveMissingItems
            });
        });
    }
    
    private void handleList(CommandSender sender, String playerName) {
        java.util.List<String> backups = plugin.getInventoryManager().listBackups(playerName);
        
        if (backups.isEmpty()) {
            sender.sendMessage(plugin.getMessage("no-inventory-found"));
            return;
        }
        
        sender.sendMessage("§6Backups for " + playerName + ":");
        for (String backup : backups) {
            sender.sendMessage("§7- " + backup);
        }
    }
    
    private void handleDelete(CommandSender sender, String playerName, String fileName) {
        if (fileName.equalsIgnoreCase("all")) {
            int count = plugin.getInventoryManager().deleteAllBackups(playerName);
            String message = plugin.getMessage("all-backups-deleted")
                    .replace("{count}", String.valueOf(count))
                    .replace("{player}", playerName);
            sender.sendMessage(message);
        } else {
            boolean deleted = plugin.getInventoryManager().deleteBackup(playerName, fileName);
            if (deleted) {
                String message = plugin.getMessage("backup-deleted")
                        .replace("{filename}", fileName);
                sender.sendMessage(message);
            } else {
                sender.sendMessage(plugin.getMessage("no-inventory-found"));
            }
        }
    }
    
    /**
     * Provides tab completion for the /inv command.
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            completions.add("backup");
            for (Player player : Bukkit.getOnlinePlayers()) {
                completions.add(player.getName());
            }
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("backup")) {
                completions.add("all");
                for (Player player : Bukkit.getOnlinePlayers()) {
                    completions.add(player.getName());
                }
            } else {
                completions.addAll(Arrays.asList("restore", "show", "givemissing", "list", "delete"));
            }
        } else if (args.length == 3 && !args[0].equalsIgnoreCase("backup")) {
            if (args[1].equalsIgnoreCase("delete")) {
                completions.add("all");
            }
            // Get saved files for player
            File playerFolder = new File(plugin.getDataFolder(), "inventories/" + args[0]);
            if (playerFolder.exists() && playerFolder.isDirectory()) {
                File[] files = playerFolder.listFiles((dir, name) -> name.endsWith(".yml"));
                if (files != null) {
                    for (File file : files) {
                        completions.add(file.getName());
                    }
                }
            }
        }
        
        return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                .collect(Collectors.toList());
    }
}
