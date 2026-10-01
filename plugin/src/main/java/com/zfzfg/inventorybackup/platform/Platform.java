package com.zfzfg.inventorybackup.platform;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.util.Locale;
import java.util.Set;

/** Capability detection, with no Paper types in shared interfaces. */
public final class Platform {
    public static final Set<String> TARGET_VERSIONS = Set.of(
            "1.21.8", "1.21.9", "1.21.10", "1.21.11", "26.1.2", "26.2", "26.3");
    private final ItemCodec codec;
    private final boolean paper;
    private final boolean purpur;
    private final String version;

    Platform(ItemCodec codec, boolean paper, String version) {
        this.codec = codec;
        this.paper = paper;
        this.version = version;
        boolean purpurClass;
        try { Class.forName("org.purpurmc.purpur.PurpurConfig"); purpurClass = true; }
        catch (ClassNotFoundException ignored) { purpurClass = false; }
        purpur = paper && (purpurClass || Bukkit.getName().toLowerCase(Locale.ROOT).contains("purpur")
                || Bukkit.getVersion().toLowerCase(Locale.ROOT).contains("purpur"));
    }

    public static Platform detect() {
        String version = minecraftVersion(Bukkit.getBukkitVersion());
        try {
            boolean nativeBytes = hasNativeBytes();
            if (!TARGET_VERSIONS.contains(version))
                throw new IllegalStateException("No verified inventory storage target for Minecraft " + version);
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                throw new IllegalStateException("Folia scheduling is not supported");
            } catch (ClassNotFoundException ignored) { }
            ItemCodec codec = nativeBytes ? new PaperItemCodec() : new SpigotItemCodec(version);
            Platform platform = new Platform(codec, nativeBytes, version);
            // Exercise the selected implementation before any config, ZIP or archive mutation.
            ItemStack probe = new ItemStack(Material.DIAMOND, 2);
            if (!probe.equals(codec.decode(codec.encode(probe))))
                throw new IllegalStateException("NBT adapter round-trip self-test failed");
            return platform;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot initialize lossless NBT adapter for " + version, error);
        }
    }
    private static boolean hasNativeBytes() {
        try {
            ItemStack.class.getMethod("serializeAsBytes");
            ItemStack.class.getMethod("deserializeBytes", byte[].class);
            return true;
        } catch (NoSuchMethodException error) { return false; }
    }
    public static int currentDataVersion() {
        return MinecraftDataVersion.current();
    }
    public static String minecraftVersion(String bukkitVersion) {
        var match = java.util.regex.Pattern.compile("^([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)(?:-|\\.build\\.|$)").matcher(bukkitVersion);
        return match.find() ? match.group(1) : bukkitVersion;
    }
    public ItemCodec itemCodec() { return codec; }
    public String minecraftVersion() { return version; }
    public String name() { return purpur ? "Purpur" : paper ? "Paper" : "Spigot"; }
    public boolean supportsLoader(String loader) {
        return "spigot".equals(loader) || paper && "paper".equals(loader) || purpur && "purpur".equals(loader);
    }
}
