package com.zfzfg.inventorybackup.testing;

import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.block.ShulkerBox;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** Calls the original release's writer; never constructs backup YAML or payloads itself. */
public final class HistoricalFixtures extends JavaPlugin {
    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try { export(); } catch (Throwable error) { finish(error); }
        }, 20);
    }
    private void export() throws Exception {
        var plugin = Bukkit.getPluginManager().getPlugin("InventoryBackup");
        UUID owner = UUID.nameUUIDFromBytes("historical-owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.setDisplayName("Historical sword"); meta.setLore(List.of("Historical lore", "Second line"));
        meta.addEnchant(org.bukkit.enchantments.Enchantment.DURABILITY, 3, false);
        meta.getPersistentDataContainer().set(new NamespacedKey("inventorybackupfixtures", "marker"), PersistentDataType.STRING, "preserved");
        sword.setItemMeta(meta);
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta bm = (BookMeta) book.getItemMeta(); bm.setTitle("Historical book"); bm.setAuthor("Fixtures");
        bm.setPages("Page one", "Page two"); book.setItemMeta(bm);
        ItemStack potion = new ItemStack(Material.POTION);
        PotionMeta pm = (PotionMeta) potion.getItemMeta();
        pm.setBasePotionData(new org.bukkit.potion.PotionData(org.bukkit.potion.PotionType.INSTANT_HEAL)); potion.setItemMeta(pm);
        ItemStack box = new ItemStack(Material.SHULKER_BOX);
        BlockStateMeta sm = (BlockStateMeta) box.getItemMeta();
        ShulkerBox state = (ShulkerBox) sm.getBlockState(); state.getInventory().setItem(0, book.clone());
        sm.setBlockState(state); box.setItemMeta(sm);
        ItemStack[] items = new ItemStack[36]; items[0] = sword; items[1] = book; items[2] = potion; items[3] = box;
        ItemStack[] armor = new ItemStack[4]; armor[0] = new ItemStack(Material.DIAMOND_BOOTS);
        ItemStack offhand = new ItemStack(Material.SHIELD);
        if (plugin.getDescription().getVersion().equals("0.0.7")) {
            Object manager = plugin.getClass().getMethod("getInventoryManager").invoke(plugin);
            ItemStack[] legacy = Arrays.copyOf(items, 41);
            System.arraycopy(armor, 0, legacy, 36, 4); legacy[40] = offhand;
            boolean saved = (boolean) manager.getClass().getMethod("saveInventoryDirect", String.class, UUID.class,
                    ItemStack[].class, ItemStack[].class, ItemStack.class, int.class, float.class, String.class)
                    .invoke(manager, "Historical", owner, legacy, armor, offhand, 35, .25f, "historical");
            if (!saved) throw new IllegalStateException("Original 0.0.7 writer failed");
            finish(null);
        } else {
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> snapshot = loader.loadClass("com.zfzfg.inventorybackup.api.BackupSnapshot");
            Class<?> handle = loader.loadClass("com.zfzfg.inventorybackup.api.BackupHandle");
            Object value = snapshot.getConstructor(handle, ItemStack[].class, ItemStack[].class, ItemStack.class, int.class, float.class)
                    .newInstance(null, items, armor, offhand, 35, .25f);
            Class<?> request = loader.loadClass("com.zfzfg.inventorybackup.api.BackupRequest");
            Object spec = request.getMethod("of", String.class).invoke(null, "historical");
            Object service = plugin.getClass().getMethod("getApiService").invoke(plugin);
            CompletionStage<?> write = (CompletionStage<?>) service.getClass().getMethod("createBackup", UUID.class, String.class, snapshot, request)
                    .invoke(service, owner, "Historical", value, spec);
            write.whenComplete((result, error) -> Bukkit.getScheduler().runTask(this, () -> {
                if (error == null && result instanceof Optional<?> optional && optional.isEmpty())
                    finish(new IllegalStateException("Original writer returned empty"));
                else finish(error);
            }));
        }
    }
    private void finish(Throwable error) {
        if (error == null) getLogger().info("INVENTORYBACKUP_HISTORICAL_EXPORT_PASS");
        else getLogger().log(java.util.logging.Level.SEVERE, "INVENTORYBACKUP_HISTORICAL_EXPORT_FAIL", error);
        Bukkit.shutdown();
    }
}
