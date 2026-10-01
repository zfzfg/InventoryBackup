package com.zfzfg.inventorybackup.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDamageListener implements Listener {

    private final Map<UUID, CachedInventory> inventoryCache;

    public PlayerDamageListener() {
        this.inventoryCache = new ConcurrentHashMap<>();
    }
    
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getEntity();
        double finalHealth = player.getHealth() - event.getFinalDamage();
        
        if (finalHealth <= 0 || finalHealth <= 4.0) {
            CachedInventory existing = inventoryCache.get(player.getUniqueId());
            long currentTime = System.currentTimeMillis();
            
            if (existing == null || (currentTime - existing.timestamp) > 1000) {
                // Cache immediately (synchronously) to catch one-shot deaths
                cacheInventory(player);
            }
        }
    }
    
    private void cacheInventory(Player player) {
        ItemStack[] inventory = copy(player.getInventory().getStorageContents());
        ItemStack[] armor = copy(player.getInventory().getArmorContents());
        ItemStack offhand = player.getInventory().getItemInOffHand() == null ? null : player.getInventory().getItemInOffHand().clone();
        int level = player.getLevel();
        float exp = player.getExp();
        
        inventoryCache.put(player.getUniqueId(), 
            new CachedInventory(inventory, armor, offhand, level, exp, System.currentTimeMillis()));
    }
    
    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] result = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) result[i] = items[i] == null ? null : items[i].clone();
        return result;
    }
    public CachedInventory getCachedInventory(UUID playerId) {
        return inventoryCache.get(playerId);
    }
    
    public void removeCachedInventory(UUID playerId) {
        inventoryCache.remove(playerId);
    }
    
    public void cleanOldCache() {
        long currentTime = System.currentTimeMillis();
        inventoryCache.entrySet().removeIf(entry -> 
            currentTime - entry.getValue().timestamp > 30000); // 30 seconds
    }
    
    public static class CachedInventory {
        public final ItemStack[] inventory;
        public final ItemStack[] armor;
        public final ItemStack offhand;
        public final int level;
        public final float exp;
        public final long timestamp;
        
        public CachedInventory(ItemStack[] inventory, ItemStack[] armor, ItemStack offhand, int level, float exp, long timestamp) {
            this.inventory = inventory;
            this.armor = armor;
            this.offhand = offhand;
            this.level = level;
            this.exp = exp;
            this.timestamp = timestamp;
        }
    }
}
