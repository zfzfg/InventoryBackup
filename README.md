# InventoryBackup Plugin

A Minecraft Spigot plugin that automatically saves player inventories on death and provides comprehensive restore functionality.

- **Modrinth Project:** https://modrinth.com/project/rpKY25cW
- **Developer API Artifact:** `com.zfzfg:InventoryBackup-API:0.1.0`

## Author

**Collin Lerche (zfzfg) | STERRA**  
Website: https://sterra.online  
Email: zfzfg@sterra.online

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

## Features

- **Automatic Death Backups**: Automatically saves player inventories when they die (two-stage caching prevents loss on one-shot deaths)
- **Manual Backups**: Create manual backups for any online player or all players at once
- **Inventory Restoration**: Restore inventories completely from saved backups
- **Inventory Preview**: View saved inventories in a secure, click-protected GUI
- **Missing Items**: Give only the items that are missing from a player's current inventory
- **Backup Management**: List and delete backups for individual players
- **Offline Restores**: Restores aimed at offline players are queued in `pending-restores.yml` and applied on their next join — surviving restarts
- **Developer API**: Other plugins can create, list, restore, and delete backups, and hook into operations with cancellable events — see [API.md](API.md)
- **UUID-Based Storage**: Backups are organized under player UUIDs, ensuring player history persists across username changes
- **Auto-Cleanup**: Automatically delete old backup files after a configurable retention period
- **Modrinth Update Checker**: Automatic update checking with cached join notifications and `/inv version` command
- **Multi-Language Support**: All player and console messages localized in English and German, switchable at runtime with `/inv lang`
- **Thread-Safe & Secure**: Protected against path traversal, GUI duplication exploits, and concurrent modification issues

## Requirements

- Java 17 or higher
- Minecraft 1.20+ (Spigot / Paper / Purpur)

## Installation

1. Build the plugin:
   ```bash
   mvn clean package
   ```

2. The plugin JAR will be located at `plugin/target/InventoryBackup-0.1.0.jar`

3. Drop the JAR into your server's `plugins/` folder

4. Start or reload your server

The project is structured as a multi-module Maven build: `plugin/` produces the server plugin JAR, while `api/` produces the artifact other developers compile against (`api/target/InventoryBackup-API-0.1.0.jar`). The API is shaded into the plugin JAR, so server administrators only need the single plugin file.

## Configuration

The plugin creates a `config.yml` file with the following options:

```yaml
# Language for all plugin and console messages: "en" or "de"
language: "en"

# How many days to keep inventory files before auto-deletion (0 = disabled)
auto-delete-days: 30

# Check interval for file cleanup (in hours)
cleanup-interval-hours: 24

# Save inventory on death
save-on-death: true

# Performance settings
cache-cleanup-interval: 30 # seconds

# How long an unapplied restore for an offline player is kept in days (0 = forever)
pending-restore-expiry-days: 30

# Notification settings
notify-on-backup: true
notify-ops-only: true

# Update check (Modrinth)
update-check:
  enabled: true               # Master switch -- can be disabled
  check-on-startup: true      # Fetch on server start
  notify-admins-on-join: true # Notice in chat when an admin joins
  modrinth-project-id: "rpKY25cW"
  contact: "zfzfg@sterra.online" # Included in User-Agent header
  stable-only: true           # Ignore pre-releases
  startup-delay-ticks: 20     # 20 ticks = 1 second
```

## Languages

Message texts are decoupled from `config.yml` and stored in dedicated language bundles:

```
plugins/InventoryBackup/
├── config.yml          <- language: "en"
├── messages_en.yml     <- English texts (also the master fallback)
└── messages_de.yml     <- German texts
```

Select a language in `config.yml` or switch it live at runtime:

```bash
/inv lang          # show the current language
/inv lang de       # switch to German and reload immediately
/inv reload        # re-read config.yml and the active message bundle
```

`/inv lang` updates `config.yml` and reloads texts instantly, requiring no server restart.

To customize wording, edit `messages_<language>.yml` and run `/inv reload`. Placeholders in `{curly braces}` are filled dynamically by the plugin. Color codes use `&`. If a key is missing from a translation file, the plugin automatically falls back to the English default.

### Upgrading from 0.0.7 or earlier

Older versions kept texts inside a `messages:` block in `config.yml`. On first startup after upgrading, messages are migrated automatically:

- Custom texts are preserved in your language bundle.
- Untouched defaults are updated to the latest translations.
- Your previous `config.yml` is saved as `config.yml.pre-i18n.bak`.

The migration runs once and marks `messages-migrated: true` in `config.yml`.

## Commands

The base command is `/inv`. Aliases: `/inventory`, `/invbackup`.

