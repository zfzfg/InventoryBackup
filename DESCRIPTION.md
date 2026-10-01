# InventoryBackup Plugin Description

## Overview

**InventoryBackup** is a powerful, robust, and secure Minecraft Spigot/Paper plugin designed to automatically archive player inventories upon death and provide comprehensive, flexible restore functionality. Built with server administrators and developers in mind, it offers a complete, production-ready solution featuring a rich Developer API, offline player restores, UUID-based storage, multi-language support (English and German), and dupe-proof inventory previews.

- **Version:** 0.2.0
- **Modrinth Project:** https://modrinth.com/project/rpKY25cW
- **Developer API Artifact:** `com.zfzfg:InventoryBackup-API:0.2.0`
- **Supported Platforms:** Minecraft 1.21.8 through 26.3 on tested Spigot, Paper and Purpur builds (Java 21 for 1.21.x, Java 25 for 26.1+). Folia is not supported. See [COMPATIBILITY.md](COMPATIBILITY.md).

---

## Why Use InventoryBackup?

### 🛡️ Protect Your Players' Progress
Players invest countless hours gathering gear, rare enchantments, and building resources. Unexpected deaths, void falls, server glitches, or accidental despawns can wipe out hours of hard work. InventoryBackup ensures that no player permanently loses their inventory.

### ⚡ Offline Restores Made Effortless
Targeting an offline player with a restore or missing items command queues it automatically into `pending-restores.yml`. The items are safely restored the very next time the player joins the server — surviving server restarts and network disconnects without any manual follow-up required.

### 🌐 Native Multi-Language Support (i18n)
All player and console messages are fully localized in English (`en`) and German (`de`). Switch languages on the fly at runtime with `/inv lang de` or `/inv lang en` without restarting the server. Custom translations use dedicated message files with automatic fallback to English for any missing keys.

### 🧩 First-Class Developer API & Events
Integrate directly into quests, minigames, arenas, or custom death systems. The clean API module provides asynchronous futures that complete directly on the main thread, accompanied by five custom Bukkit events (including cancellable pre-events) to customize or veto any backup and restore operation.

### 🔒 Enterprise-Grade Security & Performance
Protected against path traversal attacks, race conditions, memory leaks, and GUI item-duplication exploits. Backup timestamps are parsed directly from filenames to optimize cleanup loops, and file I/O operations run asynchronously to guarantee zero server tick lag.

---

## Key Features

### 1. Automatic Death Backups (Two-Stage Caching)
The plugin captures player state through an intelligent two-stage mechanism:
1. **Damage Detection**: When a player takes damage that brings them to 4 hearts or less, their current inventory is cached synchronously on the main thread.
2. **Death Event**: When the player dies, the cached inventory snapshot is saved to disk asynchronously.

This guarantees accurate inventory capture even during instantaneous, one-shot deaths (such as high falls, void damage, or instant-kill effects).

### 2. Offline Player Restores & Join Queue
- Restores targeted at offline players are automatically queued in `pending-restores.yml`.
- When the player joins, the queued restore is applied with a 1-tick delay to prevent other login listeners from overriding the restored inventory.
- Inspect queued restores with `/inv <player> pending` and cancel them with `/inv <player> cancelpending`.
- Configurable expiry (`pending-restore-expiry-days`, default 30 days) automatically purges stale pending entries.

### 3. Flexible Restore & Inspection Modes
- **Full Restore**: Completely replaces the player's current inventory, armor, offhand, level, and experience with the archived backup (`/inv <player> restore <filename>`).
- **Missing Items Only**: Compares the backup against what the player currently holds and gives back only the items they are lacking without wiping their current inventory (`/inv <player> givemissing <filename>`).
- **Protected GUI Preview**: Inspect the exact contents of any backup in an interactive GUI window (`/inv <player> show <filename>`). Viewers cannot drag, click, or duplicate items out of the preview GUI.

### 4. UUID-Based Storage, NBT Archives & Legacy Reads
- Backups are stored in `plugins/InventoryBackup/inventories/<UUID>/` to ensure player history is never lost or orphaned when a player changes their Minecraft name.
- New files use `format-version: 3`, compressed Minecraft item NBT and the server data version. The same archive can move between tested Spigot, Paper and Purpur servers on that Minecraft version, or upgrade to a newer tested version.
- Backups written by 0.0.7 and 0.1.0 stay readable, including multiline Base64 and 41-slot inventories. Original files are not rewritten. A ZIP of the plugin data is created before the first new-format startup.
- File names follow the format `<yyyy-MM-dd_HH-mm-ss>_<type>.yml` (e.g. `2026-08-11_15-30-45_death.yml`).
- Fast, local name-to-UUID lookups via `names.yml` eliminate blocking web requests to Mojang APIs during commands and tab completion.
- Seamless automatic migration from legacy player-name folders on server startup.

