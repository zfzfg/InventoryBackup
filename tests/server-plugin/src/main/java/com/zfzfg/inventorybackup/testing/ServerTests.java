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
        sword = withCustomData(sword);
        check("preserved".equals(sword.getItemMeta().getPersistentDataContainer().get(
                new NamespacedKey(this, "marker"), PersistentDataType.STRING)), "Custom component fixture lost its PDC");
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
        check(Arrays.equals(items, com.zfzfg.inventorybackup.utils.InventorySerializer.deserializeInventory(
                com.zfzfg.inventorybackup.utils.InventorySerializer.serializeInventory(items))), "Legacy helpers do not round-trip");
        check(sword.equals(com.zfzfg.inventorybackup.utils.InventorySerializer.deserializeItemStack(
                com.zfzfg.inventorybackup.utils.InventorySerializer.serializeItemStack(sword))), "Legacy item helper does not round-trip");
        var api = plugin.getApiService();
        // Transfer fixtures are produced on a different real server, never re-encoded here.
        UUID historicalOwner = UUID.nameUUIDFromBytes("historical-owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        api.listBackups(historicalOwner, "historical").thenCompose(handles -> {
            CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(true);
            for (var handle : handles) chain = chain.thenCompose(ok -> api.loadBackup(handle))
                    .thenCompose(result -> api.mainCall(() -> {
                        HistoricalChecks.compare(result.orElseThrow());
                        getLogger().info("INVENTORYBACKUP_HISTORICAL_LOAD_PASS " + handle.id());
                        return true;
                    }));
            return chain;
        }).thenCompose(ok -> api.background(() -> {
            var folder = plugin.getDataFolder().toPath().resolve("inventories").resolve(owner.toString());
            if (!java.nio.file.Files.exists(folder)) return List.<String>of();
            try (var files = java.nio.file.Files.list(folder)) {
                return files.map(path -> path.getFileName().toString()).filter(name -> name.startsWith("transfer-")).sorted().toList();
            } catch (Exception error) { throw new CompletionException(error); }
        })).thenCompose(names -> {
            CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(true);
            for (String name : names) chain = chain.thenCompose(ok -> api.background(() ->
                    plugin.getInventoryManager().readBackup(owner, name).orElseThrow()))
                    .thenCompose(result -> api.mainCall(() -> {
                        compare(snapshot, result);
                        getLogger().info("INVENTORYBACKUP_TRANSFER_PASS " + name);
                        return true;
                    }));
            return chain;
        }).thenCompose(ok -> api.createBackup(owner, "Integration", snapshot, BackupRequest.of("integration")))
            .thenCompose(handle -> api.loadBackup(handle.orElseThrow()))
            .thenCompose(loaded -> api.mainCall(() -> {
                BackupSnapshot result = loaded.orElseThrow();
                compare(snapshot, result);
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
    private void compare(BackupSnapshot expected, BackupSnapshot actual) {
        check(Arrays.equals(expected.contents(), actual.contents()), "Inventory changed");
        check(Arrays.equals(expected.armor(), actual.armor()), "Armor changed");
        check(Objects.equals(expected.offhand(), actual.offhand()), "Offhand changed");
        check(expected.level() == actual.level() && expected.exp() == actual.exp(), "XP changed");
        var codec = plugin.getPlatform().itemCodec();
        for (int i = 0; i < expected.contents().length; i++) {
            ItemStack item = expected.contents()[i];
            if (item != null) check(nbtTag(codec.encode(item)).equals(nbtTag(codec.encode(actual.contents()[i]))),
                    "Full NBT/components changed at slot " + i);
        }
    }
    private Object nbtTag(byte[] bytes) {
        try {
            Class<?> io;
            try { io = Class.forName("net.minecraft.nbt.NbtIo"); }
            catch (ClassNotFoundException ignored) { io = Class.forName("net.minecraft.nbt.NBTCompressedStreamTools"); }
            for (var read : io.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(read.getModifiers()) && read.getParameterCount() == 2
                        && read.getParameterTypes()[0] == java.io.InputStream.class
                        && read.getReturnType().getName().matches(".*\\.(CompoundTag|NBTTagCompound)")) {
                    Class<?> limiter = read.getParameterTypes()[1];
                    for (var create : limiter.getMethods()) {
                        if (java.lang.reflect.Modifier.isStatic(create.getModifiers()) && create.getReturnType() == limiter
                                && Arrays.equals(create.getParameterTypes(), new Class<?>[]{long.class}))
                            return read.invoke(null, new java.io.ByteArrayInputStream(bytes), create.invoke(null, 16777216L));
                    }
                }
            }
            throw new IllegalStateException("No test NBT reader");
        } catch (ReflectiveOperationException error) { throw new CompletionException(error); }
    }
    private ItemStack withCustomData(ItemStack item) {
        try {
            var codec = plugin.getPlatform().itemCodec();
            Object root = nbtTag(codec.encode(item));
            Class<?> tag = root.getClass();
            Class<?> opsType;
            try { opsType = Class.forName("net.minecraft.nbt.NbtOps"); }
            catch (ClassNotFoundException ignored) { opsType = Class.forName("net.minecraft.nbt.DynamicOpsNBT"); }
            Object ops = null;
            for (var field : opsType.getFields())
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && opsType.isAssignableFrom(field.getType())) ops = field.get(null);
            Class<?> dynamic = Class.forName("com.mojang.serialization.Dynamic");
            Object data = dynamic.getConstructor(Class.forName("com.mojang.serialization.DynamicOps"), Object.class).newInstance(ops, root);
            Object component = dynamic.getMethod("get", String.class).invoke(data, "components");
            Object value = ((Optional<?>) Class.forName("com.mojang.serialization.OptionalDynamic").getMethod("result").invoke(component)).orElseThrow();
            Object components = dynamic.getMethod("getValue").invoke(value);
            Object componentDynamic = dynamic.getConstructor(Class.forName("com.mojang.serialization.DynamicOps"), Object.class).newInstance(ops, components);
            Object customOptional = dynamic.getMethod("get", String.class).invoke(componentDynamic, "minecraft:custom_data");
            var oldCustom = (Optional<?>) Class.forName("com.mojang.serialization.OptionalDynamic").getMethod("result").invoke(customOptional);
            Object custom = oldCustom.isPresent() ? dynamic.getMethod("getValue").invoke(oldCustom.get()) : tag.getConstructor().newInstance();
            Object marker = tag.getConstructor().newInstance();
            java.lang.reflect.Method put = null;
            for (var method : tag.getMethods()) {
                var args = method.getParameterTypes();
                if (args.length != 2 || args[0] != String.class) continue;
                if (args[1] == int.class && method.getReturnType() == void.class) method.invoke(marker, "marker", 42);
                if (args[1] == byte[].class && method.getReturnType() == void.class) method.invoke(marker, "bytes", new byte[]{1, 2, 3});
                if (args[1].getName().matches(".*\\.(Tag|NBTBase)")) put = method;
            }
            if (put == null) throw new IllegalStateException("No test compound writer");
            put.invoke(custom, "inventorybackup_test", marker);
            put.invoke(components, "minecraft:custom_data", custom);
            Class<?> io;
            try { io = Class.forName("net.minecraft.nbt.NbtIo"); }
            catch (ClassNotFoundException ignored) { io = Class.forName("net.minecraft.nbt.NBTCompressedStreamTools"); }
            var bytes = new java.io.ByteArrayOutputStream();
            for (var method : io.getMethods())
                if (Arrays.equals(method.getParameterTypes(), new Class<?>[]{tag, java.io.OutputStream.class})) method.invoke(null, root, bytes);
            return codec.decode(bytes.toByteArray());
        } catch (ReflectiveOperationException error) { throw new CompletionException(error); }
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
