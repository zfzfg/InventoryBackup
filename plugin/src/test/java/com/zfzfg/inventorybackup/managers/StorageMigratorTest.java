package com.zfzfg.inventorybackup.managers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the one-time move from name-keyed to UUID-keyed backup folders.
 *
 * <p>This runs against a real temp directory rather than a mock file system:
 * the whole point of the migration is that it moves files correctly, and the
 * bugs worth catching here (a clobbered folder, a deleted history) only show up
 * against real rename semantics.
 */
class StorageMigratorTest {

    private static final UUID STEVE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ALEX = UUID.fromString("66666666-7777-8888-9999-000000000000");

    @Test
    void movesNameFolderToUuidFolder(@TempDir File root) throws IOException {
        backup(root, "Steve", "2026-08-09_12-00-00_death.yml", STEVE);

        Map<UUID, String> remembered = new HashMap<>();
        StorageMigrator.Report report = StorageMigrator.migrateFolders(root, remembered::put);

        assertEquals(STEVE, report.moved().get("Steve"));
        assertFalse(new File(root, "Steve").exists(), "old name folder should be gone");
        assertTrue(new File(root, STEVE + "/2026-08-09_12-00-00_death.yml").isFile());
        assertEquals("Steve", remembered.get(STEVE), "name should be fed to the index");
    }

    @Test
    void leavesFoldersThatAreAlreadyUuids(@TempDir File root) throws IOException {
        backup(root, ALEX.toString(), "2026-08-09_12-00-00_manual.yml", ALEX);

        StorageMigrator.Report report = StorageMigrator.migrateFolders(root, (id, name) -> { });

        assertTrue(report.moved().isEmpty());
        assertTrue(report.parked().isEmpty());
        assertTrue(new File(root, ALEX + "/2026-08-09_12-00-00_manual.yml").isFile());
    }

    @Test
    void mergesIntoAnExistingUuidFolderWithoutLosingAnything(@TempDir File root) throws IOException {
        // Player renamed mid-life: some backups landed under the old name,
        // later ones under the UUID. Both sets have to survive.
        backup(root, "Steve", "2026-08-01_10-00-00_death.yml", STEVE);
        backup(root, STEVE.toString(), "2026-08-09_12-00-00_death.yml", STEVE);

        StorageMigrator.migrateFolders(root, (id, name) -> { });

        File target = new File(root, STEVE.toString());
        assertEquals(2, target.listFiles().length, "both backups should be in the UUID folder");
        assertFalse(new File(root, "Steve").exists());
    }

    @Test
    void keepsBothWhenTheSameFileNameExistsOnBothSides(@TempDir File root) throws IOException {
        String sameName = "2026-08-09_12-00-00_death.yml";
        backup(root, "Steve", sameName, STEVE);
        backup(root, STEVE.toString(), sameName, STEVE);

        StorageMigrator.migrateFolders(root, (id, name) -> { });

        File[] files = new File(root, STEVE.toString()).listFiles();
        assertEquals(2, files.length, "a name collision must not silently drop a backup");
    }

    @Test
    void parksFoldersWithoutAReadableUuid(@TempDir File root) throws IOException {
        File folder = new File(root, "Ghost");
        folder.mkdirs();
        // A backup file that never recorded an owner - nothing to resolve.
        Files.write(new File(folder, "2026-08-09_12-00-00_death.yml").toPath(),
                "player: Ghost\n".getBytes(StandardCharsets.UTF_8));

        StorageMigrator.Report report = StorageMigrator.migrateFolders(root, (id, name) -> { });

        assertTrue(report.parked().contains("Ghost"));
        assertTrue(new File(root, "_unmigrated/Ghost/2026-08-09_12-00-00_death.yml").isFile(),
                "nothing may be deleted - it has to be recoverable by hand");
    }

    @Test
    void skipsTheParkingFolderOnASecondRun(@TempDir File root) throws IOException {
        File parked = new File(root, "_unmigrated/Ghost");
        parked.mkdirs();
        Files.write(new File(parked, "2026-08-09_12-00-00_death.yml").toPath(),
                "player: Ghost\n".getBytes(StandardCharsets.UTF_8));

        StorageMigrator.Report report = StorageMigrator.migrateFolders(root, (id, name) -> { });

        assertTrue(report.moved().isEmpty());
        assertTrue(report.parked().isEmpty(), "_unmigrated must not be re-parked into itself");
        assertTrue(new File(root, "_unmigrated/Ghost").isDirectory());
    }

    @Test
    void handlesAnEmptyArchive(@TempDir File root) {
        StorageMigrator.Report report = StorageMigrator.migrateFolders(root, (id, name) -> { });

        assertTrue(report.moved().isEmpty());
        assertTrue(report.parked().isEmpty());
        assertTrue(report.failed().isEmpty());
    }

    /** Writes a minimal backup file, carrying only what the migration reads. */
    private static void backup(File root, String folderName, String fileName, UUID owner) throws IOException {
        File folder = new File(root, folderName);
        folder.mkdirs();
        Files.write(new File(folder, fileName).toPath(),
                ("uuid: " + owner + "\nplayer: someone\n").getBytes(StandardCharsets.UTF_8));
    }
}
