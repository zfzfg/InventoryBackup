package com.zfzfg.inventorybackup.utils;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class InventorySerializerTest {

    @Test
    public void testDeserializeNullOrEmpty() {
        ItemStack[] items = InventorySerializer.deserializeInventory(null);
        assertNotNull(items);
        assertEquals(0, items.length);

        items = InventorySerializer.deserializeInventory("");
        assertNotNull(items);
        assertEquals(0, items.length);

        ItemStack item = InventorySerializer.deserializeItemStack(null);
        assertNull(item);

        item = InventorySerializer.deserializeItemStack("");
        assertNull(item);
    }

    @Test
    public void testSerializeNullItem() {
        String result = InventorySerializer.serializeItemStack(null);
        assertEquals("", result);
    }
}