| Command | Description | Permission |
|---------|-------------|------------|
| `/inv backup all` | Backup all online players | `inventorybackup.backup` |
| `/inv backup <player>` | Backup a specific online player | `inventorybackup.backup` |
| `/inv <player> restore <filename>` | Restore a saved inventory (applies immediately or queues if offline) | `inventorybackup.restore` |
| `/inv <player> show <filename>` | View saved inventory in a protected GUI | `inventorybackup.show` |
| `/inv <player> givemissing <filename>` | Give missing items only | `inventorybackup.givemissing` |
| `/inv <player> list` | List all backups and types for a player | `inventorybackup.show` |
| `/inv <player> delete <filename>` | Delete a specific backup | `inventorybackup.restore` |
| `/inv <player> delete all` | Delete all backups for a player | `inventorybackup.restore` |
| `/inv <player> pending` | Show the restore queued for an offline player | `inventorybackup.pending` |
| `/inv <player> cancelpending` | Cancel a queued offline restore | `inventorybackup.pending` |
| `/inv version` | Check for updates and show version | `inventorybackup.use` |
| `/inv lang` | Show the current message language | `inventorybackup.lang` |
| `/inv lang <en\|de>` | Switch language and reload immediately | `inventorybackup.lang` |
| `/inv reload` | Reload `config.yml` and the message bundle | `inventorybackup.reload` |

> `backup`, `version`, `lang`, and `reload` are reserved as subcommands, so a player with one of those names cannot be addressed via `/inv <name> ...`.

### Tab Completion

Full tab completion is supported for:
- Subcommands (`backup`, `version`, `lang`, `reload`)
- Actions (`restore`, `show`, `givemissing`, `list`, `delete`, `pending`, `cancelpending`)
- Online players and known offline players (indexed in `names.yml`)
- Language codes (`en`, `de`)
- Backup filenames for the targeted player

## Permissions

All permissions default to OP:

- `inventorybackup.use` - Use all inventory backup commands and `/inv version`
- `inventorybackup.restore` - Restore inventories and delete backups
- `inventorybackup.show` - Show saved inventories and list backups
- `inventorybackup.givemissing` - Give missing items
- `inventorybackup.backup` - Create manual backups
- `inventorybackup.notify` - Receive backup notification messages
- `inventorybackup.update.notify` - Receive update notification messages on join
- `inventorybackup.reload` - Reload configuration and message bundles
- `inventorybackup.lang` - View and change message language
- `inventorybackup.pending` - View and cancel restores queued for offline players

## How It Works

### Death Backup System

The plugin uses a two-stage caching system to ensure reliable inventory capture:

1. **Damage Detection**: When a player takes damage that brings them to 4 hearts or less, their inventory is cached synchronously on the main thread.
2. **Death Event**: When the player dies, the cached inventory is saved to disk asynchronously.

This ensures that even one-shot deaths (void, fall damage, instant effects) are captured reliably.

### File Structure

```
plugins/InventoryBackup/
├── inventories/<owner-uuid>/<yyyy-MM-dd_HH-mm-ss>_<type>.yml
├── names.yml               <- player name <-> UUID index cache
└── pending-restores.yml    <- restores waiting for player join
```

Example: `inventories/11111111-2222-3333-4444-555555555555/2026-08-11_15-30-45_death.yml`

Backups are organized by player **UUID**, ensuring history is preserved across name changes. Types are free-form strings (e.g. `death`, `manual`, `quest`, `pvp`).

#### Upgrading from 0.0.7 or earlier

Older versions stored backups under `inventories/<PlayerName>/`. On first startup, directories are migrated to UUIDs automatically using the `uuid` field contained in every backup file — requiring zero Mojang web lookups. Unresolved folders are safely preserved in `inventories/_unmigrated/`. The migration is recorded as `storage-version: 2` in `config.yml`.

### Auto-Cleanup

Old backup files are automatically pruned according to `auto-delete-days`. The cleanup task runs every `cleanup-interval-hours` and parses timestamps directly from filenames for optimal performance.

## Developer API

Other plugins can interact directly with InventoryBackup — create backups before dangerous events, restore them later, or listen to cancellable events:

```java
InventoryBackupAPI api = InventoryBackupProvider.get();

api.createBackup(player, BackupRequest.builder()
                .type("quest")
                .sourcePlugin(this)
                .metadata("quest-id", "42")
                .build())
   .thenAccept(handle -> handle.ifPresent(h -> remember(h.id())));
```

See **[API.md](API.md)** for the complete API reference, Maven coordinates, threading guarantees, event hooks, and integration examples.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for full version history.

## Support

For issues, questions, or suggestions, visit https://sterra.online or contact zfzfg@sterra.online.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