### 5. Multi-Language & Translation Bundles
- Complete translations for all player messages, GUI titles, list entries, and console logs.
- Message bundles: `messages_en.yml` (English fallback master) and `messages_de.yml` (German).
- Switch languages on the fly: `/inv lang de` or `/inv lang en`.
- Reload configuration and bundles at any time: `/inv reload`.
- Color codes (`&`) and dynamic placeholders (`{player}`, `{file}`, `{count}`, `{level}`, `{type}`, etc.) supported throughout.

### 6. Developer API & Custom Events
- Standalone API artifact `com.zfzfg:InventoryBackup-API` for third-party plugin integration via `InventoryBackupProvider.get()`.
- Non-blocking `CompletableFuture`s completing on the Bukkit main thread.
- Custom Bukkit events included in the plugin JAR:
  - `BackupCreateEvent` (Cancellable; modify type or attach custom metadata)
  - `BackupCreatedEvent` (Fired after backup is saved)
  - `InventoryRestoreEvent` (Cancellable; inspect or filter restored items)
  - `InventoryRestoredEvent` (Fired after restore is applied)
  - `BackupDeletedEvent` (Fired with reason `API`, `COMMAND`, or `EXPIRED`)
- Free-form backup types (e.g., `quest`, `pvp`, `admin`, `checkpoint`) and arbitrary string key/value metadata.

### 7. Automated Maintenance & Cleanup
- Automatic deletion of backups older than a configurable number of days (`auto-delete-days: 30`).
- Background cleanup task runs at configurable intervals (`cleanup-interval-hours: 24`).
- Fast timestamp extraction directly from filenames without heavy YAML parsing.

### 8. Modrinth Update Checker
- Background update check on server startup via Modrinth v2 API (`rpKY25cW`).
- Cached update notifications for administrators upon joining (zero login network lag).
- Manual on-demand check via `/inv version` with live feedback and direct download links.

---

## Command Reference

The base command is `/inv`, with aliases `/inventory` and `/invbackup`.

| Command | Description | Permission |
|---------|-------------|------------|
| `/inv backup all` | Create a manual backup for all online players | `inventorybackup.backup` |
| `/inv backup <player>` | Create a manual backup for a specific online player | `inventorybackup.backup` |
| `/inv <player> restore <filename>` | Restore inventory (applies immediately or queues if offline) | `inventorybackup.restore` |
| `/inv <player> show <filename>` | Open read-only GUI preview of a saved backup | `inventorybackup.show` |
| `/inv <player> givemissing <filename>` | Give only missing items to an online player | `inventorybackup.givemissing` |
| `/inv <player> list` | List all backups and their types for a player | `inventorybackup.show` |
| `/inv <player> delete <filename>` | Delete a specific backup file | `inventorybackup.restore` |
| `/inv <player> delete all` | Delete all backups for a specific player | `inventorybackup.restore` |
| `/inv <player> pending` | View the restore queued for an offline player | `inventorybackup.pending` |
| `/inv <player> cancelpending` | Cancel a queued offline restore | `inventorybackup.pending` |
| `/inv version` | Check Modrinth for updates and display version info | `inventorybackup.use` |
| `/inv lang` | Display the currently active language | `inventorybackup.lang` |
| `/inv lang <en\|de>` | Switch language and reload message bundles instantly | `inventorybackup.lang` |
| `/inv reload` | Reload `config.yml` and active message bundle | `inventorybackup.reload` |

> ℹ️ **Tab Completion:** Full tab completion is supported for subcommands, actions, online and known offline players, language codes, and existing backup filenames.

---

## Permissions

All permissions default to server operators (`op`):

| Permission | Description | Default |
|------------|-------------|---------|
| `inventorybackup.use` | Access to base `/inv` command and `/inv version` | OP |
| `inventorybackup.restore` | Restore backups and delete individual or all backups | OP |
| `inventorybackup.show` | Open GUI preview (`show`) and view backup list (`list`) | OP |
| `inventorybackup.givemissing` | Hand over only missing items to a player | OP |
| `inventorybackup.backup` | Create manual backups for single players or all online players | OP |
| `inventorybackup.pending` | View and cancel queued restores for offline players | OP |
| `inventorybackup.notify` | Receive chat notifications when a backup is saved | OP |
| `inventorybackup.update.notify` | Receive update notification messages upon joining | OP |
| `inventorybackup.lang` | View and switch the message language | OP |
| `inventorybackup.reload` | Reload plugin configuration and message bundles | OP |

