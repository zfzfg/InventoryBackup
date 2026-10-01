package com.zfzfg.inventorybackup;

import com.zfzfg.inventorybackup.api.*;
import com.zfzfg.inventorybackup.managers.*;
import com.zfzfg.inventorybackup.utils.InventorySerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class StabilityTest {
    ServerMock server;
    InventoryBackup plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(InventoryBackup.class); }
    @AfterEach void close() { MockBukkit.unmock(); }
    @Test void snapshotsDoNotShareItems() {
        ItemStack item = new ItemStack(Material.DIAMOND, 5);
        ItemStack[] contents = new ItemStack[36]; contents[0] = item;
        BackupSnapshot snapshot = new BackupSnapshot(null, contents, new ItemStack[4], item, 2, .5f);
        item.setAmount(30); snapshot.contents()[0].setAmount(20); snapshot.offhand().setAmount(10);
        assertEquals(5, snapshot.contents()[0].getAmount()); assertEquals(5, snapshot.offhand().getAmount());
    }
    @Test void invalidSnapshotCannotClearInventory() {
        var player = server.addPlayer(); player.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        BackupSnapshot invalid = new BackupSnapshot(null, new ItemStack[0], new ItemStack[4], null, 0, Float.NaN);
        assertEquals(RestoreResult.INVALID_BACKUP, plugin.getApiService().applyNow(player, invalid, RestoreOptions.all()));
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
    }
    @Test void missingItemsUsesTotalQuantityAndIsRepeatable() {
        var player = server.addPlayer();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        player.getInventory().setItem(1, new ItemStack(Material.DIAMOND, 2));
        ItemStack[] contents = new ItemStack[36]; contents[0] = new ItemStack(Material.DIAMOND, 4); contents[1] = new ItemStack(Material.DIAMOND, 4);
        var snapshot = new BackupSnapshot(null, contents, new ItemStack[4], null, 0, 0);
        assertEquals(1, plugin.getInventoryManager().applyMissingItems(player, snapshot));
        assertEquals(0, plugin.getInventoryManager().applyMissingItems(player, snapshot));
        assertEquals(8, java.util.Arrays.stream(player.getInventory().getContents()).filter(java.util.Objects::nonNull).mapToInt(ItemStack::getAmount).sum());
    }
    @Test void overflowRejectionDoesNotChangePlayer() {
        var player = server.addPlayer();
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
        ItemStack[] contents = new ItemStack[36]; contents[0] = new ItemStack(Material.DIAMOND);
        var snapshot = new BackupSnapshot(null, contents, new ItemStack[4], null, 42, .5f);
        var options = RestoreOptions.builder().clearBefore(false).dropOverflow(false).build();
        assertEquals(RestoreResult.INSUFFICIENT_SPACE, plugin.getApiService().applyNow(player, snapshot, options));
        assertEquals(Material.STONE, player.getInventory().getItem(0).getType()); assertEquals(0, player.getLevel());
    }
    @Test void pendingCompareAndRemovePreservesNewerEntry() {
        UUID target = UUID.randomUUID(), owner = UUID.randomUUID(); var store = plugin.getPendingRestores();
        store.put(target, owner, "old.yml", RestoreOptions.all(), null); var old = store.get(target).orElseThrow();
        store.put(target, owner, "new.yml", RestoreOptions.all(), null);
        assertFalse(store.removeIfSame(target, old)); assertEquals("new.yml", store.get(target).orElseThrow().backupId());
        store.saveIfDirty(); var reloaded = new PendingRestoreStore(plugin); reloaded.load();
        assertEquals("new.yml", reloaded.get(target).orElseThrow().backupId());
    }
    @Test void malformedPayloadIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> InventorySerializer.decodeInventory("broken", true));
        assertThrows(IllegalArgumentException.class, () -> InventorySerializer.decodeItem("broken", false));
    }
    private <T> T await(java.util.concurrent.CompletableFuture<T> future) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!future.isDone() && System.nanoTime() < deadline) {
            server.getScheduler().performOneTick(); Thread.sleep(2);
        }
        return future.get(1, java.util.concurrent.TimeUnit.SECONDS);
    }
    @Test void emptyInventoryRoundtripAndMissingArmorRejected() throws Exception {
        var player = server.addPlayer();
        var handle = await(plugin.getApiService().createBackup(player, BackupRequest.of("manual"))).orElseThrow();
        var snapshot = await(plugin.getApiService().loadBackup(handle)).orElseThrow();
        assertEquals(36, snapshot.contents().length); assertEquals(4, snapshot.armor().length);
        java.nio.file.Path file = plugin.getDataFolder().toPath().resolve("inventories").resolve(handle.ownerId().toString()).resolve(handle.id());
        var config = new org.bukkit.configuration.file.YamlConfiguration(); config.load(file.toFile());
        config.set("armor", "broken"); config.save(file.toFile());
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        assertEquals(RestoreResult.INVALID_BACKUP, await(plugin.getApiService().restore(player.getUniqueId(), handle, RestoreOptions.all())));
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
    }
    @Test void newerDataVersionRejected() throws Exception {
        var player = server.addPlayer();
        var handle = await(plugin.getApiService().createBackup(player, BackupRequest.of("manual"))).orElseThrow();
        var file = plugin.getDataFolder().toPath().resolve("inventories").resolve(handle.ownerId().toString()).resolve(handle.id());
        var config = new org.bukkit.configuration.file.YamlConfiguration(); config.load(file.toFile());
        config.set("data-version", Integer.MAX_VALUE); config.save(file.toFile());
        assertEquals(RestoreResult.INCOMPATIBLE_VERSION, await(plugin.getApiService().restore(player.getUniqueId(), handle, RestoreOptions.all())));
    }
    @Test void rejectedReloadRetainsPreviousSettings() throws Exception {
        int interval = plugin.getConfig().getInt("cleanup-interval-hours");
        var file = plugin.getDataFolder().toPath().resolve("config.yml");
        java.nio.file.Files.writeString(file, "cleanup-interval-hours: -1\n");
        assertThrows(IllegalArgumentException.class, plugin::reloadAll);
        assertEquals(interval, plugin.getConfig().getInt("cleanup-interval-hours"));
    }
    @Test void shutdownCompletesOutstandingFutures() throws Exception {
        var future = plugin.getApiService().background(() -> { try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return true; });
        plugin.getApiService().shutdown();
        assertTrue(future.isCompletedExceptionally());
    }
    @Test void corruptPendingFilePreserved() throws Exception {
        var path = plugin.getDataFolder().toPath().resolve("pending-restores.yml");
        java.nio.file.Files.writeString(path, "broken: [\n");
        assertThrows(IllegalStateException.class, () -> new PendingRestoreStore(plugin).load());
        assertEquals("broken: [\n", java.nio.file.Files.readString(path));
    }

    @Test void previewBlocksAllClickKindsAndDrag() {
        var player = server.addPlayer();
        ItemStack[] items = new ItemStack[36]; items[0] = new ItemStack(Material.DIAMOND);
        var gui = com.zfzfg.inventorybackup.gui.PreviewHolder.build(new BackupSnapshot(null, items, new ItemStack[4], null, 0, 0), "Preview");
        player.openInventory(gui);
        var view = player.getOpenInventory();
        for (var click : java.util.List.of(org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.ClickType.SHIFT_LEFT,
                org.bukkit.event.inventory.ClickType.NUMBER_KEY, org.bukkit.event.inventory.ClickType.DOUBLE_CLICK,
                org.bukkit.event.inventory.ClickType.CREATIVE, org.bukkit.event.inventory.ClickType.SWAP_OFFHAND)) {
            var event = new org.bukkit.event.inventory.InventoryClickEvent(view, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                    0, click, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL, 1);
            server.getPluginManager().callEvent(event); assertTrue(event.isCancelled(), click.toString());
        }
        var drag = new org.bukkit.event.inventory.InventoryDragEvent(view, new ItemStack(Material.DIAMOND), new ItemStack(Material.DIAMOND), false, java.util.Map.of(0, new ItemStack(Material.DIAMOND)));
        server.getPluginManager().callEvent(drag); assertTrue(drag.isCancelled());
        assertEquals(Material.DIAMOND, gui.getItem(0).getType());
    }
    @Test void cancelledRestorePreservesPendingAndInventory() {
        var player = server.addPlayer();
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler public void cancel(com.zfzfg.inventorybackup.api.events.InventoryRestoreEvent event) { event.setCancelled(true); }
        }, plugin);
        var snapshot = new BackupSnapshot(null, new ItemStack[36], new ItemStack[4], null, 0, 0);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        assertEquals(RestoreResult.CANCELLED, plugin.getApiService().applyNow(player, snapshot, RestoreOptions.all()));
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
    }
    @Test void failedQueueWriteDoesNotReportSuccess() throws Exception {
        var player = server.addPlayer();
        var handle = await(plugin.getApiService().createBackup(player, BackupRequest.of("manual"))).orElseThrow();
        var path = plugin.getDataFolder().toPath().resolve("pending-restores.yml");
        java.nio.file.Files.createDirectory(path); java.nio.file.Files.writeString(path.resolve("keep"), "preserved");
        var future = plugin.getApiService().queueRestoreOnJoin(UUID.randomUUID(), handle, RestoreOptions.all());
        assertThrows(Exception.class, () -> await(future));
        assertTrue(future.isCompletedExceptionally()); assertEquals("preserved", java.nio.file.Files.readString(path.resolve("keep")));
        java.nio.file.Files.delete(path.resolve("keep")); java.nio.file.Files.delete(path);
    }

    @Test void concurrentPendingFlushesRetainAllEntries() throws Exception {
        var store = plugin.getPendingRestores(); UUID owner = UUID.randomUUID();
        var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            java.util.List<UUID> targets = new java.util.ArrayList<>();
            for (int i = 0; i < 30; i++) {
                UUID target = UUID.randomUUID(); targets.add(target);
                futures.add(workers.submit(() -> { store.put(target, owner, "backup.yml", RestoreOptions.all(), null); store.saveIfDirty(); }));
            }
            for (var future : futures) future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            store.saveIfDirty(); var reloaded = new PendingRestoreStore(plugin); reloaded.load();
            for (UUID target : targets) assertTrue(reloaded.get(target).isPresent());
        } finally { workers.shutdownNow(); }
    }
    @Test void backupNamesRemainUniqueAfterThousandCollisions() throws Exception {
        var folder = plugin.getDataFolder().toPath().resolve("collision-test"); java.nio.file.Files.createDirectories(folder);
        String prefix = "2026-10-01_00-00-00";
        java.nio.file.Files.writeString(folder.resolve(prefix + "_manual.yml"), "original");
        for (int i = 2; i <= 1000; i++) java.nio.file.Files.writeString(folder.resolve(prefix + "_manual-" + i + ".yml"), "original");
        var method = InventoryManager.class.getDeclaredMethod("uniqueFile", java.io.File.class, String.class, String.class); method.setAccessible(true);
        var file = (java.io.File) method.invoke(null, folder.toFile(), prefix, "manual");
        assertFalse(file.exists()); assertEquals(prefix + "_manual-1001.yml", file.getName());
    }
    @Test void restoreAfterDisconnectIsDurablyQueued() throws Exception {
        var player = server.addPlayer();
        var handle = await(plugin.getApiService().createBackup(player, BackupRequest.of("manual"))).orElseThrow();
        player.disconnect();
        assertEquals(RestoreResult.QUEUED_FOR_JOIN, await(plugin.getApiService().restore(player.getUniqueId(), handle, RestoreOptions.all())));
        var reloaded = new PendingRestoreStore(plugin); reloaded.load(); assertTrue(reloaded.get(player.getUniqueId()).isPresent());
    }
    @Test void retainedBackupIsExcludedFromCleanup() throws Exception {
        var player = server.addPlayer();
        var handle = await(plugin.getApiService().createBackup(player, BackupRequest.of("manual"))).orElseThrow();
        var folder = plugin.getDataFolder().toPath().resolve("inventories").resolve(handle.ownerId().toString());
        String oldName = "2020-01-01_00-00-00_manual.yml";
        java.nio.file.Files.move(folder.resolve(handle.id()), folder.resolve(oldName));
        plugin.getPendingRestores().put(UUID.randomUUID(), handle.ownerId(), oldName, RestoreOptions.all(), null);
        assertTrue(await(plugin.getApiService().background(() -> plugin.getInventoryManager().cleanOldFiles())).isEmpty());
        assertTrue(java.nio.file.Files.exists(folder.resolve(oldName)));
    }

    @Test void deathEventsCaptureWithoutDamageCache() throws Exception {
        for (var type : java.util.List.of(org.bukkit.damage.DamageType.GENERIC, org.bukkit.damage.DamageType.FALL,
                org.bukkit.damage.DamageType.OUT_OF_WORLD, org.bukkit.damage.DamageType.GENERIC_KILL)) {
            for (boolean keepInventory : java.util.List.of(false, true)) {
                var player = server.addPlayer(); player.setLevel(12); player.setExp(.5f);
                var source = org.bukkit.damage.DamageSource.builder(type).build();
                var event = new org.bukkit.event.entity.PlayerDeathEvent(player, source, new java.util.ArrayList<>(), 0, (String) null);
                event.setKeepInventory(keepInventory); server.getPluginManager().callEvent(event);
                java.util.List<BackupHandle> handles = java.util.List.of();
                for (int i = 0; i < 10 && handles.isEmpty(); i++) handles = await(plugin.getApiService().listBackups(player.getUniqueId(), "death"));
                assertEquals(1, handles.size(), type.toString() + keepInventory);
                var saved = await(plugin.getApiService().loadBackup(handles.get(0))).orElseThrow();
                assertEquals(12, saved.level()); assertEquals(.5f, saved.exp());
            }
        }
    }

}
