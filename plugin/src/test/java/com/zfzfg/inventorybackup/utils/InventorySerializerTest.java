package com.zfzfg.inventorybackup.utils;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.bukkit.Material;
import com.zfzfg.inventorybackup.platform.TestPlatforms;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

public class InventorySerializerTest {
    @BeforeEach void setup() { MockBukkit.mock(); }
    @AfterEach void close() { MockBukkit.unmock(); }

    @Test void legacyHelpersRoundTripEmptySlotsAndMultilineBase64() {
        ItemStack[] inventory = new ItemStack[41];
        String data = InventorySerializer.serializeInventory(inventory);
        assertArrayEquals(inventory, InventorySerializer.deserializeInventory(data));
        String multiline = Base64.getMimeEncoder(76, new byte[]{'\r', '\n'})
                .encodeToString(Base64.getDecoder().decode(data));
        assertArrayEquals(inventory, InventorySerializer.deserializeInventory(multiline));
        // Populated legacy items require CraftBukkit's real ItemMeta implementations;
        // ServerTests checks those without broadening the production input filter for mocks.
        assertArrayEquals(new ItemStack[4], InventorySerializer.deserializeInventory(
                InventorySerializer.serializeInventory(new ItemStack[4])));
    }

    @Test void explicitCodecRoundTripAndFormatsCannotBeConfused() {
        var codec = TestPlatforms.mock().itemCodec();
        ItemStack[] inventory = new ItemStack[36]; inventory[2] = new ItemStack(Material.GOLD_INGOT, 12);
        String nbt = InventorySerializer.encodeInventory(inventory, codec);
        assertArrayEquals(inventory, InventorySerializer.decodeInventory(nbt, codec));
        assertThrows(IllegalArgumentException.class, () -> InventorySerializer.deserializeInventory(nbt));
        assertThrows(IllegalArgumentException.class, () -> InventorySerializer.decodeInventory(
                InventorySerializer.serializeInventory(inventory), codec));
        assertEquals(inventory[2], InventorySerializer.decodeItem(InventorySerializer.encodeItem(inventory[2], codec), codec));
        assertNull(InventorySerializer.decodeItem(InventorySerializer.encodeItem(null, codec), codec));
        assertThrows(IllegalArgumentException.class, () -> InventorySerializer.serializeInventory(new ItemStack[42]));
    }
    @Test void cyclicLegacyCollectionsAreRejectedWithoutRecursionFailure() throws Exception {
        var cyclic = new java.util.ArrayList<Object>(); cyclic.add(cyclic);
        var bytes = new java.io.ByteArrayOutputStream();
        try (var out = new java.io.ObjectOutputStream(bytes)) {
            out.writeInt(1); out.writeObject(cyclic);
        }
        assertThrows(IllegalArgumentException.class, () -> InventorySerializer.deserializeInventory(
                Base64.getEncoder().encodeToString(bytes.toByteArray())));
    }

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
