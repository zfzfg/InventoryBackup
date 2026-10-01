# Changelog

All notable changes to the InventoryBackup plugin are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-08-11

### New Features

- **Public Developer API** (`com.zfzfg:InventoryBackup-API`):
  - Standalone API module allowing other plugins to create, list, load, restore, and delete backups, open the preview GUI, and resolve names to UUIDs.
  - Retrieved via `InventoryBackupProvider.get()` or `getOptional()`, backed by Bukkit's `ServicesManager` (`softdepend: [InventoryBackup]` is sufficient).
  - All `CompletableFuture`s complete directly on the Bukkit main thread, allowing callers to interact with Bukkit methods safely without manual scheduler hops.
  - Detailed documentation and integration examples in [API.md](API.md).
- **Custom Bukkit Events**:
  - Five custom events shipped inside the plugin JAR, usable with or without compiling against the API module:
    - `BackupCreateEvent` (cancellable, allows retyping backups and attaching custom metadata).
    - `BackupCreatedEvent` (fired after a backup is successfully saved to disk).
    - `InventoryRestoreEvent` (cancellable, allows inspecting or modifying snapshot items before applying).
    - `InventoryRestoredEvent` (fired after a restore is successfully applied).
    - `BackupDeletedEvent` (fired with reason `API`, `COMMAND`, or `EXPIRED`).
  - The plugin's internal death handler and admin commands route through the same service, ensuring uniform event dispatching.
- **Offline Player Restores**:
  - Aiming a restore or missing items command at an offline player automatically queues it in `pending-restores.yml`.
  - Automatically applied on the player's next join with a 1-tick delay to prevent overwriting by other login listeners; persists across server restarts.
  - Added `/inv <player> pending` to inspect queued restores and `/inv <player> cancelpending` to discard them (permission `inventorybackup.pending`).
  - Added `pending-restore-expiry-days` configuration setting (default: 30 days) to automatically purge expired pending entries.
- **Native Multi-Language Support (i18n)**:
  - Complete English (`en`) and German (`de`) translations for all player messages, GUI titles, list headers, and console logs.
  - Message texts decoupled into dedicated bundles: `messages_en.yml` (master fallback) and `messages_de.yml`.
  - Added `/inv lang [en|de]` to view or change language on the fly (rewrites `config.yml` and reloads immediately without a server restart; permission `inventorybackup.lang`).
  - Added `/inv reload` to reload configuration and message bundles on demand (permission `inventorybackup.reload`).
  - Layered fallback system: missing keys in custom translations seamlessly fall back to English defaults without breaking plugin execution.
- **UUID-Based Storage & Player Index**:
  - Backups are stored in `inventories/<owner-uuid>/` rather than player names, preventing orphaned backups when players change their username.
  - Implemented `names.yml` index cache populated on player join and backup operations, eliminating blocking Mojang API requests during commands and tab completion.
  - Automatic migration on first startup (`StorageMigrator`) moves legacy player-name folders to UUIDs; unrecognized folders are safely parked in `inventories/_unmigrated/` with zero data loss.
- **Free-Form Backup Types & Key-Value Metadata**:
  - Backup types are no longer restricted to `death` and `manual`. Custom types (e.g. `quest`, `dungeon`, `pvp`) are normalized to `[a-z0-9-]` for filename safety.
  - Arbitrary string metadata stored alongside backups in the YAML header and accessible via `BackupHandle.metadata(key)`.
- **Dupe-Proof Preview GUI**:
  - Implemented `PreviewHolder` and `PreviewGuiListener` to block all inventory clicks, drags, and hotbar swaps in `/inv <player> show`, preventing item duplication exploits regardless of window titles or translations.

### Bugfixes & Improvements

- **Fixed `restoreInventory` reporting false positives**: Previously returned `true` as soon as the task was scheduled; storage operations are now fully blocking internally with completion handled asynchronously.
- **Fixed corrupted backup handling**: A failed Base64 decode previously produced an empty item array that wiped player inventories upon restore. Unreadable backups are now safely logged, skipped, and left untouched.
- **Fixed armor & offhand overwrite during partial restores**: Contents array slot bounds are strictly enforced so that restoring main inventory items does not inadvertently alter armor or offhand slots.
- **Fixed same-second backup filename collisions**: When multiple backups are created for a player within the same second, an incremental numeric suffix (e.g. `-2`) is appended to prevent overwrites.
- **Fixed custom backup type timestamp parsing**: The cleanup parser now matches fixed-width timestamp prefixes rather than cutting at the last underscore.
- **Fixed unbounded lock map memory leak**: `InventoryManager` releases player write locks upon player disconnect (`PlayerQuitEvent`).
- **Fixed mixed-language outputs**: Eliminated remaining hardcoded German and English strings across console logging, update checking, list headers, GUI titles, and missing-key indicators (`&c[missing: messages.<key>]`).
- **Enhanced tab completion**: Now completes subcommands (`backup`, `version`, `lang`, `reload`), actions (`restore`, `show`, `givemissing`, `list`, `delete`, `pending`, `cancelpending`), online players, known offline players from the local index, and backup filenames.

### Breaking Changes

