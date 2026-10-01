package com.zfzfg.inventorybackup.managers;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cleanup task ages backups out by reading the timestamp off the file name
 * instead of parsing every YAML document. A name it cannot read is a backup that
 * never expires, so the parser has to cope with everything the writer produces.
 */
class BackupFileNameTest {

    @Test
    void readsTheTimestampPrefix() {
        long parsed = InventoryManager.parseTimestamp("2026-08-09_12-30-45_death.yml");

        long expected = LocalDateTime.of(2026, 8, 9, 12, 30, 45)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals(expected, parsed);
    }

    @Test
    void copesWithCustomTypesFromOtherPlugins() {
        // The old parser cut at the last underscore, which a multi-word type
        // would have broken.
        assertTrue(InventoryManager.parseTimestamp("2026-08-09_12-30-45_my-quest.yml") > 0);
        assertEquals(
                InventoryManager.parseTimestamp("2026-08-09_12-30-45_death.yml"),
                InventoryManager.parseTimestamp("2026-08-09_12-30-45_my-quest.yml"));
    }

    @Test
    void copesWithTheCollisionSuffix() {
        // Two backups in the same second get "-2" appended.
        assertEquals(
                InventoryManager.parseTimestamp("2026-08-09_12-30-45_death.yml"),
                InventoryManager.parseTimestamp("2026-08-09_12-30-45_death-2.yml"));
    }

    @Test
    void returnsZeroForNamesWithoutATimestamp() {
        assertEquals(0, InventoryManager.parseTimestamp("notes.yml"));
        assertEquals(0, InventoryManager.parseTimestamp("2026-13-45_99-99-99_death.yml"));
        assertEquals(0, InventoryManager.parseTimestamp(""));
        assertEquals(0, InventoryManager.parseTimestamp(null));
    }

    @Test
    void recognisesUuidFolderNames() {
        assertEquals("11111111-2222-3333-4444-555555555555",
                String.valueOf(InventoryManager.parseUuid("11111111-2222-3333-4444-555555555555")));
        // Player names must not be mistaken for UUID folders, or the migration
        // would consider them already done.
        assertEquals(null, InventoryManager.parseUuid("Steve"));
        assertEquals(null, InventoryManager.parseUuid("_unmigrated"));
    }
}