---

## Configuration

The default `config.yml` provides clean, granular control over all features:

```yaml
# Language for all plugin and console messages: "en" (English) or "de" (German).
# Message texts live in messages_<language>.yml next to this file.
language: "en"

# How many days to keep inventory files before auto-deletion (0 = disabled)
auto-delete-days: 30

# Check interval for file cleanup (in hours)
cleanup-interval-hours: 24

# Save inventory automatically on player death
save-on-death: true

# Performance settings: damage cache cleanup interval (in seconds)
cache-cleanup-interval: 30

# Retention period for unapplied offline restores in days (0 = forever)
pending-restore-expiry-days: 30

# Notification settings
notify-on-backup: true
notify-ops-only: true

# Update check (Modrinth)
update-check:
  enabled: true               # Master switch
  check-on-startup: true      # Fetch on server start
  notify-admins-on-join: true # Notice in chat when an admin joins
  modrinth-project-id: "rpKY25cW"
  contact: "zfzfg@sterra.online" # Included in User-Agent header
  stable-only: true           # Ignore pre-releases
  startup-delay-ticks: 20     # Startup check delay (20 ticks = 1 second)
```

### Language Bundles (`messages_en.yml`, `messages_de.yml`)
Message texts are decoupled from `config.yml`. Customizing messages is as simple as editing `messages_en.yml` or `messages_de.yml` and running `/inv reload`. Any missing translation keys automatically fall back to the English defaults without breaking plugin execution.

---

## File Structure

```
plugins/InventoryBackup/
├── config.yml                                      <- Main plugin settings
├── messages_en.yml                                 <- English message bundle
├── messages_de.yml                                 <- German message bundle
├── names.yml                                       <- Player Name <-> UUID index cache
├── pending-restores.yml                            <- Offline restores queue
└── inventories/
    └── <owner-uuid>/
        ├── 2026-08-11_14-00-00_death.yml           <- Automatic death backup
        ├── 2026-08-11_15-30-00_manual.yml          <- Manual /inv backup
        └── 2026-08-11_16-45-00_quest.yml           <- API backup with custom type
```

---

## Common Administrator Scenarios

### Scenario 1: Player Died in Lava / Void
1. Check the player's backups: `/inv Steve list`
2. Preview the backup items in the GUI: `/inv Steve show 2026-08-11_14-00-00_death.yml`
3. Restore the full inventory: `/inv Steve restore 2026-08-11_14-00-00_death.yml`

### Scenario 2: Player Lost Specific Items but Kept Some Gear
1. List the player's backups: `/inv Alex list`
2. Hand over only the missing items: `/inv Alex givemissing 2026-08-11_14-00-00_death.yml`
3. Alex receives all missing item stacks in their inventory without overwriting their currently equipped gear.

### Scenario 3: Restoring an Offline Player
1. A player reports item loss on Discord while offline.
2. An admin executes: `/inv Steve restore 2026-08-11_14-00-00_death.yml`
3. The plugin queues the restore: `Steve is offline - the restore will run on their next join.`
4. Verify the queue: `/inv Steve pending`
5. As soon as Steve logs into the server, their inventory is restored automatically.

### Scenario 4: Server Maintenance / Pre-Event Snapshot
1. Before running a risky server event or maintenance: `/inv backup all`
2. All online players have their exact inventories snapshotted.
3. If an issue occurs, any player can be restored to this exact pre-event state.

---

## Developer Integration

Add the API dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.zfzfg</groupId>
    <artifactId>InventoryBackup-API</artifactId>
    <version>0.2.0</version>
    <scope>provided</scope>
</dependency>
```

### Quick Code Example

```java
import com.zfzfg.inventorybackup.api.*;

// Obtain API instance
InventoryBackupAPI api = InventoryBackupProvider.get();

// Create a snapshot before a custom minigame / quest
api.createBackup(player, BackupRequest.builder()
        .type("dungeon")
        .sourcePlugin(this)
        .metadata("dungeon-id", "shadow-crypt")
        .build())
   .thenAccept(optHandle -> optHandle.ifPresent(handle -> {
       getLogger().info("Saved backup " + handle.id() + " for " + player.getName());
   }));
```

See [API.md](API.md) for full developer documentation, event listener guides, and threading specifications.

---

## Author & Support

- **Author:** Collin Lerche (zfzfg) | STERRA
- **Website:** https://sterra.online
- **Email:** zfzfg@sterra.online
- **License:** MIT License (see [LICENSE](LICENSE))
