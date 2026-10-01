package com.zfzfg.inventorybackup.testing;

import com.zfzfg.inventorybackup.InventoryBackup;
import com.zfzfg.inventorybackup.api.*;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.block.ShulkerBox;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;

/** Runs only on disposable servers created by tests/run_servers.py. */
public final class ServerTests extends JavaPlugin {
    private InventoryBackup plugin;
    @Override public void onEnable() {
        plugin = (InventoryBackup) Bukkit.getPluginManager().getPlugin("InventoryBackup");
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try { run(); } catch (Throwable error) { finish(error); }
        }, 20);
    }
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private void run() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta(); meta.setDisplayName("NBT test sword"); meta.setLore(List.of("Lore survives", "Second line"));
        meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 3, false);
        meta.getPersistentDataContainer().set(new NamespacedKey(this, "marker"), PersistentDataType.STRING, "preserved");
        meta.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_DAMAGE,
                new org.bukkit.attribute.AttributeModifier(new NamespacedKey(this, "damage"), 2,
                        org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.HAND));
        sword.setItemMeta(meta);
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK); BookMeta bm = (BookMeta) book.getItemMeta();
        bm.setTitle("Saved book"); bm.setAuthor("ServerTests"); bm.setPages("Page one", "Page two"); book.setItemMeta(bm);
        ItemStack potion = new ItemStack(Material.POTION); PotionMeta pm = (PotionMeta) potion.getItemMeta();
        pm.setBasePotionType(org.bukkit.potion.PotionType.HEALING); potion.setItemMeta(pm);
        ItemStack box = new ItemStack(Material.SHULKER_BOX); BlockStateMeta sm = (BlockStateMeta) box.getItemMeta();
        ShulkerBox state = (ShulkerBox) sm.getBlockState(); state.getInventory().setItem(0, book.clone()); sm.setBlockState(state); box.setItemMeta(sm);
        ItemStack[] items = new ItemStack[36]; items[0] = sword; items[1] = book; items[2] = potion; items[3] = box;
        ItemStack[] armor = new ItemStack[4]; armor[0] = new ItemStack(Material.DIAMOND_BOOTS);
        BackupSnapshot snapshot = new BackupSnapshot(null, items, armor, new ItemStack(Material.SHIELD), 35, .25f);
        UUID owner = UUID.nameUUIDFromBytes("integration-owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var api = plugin.getApiService();
        api.createBackup(owner, "Integration", snapshot, BackupRequest.of("integration"))
            .thenCompose(handle -> api.loadBackup(handle.orElseThrow()))
            .thenCompose(loaded -> api.mainCall(() -> {
                BackupSnapshot result = loaded.orElseThrow();
                for (int i = 0; i < 4; i++) check(items[i].equals(result.contents()[i]), "Item data mismatch at slot " + i);
                check(result.armor()[0].equals(armor[0]), "Armor changed");
                check(result.offhand().getType() == Material.SHIELD && result.level() == 35 && result.exp() == .25f, "Snapshot state changed");
                return true;
            }))
            .thenCompose(ok -> api.background(() -> plugin.getInventoryManager().readBackup(owner, "upgrade.yml")))
            .thenCompose(upgraded -> api.mainCall(() -> {
                if (upgraded.isPresent()) {
                    for (int i = 0; i < 4; i++) check(items[i].equals(upgraded.get().contents()[i]), "Cross-version item changed " + i);
                    getLogger().info("INVENTORYBACKUP_UPGRADE_TEST_PASS");
                }
                return true;
            }))
            .thenCompose(ok -> {
                // A real legacy ObjectStream fixture exercises the restricted reader and 41-slot normalization.
                return api.mainCall(() -> {
                    var config = new org.bukkit.configuration.file.YamlConfiguration();
                    config.set("uuid", owner.toString()); config.set("player", "Integration"); config.set("timestamp", System.currentTimeMillis());
                    config.set("inventory", legacyInventory(java.util.Arrays.copyOf(items, 41)));
                    config.set("armor", legacyInventory(armor)); config.set("offhand", legacyItem(new ItemStack(Material.SHIELD)));
                    config.set("level", 35); config.set("exp", .25);
                    return config.saveToString();
                }).thenCompose(document -> api.background(() -> {
                    var file = plugin.getDataFolder().toPath().resolve("inventories").resolve(owner.toString()).resolve("legacy.yml");
                    try { java.nio.file.Files.writeString(file, document); } catch (Exception error) { throw new CompletionException(error); }
                    return plugin.getInventoryManager().readBackup(owner, "legacy.yml").orElseThrow();
                })).thenCompose(result -> api.mainCall(() -> {
                    check(result.contents().length == 36, "Legacy slots not normalized");
                    for (int i = 0; i < 4; i++) check(items[i].equals(result.contents()[i]), "Legacy item changed " + i);
                    return true;
                }));
            })
            .thenCompose(ok -> {
                List<CompletableFuture<Optional<BackupHandle>>> writes = new ArrayList<>();
                for (int i = 0; i < 12; i++) writes.add(api.createBackup(owner, "Integration", snapshot, BackupRequest.of("parallel")));
                return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
            })
            .thenCompose(ok -> api.listBackups(owner, "parallel"))
            .thenCompose(handles -> api.mainCall(() -> { check(handles.size() == 12, "Parallel writes lost backups"); return true; }))
            .whenComplete((ok, error) -> { if (Bukkit.isPrimaryThread()) finish(error); else Bukkit.getScheduler().runTask(this, () -> finish(error)); });
    }
    private String legacyInventory(ItemStack[] items) {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            try (var out = new org.bukkit.util.io.BukkitObjectOutputStream(bytes)) {
                out.writeInt(items.length); for (ItemStack item : items) out.writeObject(item);
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception error) { throw new CompletionException(error); }
    }
    private String legacyItem(ItemStack item) {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            try (var out = new org.bukkit.util.io.BukkitObjectOutputStream(bytes)) { out.writeObject(item); }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception error) { throw new CompletionException(error); }
    }
    private void finish(Throwable error) {
        if (error == null) getLogger().info("INVENTORYBACKUP_SERVER_TEST_PASS " + Bukkit.getVersion());
        else getLogger().log(java.util.logging.Level.SEVERE, "INVENTORYBACKUP_SERVER_TEST_FAIL", error);
        Bukkit.shutdown();
    }
}
