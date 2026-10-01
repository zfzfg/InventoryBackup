package com.zfzfg.inventorybackup.testing;

import com.zfzfg.inventorybackup.InventoryBackup;
import com.zfzfg.inventorybackup.api.*;
import com.zfzfg.inventorybackup.api.events.BackupCreatedEvent;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** Actual connected clients exercise vanilla deaths and inventory packets on disposable servers. */
public final class ClientTests extends JavaPlugin implements Listener {
    private InventoryBackup plugin;
    private BackupSnapshot dying;
    private BackupHandle lastDeath;
    private String cause;
    private boolean keep;
    private boolean queued;
    private int deaths, guis;
    private final UUID victimId = UUID.nameUUIDFromBytes("OfflinePlayer:IBVictim".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    @Override public void onEnable() {
        plugin = (InventoryBackup) Bukkit.getPluginManager().getPlugin("InventoryBackup");
        Bukkit.getPluginManager().registerEvents(this, this);
        var world = Bukkit.getWorlds().getFirst();
        world.setDifficulty(Difficulty.PEACEFUL); world.setSpawnLocation(0, 81, 0);
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) world.getBlockAt(x, 80, z).setType(Material.GLASS);
    }
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private void fail(Throwable error) {
        getLogger().log(java.util.logging.Level.SEVERE, "INVENTORYBACKUP_CLIENT_TEST_FAIL", error);
        Bukkit.shutdown();
    }
    private ItemStack[] items(Material type, int amount) {
        var values = new ItemStack[36]; values[0] = new ItemStack(type, amount); return values;
    }
    private void prepare(Player player) {
        player.closeInventory(); player.setGameMode(GameMode.SURVIVAL); player.setHealth(20); player.setFoodLevel(20);
        player.getInventory().clear(); player.getInventory().setStorageContents(items(Material.DIAMOND, 5));
        player.getInventory().setBoots(new ItemStack(Material.DIAMOND_BOOTS));
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD)); player.setLevel(35); player.setExp(.25f);
        player.teleport(new Location(player.getWorld(), 0.5, 81, 0.5));
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        var player = event.getPlayer(); player.setOp(true);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (queued && player.getUniqueId().equals(victimId)) {
                try {
                    check(player.getInventory().getItem(0).getType() == Material.GOLD_INGOT
                            && player.getInventory().getItem(0).getAmount() == 9, "Wrong queued restore applied on real join");
                    check(plugin.getPendingRestores().get(victimId).isEmpty(), "Pending entry was not consumed");
                    player.sendMessage("IBCLIENT_JOIN_PASS");
                } catch (Throwable error) { fail(error); }
            } else {
                prepare(player); player.sendMessage("IBCLIENT_READY");
            }
        }, 100);
    }
    @EventHandler public void respawn(PlayerRespawnEvent event) {
        event.setRespawnLocation(new Location(event.getPlayer().getWorld(), .5, 81, .5));
    }
    @EventHandler(priority = EventPriority.LOWEST) public void death(PlayerDeathEvent event) {
        if (!event.getEntity().getUniqueId().equals(victimId) || cause == null) return;
        dying = BackupSnapshot.of(event.getEntity());
        String actual = event.getEntity().getLastDamageCause().getCause().name();
        try {
            check(switch (cause) {
                case "fall" -> actual.equals("FALL");
                case "void" -> actual.equals("VOID");
                case "combat" -> actual.equals("ENTITY_ATTACK");
                default -> actual.equals("KILL") || actual.equals("SUICIDE");
            }, "Wrong vanilla death cause: " + actual);
        } catch (Throwable error) { fail(error); }
    }
    @EventHandler public void created(BackupCreatedEvent event) {
        if (!event.getHandle().ownerId().equals(victimId) || !event.getHandle().type().equals("death") || dying == null) return;
        var expected = dying; dying = null; lastDeath = event.getHandle();
        plugin.getApiService().loadBackup(lastDeath).thenCompose(value -> plugin.getApiService().mainCall(() -> {
            var actual = value.orElseThrow();
            check(Arrays.equals(expected.contents(), actual.contents()) && Arrays.equals(expected.armor(), actual.armor())
                    && Objects.equals(expected.offhand(), actual.offhand()) && expected.level() == actual.level()
                    && expected.exp() == actual.exp(), "Real death inventory changed");
            deaths++; Bukkit.getPlayer(victimId).sendMessage("IBCLIENT_DEATH_PASS " + cause + " " + keep); return true;
        })).exceptionally(error -> { fail(error); return false; });
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return false;
        try {
            switch (args[0]) {
                case "death" -> {
                    cause = args[1]; keep = Boolean.parseBoolean(args[2]); prepare(player);
                    player.getWorld().setGameRule(GameRule.KEEP_INVENTORY, keep);
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        switch (cause) {
                            case "fall" -> player.teleport(new Location(player.getWorld(), .5, 150, .5));
                            case "void" -> player.teleport(new Location(player.getWorld(), .5, -100, .5));
                            case "combat" -> {
                                var attacker = Bukkit.getPlayer("IBAttacker");
                                attacker.teleport(new Location(player.getWorld(), 1.5, 81, .5));
                                attacker.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));
                                player.setHealth(1); attacker.sendMessage("IBCLIENT_ATTACK");
                            }
                            default -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "kill " + player.getName());
                        }
                    }, 40);
                }
                case "gui" -> {
                    player.getInventory().clear(); player.setItemOnCursor(null);
                    player.setGameMode(args[1].equals("creative") ? GameMode.CREATIVE : GameMode.SURVIVAL);
                    check(plugin.getApiService().openPreview(player, lastDeath), "Preview was rejected");
                }
                case "gui-check" -> {
                    check(Arrays.stream(player.getInventory().getContents()).allMatch(item -> item == null || item.getType().isAir()), "Preview items escaped via real packets");
                    check(player.getItemOnCursor().getType().isAir(), "Preview cursor escaped");
                    guis++; player.closeInventory(); player.sendMessage("IBCLIENT_GUI_PASS");
                }
                case "queue" -> {
                    check(Bukkit.getPlayer(victimId) == null, "Victim must be disconnected");
                    var api = plugin.getApiService();
                    var first = new BackupSnapshot(null, items(Material.DIAMOND, 5), new ItemStack[4], null, 0, 0);
                    var second = new BackupSnapshot(null, items(Material.GOLD_INGOT, 9), new ItemStack[4], null, 0, 0);
                    api.createBackup(victimId, "IBVictim", first, BackupRequest.of("client"))
                        .thenCompose(handle -> api.queueRestoreOnJoin(victimId, handle.orElseThrow(), RestoreOptions.all()))
                        .thenCompose(ok -> { check(ok, "Initial queue failed"); return api.cancelPendingRestore(victimId); })
                        .thenCompose(ok -> { check(ok, "Queue cancellation failed"); return api.createBackup(victimId, "IBVictim", first, BackupRequest.of("client")); })
                        .thenCompose(handle -> api.queueRestoreOnJoin(victimId, handle.orElseThrow(), RestoreOptions.all()))
                        .thenCompose(ok -> { check(ok, "Queue failed"); return api.createBackup(victimId, "IBVictim", second, BackupRequest.of("client")); })
                        .thenCompose(handle -> api.queueRestoreOnJoin(victimId, handle.orElseThrow(), RestoreOptions.all()))
                        .thenCompose(ok -> api.mainCall(() -> { check(ok, "Queue replacement failed"); queued = true; player.sendMessage("IBCLIENT_QUEUE_READY"); return true; }))
                        .exceptionally(error -> { fail(error); return false; });
                }
                case "finish" -> {
                    check(deaths == 8 && guis == 2 && queued, "Incomplete client checks");
                    getLogger().info("INVENTORYBACKUP_CLIENT_TEST_PASS"); Bukkit.shutdown();
                }
                default -> throw new IllegalArgumentException("Unknown client test step");
            }
        } catch (Throwable error) { fail(error); }
        return true;
    }
}
