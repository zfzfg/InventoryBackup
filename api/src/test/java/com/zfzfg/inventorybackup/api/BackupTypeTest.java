package com.zfzfg.inventorybackup.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The normalized type ends up in a file name, so anything that survives
 * {@link BackupType#normalize} has to be safe there on every platform.
 */
class BackupTypeTest {

    @Test
    void lowercasesAndReplacesSeparators() {
        assertEquals("my-quest", BackupType.normalize("My Quest!"));
        assertEquals("pvp-arena", BackupType.normalize("PvP_Arena"));
    }

    @Test
    void collapsesRunsOfSeparators() {
        // "my---quest" would still be a legal file name, just an ugly one.
        assertEquals("my-quest", BackupType.normalize("my   ///   quest"));
    }

    @Test
    void dropsLeadingAndTrailingSeparators() {
        // A trailing hyphen would blur the "<timestamp>_<type>" boundary.
        assertEquals("quest", BackupType.normalize("  quest  "));
        assertEquals("quest", BackupType.normalize("...quest..."));
    }

    @Test
    void fallsBackWhenNothingUsableIsLeft() {
        assertEquals("custom", BackupType.normalize(null));
        assertEquals("custom", BackupType.normalize(""));
        assertEquals("custom", BackupType.normalize("!!!"));
    }

    @Test
    void truncatesOverlongTypes() {
        String result = BackupType.normalize("a".repeat(200));
        assertEquals(BackupType.MAX_LENGTH, result.length());
    }

    @Test
    void producesOnlyFileSafeCharacters() {
        String result = BackupType.normalize("Quest #42: der Wald/Berg\\Pfad");
        assertTrue(result.matches("[a-z0-9-]{1,32}"), "unsafe for a file name: " + result);
    }

    @Test
    void leavesTheShippedTypesAlone() {
        assertEquals(BackupType.DEATH, BackupType.normalize(BackupType.DEATH));
        assertEquals(BackupType.MANUAL, BackupType.normalize(BackupType.MANUAL));
    }
}