- **Storage Layout Migration**: Backup directory changed from `inventories/<PlayerName>/` to `inventories/<owner-uuid>/`. Existing directories are migrated automatically on first startup.
- **`InventoryManager` Refactoring**: Deprecated name-keyed methods in favor of UUID-keyed methods returning `BackupHandle` / `BackupSnapshot`. External integrations must migrate to `InventoryBackupAPI`.

### Build & Testing

- Multi-module Maven architecture: `InventoryBackup-parent` containing `api` (packaged as standalone JAR with sources and javadoc) and `plugin` (shading API classes without relocation).
- Added comprehensive JUnit 5 test suite with 18 automated tests:
  - `BundleParityTest`: Verifies full translation parity, placeholder integrity, and missing key detection.
  - `StorageMigratorTest`: Tests folder migration, merging, and unmigrated folder parking against real temporary directories.
  - `BackupFileNameTest`: Tests timestamp formatting, UUID parsing, and suffix handling.
  - `BackupTypeTest`: Tests type normalization and validation.
  - `InventorySerializerTest`: Tests Base64 serialization and deserialization across Minecraft inventory structures.

---

## [0.0.7] - 2026-08-08

### New Features
- Added Modrinth Update Checker (`UpdateChecker.java`) - Asynchronous update checking with Modrinth v2 API integration (`https://modrinth.com/project/rpKY25cW`).
- Added `/inv version` command - On-demand version check and update lookup with direct download links and live feedback.
- Added `UpdateNotifyListener` - Notifies administrators with permission `inventorybackup.update.notify` on join using cached check results (zero HTTP requests during login).
- Added tab completion support for `version` subcommand in `/inv`.

### Bugfixes & Improvements
- **Fixed `NoClassDefFoundError: Base64Coder`**: Replaced deprecated `org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder` with standard `java.util.Base64` (`Base64.getEncoder()` and `Base64.getMimeDecoder()`) for full compatibility with modern Paper/Purpur/Spigot 1.20+ and SnakeYAML 2.x runtimes.
- **Fixed URL formatting corruption in `colorize()`**: Switched from `message.replace("&", "§")` to `ChatColor.translateAlternateColorCodes('&', message)` to ensure URLs with query parameters remain intact.
- **Fixed missing message fallback in `getMessage()`**: Missing keys in existing configurations return a fallback (`&c[fehlt: messages.<path>]`) instead of returning raw key strings.
- **Unified version definition**: Switched `plugin.yml` to `${project.version}` so that Maven (`pom.xml`) is the single source of truth for the plugin version.
- **Robust version comparison**: `UpdateChecker` handles numeric parts, pre-release suffixes, and numeric overflows safely.

### Configuration Updates
- Added `update-check` configuration section in `config.yml` (`enabled`, `check-on-startup`, `notify-admins-on-join`, `modrinth-project-id`, `contact`, `stable-only`, `startup-delay-ticks`).
- Added message templates for update checking (`update-available`, `update-download`, `update-current`, `update-up-to-date`, `update-checking`, `update-failed`, `update-disabled`).
- Updated `plugin.yml` with `inventorybackup.update.notify` permission and updated command usage description.

---

## [0.0.6] - 2024-05-24

### Security Fixes
- **Critical**: Fixed Path Traversal vulnerability in `getInventoryFile()` - Added filename validation (regex check) and path canonicalization to prevent directory traversal attacks.
- **Critical**: Fixed Race Condition in `giveMissingItems()` - Moved item counting into the synchronous main thread callback to ensure accurate counts and prevent timing issues.
- **Critical**: Fixed Thread-Safety issue - Replaced non-thread-safe `SimpleDateFormat` with thread-safe `DateTimeFormatter` to prevent concurrent access errors.

### Code Quality Improvements
- Removed code duplication in `InventoryCommand` - Created helper method `sendBackupNotification()` to consolidate notification logic for backup operations.
- Improved error handling in `InventorySerializer` - Replaced `printStackTrace()` with Bukkit Logger for proper error logging and better debugging.
- Added comprehensive JavaDoc documentation for all public methods in `InventoryManager`, `InventorySerializer`, and `InventoryCommand`.

### New Features
- Added `/inv <player> list` command - Lists all backup files for a specific player with timestamps.
- Added `/inv <player> delete <filename>` command - Deletes a specific backup file.
- Added `/inv <player> delete all` command - Deletes all backup files for a player.
- Added tab completion support for all new commands including "all" option for delete command.

### Performance Optimizations
- Optimized file cleanup in `cleanOldFiles()` - Parses timestamp from filename instead of loading YAML files, significantly improving performance when cleaning up old backups.
- Added fallback mechanism for timestamp parsing - If filename parsing fails, falls back to loading YAML for compatibility.

### Configuration Updates
- Updated `config.yml` with new messages for delete operations:
  - `backup-deleted` - Message when a single backup is deleted.
  - `all-backups-deleted` - Message when all backups are deleted.
- Updated `plugin.yml` command usage to include new list and delete commands.
- Updated `plugin.yml` author field with full author information and website.

### Documentation
- Completely rewrote README.md with comprehensive documentation.
- Created dedicated CHANGELOG.md file for version history.
- Updated version to 0.0.6 in pom.xml, plugin.yml, and README.md.
