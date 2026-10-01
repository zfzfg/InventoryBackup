package com.zfzfg.inventorybackup;

import com.zfzfg.inventorybackup.platform.Platform;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;

class PlatformInitializationTest {
    public static class UnsupportedPlugin extends InventoryBackup {
        @Override protected Platform detectPlatform() { throw new IllegalStateException("Test unsupported adapter"); }
    }
    @Test void unsupportedAdapterCannotMutateArchivesOrCreateConfig() {
        MockBukkit.mock();
        try {
            InventoryBackup plugin = MockBukkit.load(UnsupportedPlugin.class);
            assertFalse(plugin.isEnabled());
            assertNull(plugin.getInventoryManager());
            assertFalse(new File(plugin.getDataFolder(), "config.yml").exists());
            assertFalse(new File(plugin.getDataFolder(), ".nbt-format-backup-complete").exists());
            assertFalse(new File(plugin.getDataFolder(), "inventories").exists());
        } finally { MockBukkit.unmock(); }
    }
}
