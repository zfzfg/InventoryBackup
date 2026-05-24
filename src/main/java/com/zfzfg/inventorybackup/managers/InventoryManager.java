package com.zfzfg.inventorybackup.managers;

import com.zfzfg.inventorybackup.InventoryBackup;
import com.zfzfg.inventorybackup.utils.InventorySerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.HashMap;

public class InventoryManager {
    
    private final InventoryBackup plugin;
    private final DateTimeFormatter dateFormat;
    private final ConcurrentHashMap<String, ReentrantLock> playerLocks;
    
    public InventoryManager(InventoryBackup plugin) {
        this.plugin = plugin;
        this.dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
        this.playerLocks = new ConcurrentHashMap<>();
    }
    
    /**
     * Saves a player's inventory to a file.
     * @param player The player whose inventory to save
     * @param type The type of backup (e.g., "death", "manual")
     * @return true if successful, false otherwise
     */
    public boolean saveInventory(Player player, String type) {
        ReentrantLock lock = playerLocks.computeIfAbsent(player.getName(), k -> new ReentrantLock());
        lock.lock();
        try {
        File playerFolder = new File(plugin.getDataFolder(), "inventories/" + player.getName());
        if (!playerFolder.exists()) {
            playerFolder.mkdirs();
        }
        
        String timestamp = dateFormat.format(LocalDateTime.now());
        String fileName = timestamp + "_" + type + ".yml";
        File file = new File(playerFolder, fileName);
        
        YamlConfiguration config = new YamlConfiguration();
        
        // Save inventory contents
        config.set("player", player.getName());
        config.set("uuid", player.getUniqueId().toString());
        config.set("timestamp", System.currentTimeMillis());
        config.set("type", type);
        
        // Serialize inventory
        config.set("inventory", InventorySerializer.serializeInventory(player.getInventory().getContents()));
        config.set("armor", InventorySerializer.serializeInventory(player.getInventory().getArmorContents()));
        config.set("offhand", InventorySerializer.serializeItemStack(player.getInventory().getItemInOffHand()));
        
        // Save player level and experience
        config.set("level", player.getLevel());
        config.set("exp", player.getExp());
        
        try {
            config.save(file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save inventory for " + player.getName() + ": " + e.getMessage());
            return false;
        }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Saves inventory data directly without requiring an online player.
     * Used for death events where inventory is cached.
     * @param playerName The player's name
     * @param uuid The player's UUID
     * @param inventory The inventory contents
     * @param armor The armor contents
     * @param offhand The offhand item
     * @param level The player's level
     * @param exp The player's experience
     * @param type The type of backup
     * @return true if successful, false otherwise
     */
    public boolean saveInventoryDirect(String playerName, UUID uuid, ItemStack[] inventory,
                                       ItemStack[] armor, ItemStack offhand, int level, float exp, String type) {
        ReentrantLock lock = playerLocks.computeIfAbsent(playerName, k -> new ReentrantLock());
        lock.lock();
        try {
            File playerFolder = new File(plugin.getDataFolder(), "inventories/" + playerName);
            if (!playerFolder.exists()) {
                playerFolder.mkdirs();
            }
            
            String timestamp = dateFormat.format(LocalDateTime.now());
            String fileName = timestamp + "_" + type + ".yml";
            File file = new File(playerFolder, fileName);
            
            YamlConfiguration config = new YamlConfiguration();
            
            config.set("player", playerName);
            config.set("uuid", uuid.toString());
            config.set("timestamp", System.currentTimeMillis());
            config.set("type", type);
            
            config.set("inventory", InventorySerializer.serializeInventory(inventory));
            config.set("armor", InventorySerializer.serializeInventory(armor));
            config.set("offhand", InventorySerializer.serializeItemStack(offhand));
            
            // Save level and experience
            config.set("level", level);
            config.set("exp", exp);
            
            try {
                config.save(file);
                return true;
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to save inventory for " + playerName + ": " + e.getMessage());
                return false;
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Restores a player's inventory from a saved file.
     * @param player The player to restore inventory to
     * @param fileName The name of the backup file
     * @return true if successful, false otherwise
     */
    public boolean restoreInventory(Player player, String fileName) {
        File file = getInventoryFile(player.getName(), fileName);
        if (file == null || !file.exists()) {
            return false;
        }
        
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        
        // Restore on main thread
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            ItemStack[] inventory = InventorySerializer.deserializeInventory(config.getString("inventory"));
            ItemStack[] armor = InventorySerializer.deserializeInventory(config.getString("armor"));
            ItemStack offhand = InventorySerializer.deserializeItemStack(config.getString("offhand"));
            // Restore level and exp
            int level = config.getInt("level", 0);
            float exp = (float) config.getDouble("exp", 0.0);
            
            player.getInventory().setContents(inventory);
            player.getInventory().setArmorContents(armor);
            player.getInventory().setItemInOffHand(offhand);
            player.setLevel(level);
            player.setExp(exp);
            
            player.updateInventory();
        });
        
        return true;
    }
    
    /**
     * Shows a saved inventory in a GUI to a viewer.
     * @param viewer The player viewing the inventory
     * @param playerName The name of the player whose inventory is being viewed
     * @param fileName The name of the backup file
     * @return true if successful, false otherwise
     */
    public boolean showInventory(Player viewer, String playerName, String fileName) {
        File file = getInventoryFile(playerName, fileName);
        if (file == null || !file.exists()) {
            return false;
        }
        
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        
        // Read level and exp from saved file
        int savedLevel = config.getInt("level", 0);

        // Show inventory on main thread
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            ItemStack[] inventory = InventorySerializer.deserializeInventory(config.getString("inventory"));
            
            // Create GUI
            Inventory gui = Bukkit.createInventory(null, 54, "§6Inventory: " + playerName);
            
            // Add items to GUI
            for (int i = 0; i < inventory.length && i < 36; i++) {
                if (inventory[i] != null) {
                    gui.setItem(i, inventory[i]);
                }
            }
            
            // Add armor in separate slots
            ItemStack[] armor = InventorySerializer.deserializeInventory(config.getString("armor"));
            if (armor.length >= 4) {
                gui.setItem(45, armor[3]); // Helmet
                gui.setItem(46, armor[2]); // Chestplate
                gui.setItem(47, armor[1]); // Leggings
                gui.setItem(48, armor[0]); // Boots
            }
            
            // Add offhand
            ItemStack offhand = InventorySerializer.deserializeItemStack(config.getString("offhand"));
            if (offhand != null) {
                gui.setItem(49, offhand);
            }
            
            viewer.openInventory(gui);
            
                // Show level message
                String levelMessage = plugin.getMessage("inventory-level")
                    .replace("{player}", playerName)
                    .replace("{level}", String.valueOf(savedLevel));
                viewer.sendMessage(levelMessage);
        });
        
        return true;
    }
    
    /**
     * Gives items from a saved inventory that are missing from the player's current inventory.
     * @param player The player to give items to
     * @param fileName The name of the backup file
     * @return 0 if successful (message sent asynchronously), -1 if file not found
     */
    public int giveMissingItems(Player player, String fileName) {
        File file = getInventoryFile(player.getName(), fileName);
        if (file == null || !file.exists()) {
            return -1;
        }
        
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ItemStack[] savedInventory = InventorySerializer.deserializeInventory(config.getString("inventory"));
        
        // Check and give missing items on main thread to avoid race condition
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Inventory currentInv = player.getInventory();
            int givenCount = 0;
            
            for (ItemStack savedItem : savedInventory) {
                if (savedItem == null) continue;
                
                if (!hasItem(currentInv, savedItem)) {
                    HashMap<Integer, ItemStack> leftover = currentInv.addItem(savedItem);
                    givenCount++;
                    
                    if (!leftover.isEmpty()) {
                        // Drop items that don't fit
                        for (ItemStack item : leftover.values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), item);
                        }
                    }
                }
            }
            
            player.updateInventory();
            
            // Send message with actual count
            String message = plugin.getMessage("missing-items-given")
                    .replace("{count}", String.valueOf(givenCount))
                    .replace("{player}", player.getName());
            player.sendMessage(message);
        });
        
        return 0; // Message is sent asynchronously, return 0 as placeholder
    }
    
    private boolean hasItem(Inventory inventory, ItemStack item) {
        for (ItemStack invItem : inventory.getContents()) {
            if (invItem != null && invItem.isSimilar(item) && invItem.getAmount() >= item.getAmount()) {
                return true;
            }
        }
        return false;
    }
    
    private File getInventoryFile(String playerName, String fileName) {
        // Validate fileName to prevent path traversal attacks
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }
        
        // Only allow alphanumeric, underscores, hyphens, and dots
        if (!fileName.matches("^[a-zA-Z0-9_.-]+$")) {
            plugin.getLogger().warning("Invalid filename detected: " + fileName);
            return null;
        }
        
        if (!fileName.endsWith(".yml")) {
            fileName += ".yml";
        }
        
        File playerFolder = new File(plugin.getDataFolder(), "inventories/" + playerName);
        File file = new File(playerFolder, fileName);
        
        // Ensure the resolved file is still within the player folder
        try {
            String canonicalPath = file.getCanonicalPath();
            String canonicalFolder = playerFolder.getCanonicalPath();
            if (!canonicalPath.startsWith(canonicalFolder)) {
                plugin.getLogger().warning("Path traversal attempt detected: " + fileName);
                return null;
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to validate file path: " + e.getMessage());
            return null;
        }
        
        return file;
    }
    
    /**
     * Lists all backup files for a player.
     * @param playerName The player's name
     * @return List of backup file names, or empty list if none found
     */
    public java.util.List<String> listBackups(String playerName) {
        File playerFolder = new File(plugin.getDataFolder(), "inventories/" + playerName);
        if (!playerFolder.exists() || !playerFolder.isDirectory()) {
            return new java.util.ArrayList<>();
        }
        
        File[] files = playerFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return new java.util.ArrayList<>();
        }
        
        java.util.List<String> fileNames = new java.util.ArrayList<>();
        for (File file : files) {
            fileNames.add(file.getName());
        }
        
        return fileNames;
    }
    
    /**
     * Deletes a specific backup file for a player.
     * @param playerName The player's name
     * @param fileName The name of the backup file
     * @return true if deleted, false otherwise
     */
    public boolean deleteBackup(String playerName, String fileName) {
        File file = getInventoryFile(playerName, fileName);
        if (file == null || !file.exists()) {
            return false;
        }
        
        return file.delete();
    }
    
    /**
     * Deletes all backup files for a player.
     * @param playerName The player's name
     * @return The number of files deleted
     */
    public int deleteAllBackups(String playerName) {
        File playerFolder = new File(plugin.getDataFolder(), "inventories/" + playerName);
        if (!playerFolder.exists() || !playerFolder.isDirectory()) {
            return 0;
        }
        
        File[] files = playerFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return 0;
        }
        
        int deletedCount = 0;
        for (File file : files) {
            if (file.delete()) {
                deletedCount++;
            }
        }
        
        // Delete empty folder
        if (playerFolder.list() != null && playerFolder.list().length == 0) {
            playerFolder.delete();
        }
        
        return deletedCount;
    }
    
    /**
     * Deletes inventory files older than the configured age limit.
     * Optimized to parse timestamp from filename instead of loading YAML.
     * @return The number of files deleted
     */
    public int cleanOldFiles() {
        int deletedCount = 0;
        int maxAgeDays = plugin.getConfig().getInt("auto-delete-days", 30);
        
        if (maxAgeDays <= 0) {
            return 0;
        }
        
        long maxAgeMillis = maxAgeDays * 24L * 60 * 60 * 1000;
        long currentTime = System.currentTimeMillis();
        
        File inventoriesFolder = new File(plugin.getDataFolder(), "inventories");
        if (!inventoriesFolder.exists()) {
            return 0;
        }
        
        File[] playerFolders = inventoriesFolder.listFiles(File::isDirectory);
        if (playerFolders == null) {
            return 0;
        }
        
        for (File playerFolder : playerFolders) {
            File[] files = playerFolder.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files == null) continue;
            
            for (File file : files) {
                // Parse timestamp from filename (format: yyyy-MM-dd_HH-mm-ss_type.yml)
                long timestamp = parseTimestampFromFilename(file.getName());
                
                if (timestamp > 0 && (currentTime - timestamp) > maxAgeMillis) {
                    if (file.delete()) {
                        deletedCount++;
                    }
                }
            }
            
            // Delete empty player folders
            if (playerFolder.list() != null && playerFolder.list().length == 0) {
                playerFolder.delete();
            }
        }
        
        return deletedCount;
    }
    
    /**
     * Parses timestamp from filename in format yyyy-MM-dd_HH-mm-ss_type.yml
     * @param filename The filename to parse
     * @return The timestamp in milliseconds, or 0 if parsing fails
     */
    private long parseTimestampFromFilename(String filename) {
        try {
            // Remove .yml extension and type suffix
            String baseName = filename.substring(0, filename.length() - 4); // Remove .yml
            int lastUnderscore = baseName.lastIndexOf('_');
            if (lastUnderscore > 0) {
                baseName = baseName.substring(0, lastUnderscore);
            }
            
            // Parse the datetime string
            LocalDateTime dateTime = LocalDateTime.parse(baseName, dateFormat);
            return dateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            // Fallback to loading YAML if filename parsing fails
            try {
                File file = new File(plugin.getDataFolder(), "inventories/" + filename);
                if (file.exists()) {
                    YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                    return config.getLong("timestamp", 0);
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to parse timestamp from filename: " + filename);
            }
            return 0;
        }
    }
}
