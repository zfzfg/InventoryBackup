package com.zfzfg.inventorybackup.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Optional;

/**
 * Entry point to the InventoryBackup API.
 *
 * <p>The plugin registers its implementation with Bukkit's
 * {@code ServicesManager} in {@code onEnable}, so no cast against the plugin
 * class is needed and {@code softdepend: [InventoryBackup]} in your plugin.yml
 * is enough:
 *
 * <pre>{@code
 * InventoryBackupProvider.getOptional().ifPresent(api -> {
 *     // InventoryBackup is installed - wire up the integration
 * });
 * }</pre>
 *
 * <p>Look the API up when you need it rather than caching it in a field. A
 * {@code /reload} or a plugin manager re-enabling InventoryBackup replaces the
 * registration, and a cached reference would then point at a dead instance.
 */
public final class InventoryBackupProvider {

    private InventoryBackupProvider() {
    }

    /**
     * The registered API implementation.
     *
     * @return the API
     * @throws IllegalStateException if InventoryBackup is not installed or has
     *                               not finished enabling yet
     */
    public static InventoryBackupAPI get() {
        return getOptional().orElseThrow(() -> new IllegalStateException(
                "InventoryBackup is not available. Add 'softdepend: [InventoryBackup]' to your "
                        + "plugin.yml and check InventoryBackupProvider.isAvailable() before calling."));
    }

    /** The registered API implementation, or empty if InventoryBackup is absent. */
    public static Optional<InventoryBackupAPI> getOptional() {
        RegisteredServiceProvider<InventoryBackupAPI> registration =
                Bukkit.getServicesManager().getRegistration(InventoryBackupAPI.class);
        return registration == null ? Optional.empty() : Optional.ofNullable(registration.getProvider());
    }

    /** Whether InventoryBackup is installed and enabled. */
    public static boolean isAvailable() {
        return getOptional().isPresent();
    }
}
