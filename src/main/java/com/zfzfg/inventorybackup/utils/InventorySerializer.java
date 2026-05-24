package com.zfzfg.inventorybackup.utils;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public class InventorySerializer {
    
    /**
     * Serializes an array of ItemStacks to a Base64 string.
     * @param items The ItemStack array to serialize
     * @return Base64 encoded string, or empty string on failure
     */
    public static String serializeInventory(ItemStack[] items) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            
            dataOutput.writeInt(items.length);
            
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }
            
            dataOutput.close();
            return Base64Coder.encodeLines(outputStream.toByteArray());
        } catch (Exception e) {
            Bukkit.getLogger().severe("Failed to serialize inventory: " + e.getMessage());
            return "";
        }
    }
    
    /**
     * Deserializes a Base64 string to an ItemStack array.
     * @param data The Base64 encoded string
     * @return ItemStack array, or empty array on failure
     */
    public static ItemStack[] deserializeInventory(String data) {
        if (data == null || data.isEmpty()) {
            return new ItemStack[0];
        }
        
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            
            int size = dataInput.readInt();
            ItemStack[] items = new ItemStack[size];
            
            for (int i = 0; i < size; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }
            
            dataInput.close();
            return items;
        } catch (Exception e) {
            Bukkit.getLogger().severe("Failed to deserialize inventory: " + e.getMessage());
            return new ItemStack[0];
        }
    }
    
    /**
     * Serializes a single ItemStack to a Base64 string.
     * @param item The ItemStack to serialize
     * @return Base64 encoded string, or empty string on failure
     */
    public static String serializeItemStack(ItemStack item) {
        if (item == null) {
            return "";
        }
        
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            
            dataOutput.writeObject(item);
            dataOutput.close();
            
            return Base64Coder.encodeLines(outputStream.toByteArray());
        } catch (Exception e) {
            Bukkit.getLogger().severe("Failed to serialize ItemStack: " + e.getMessage());
            return "";
        }
    }
    
    /**
     * Deserializes a Base64 string to a single ItemStack.
     * @param data The Base64 encoded string
     * @return ItemStack, or null on failure
     */
    public static ItemStack deserializeItemStack(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            
            ItemStack item = (ItemStack) dataInput.readObject();
            dataInput.close();
            
            return item;
        } catch (Exception e) {
            Bukkit.getLogger().severe("Failed to deserialize ItemStack: " + e.getMessage());
            return null;
        }
    }
}
