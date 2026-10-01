package com.zfzfg.inventorybackup;

import com.zfzfg.inventorybackup.platform.Platform;
import com.zfzfg.inventorybackup.platform.TestPlatforms;

public class TestInventoryBackup extends InventoryBackup {
    @Override protected Platform detectPlatform() { return TestPlatforms.mock(); }
}
