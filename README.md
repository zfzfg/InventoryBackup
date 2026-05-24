# InventoryBackup Plugin

A Minecraft Spigot plugin that automatically saves player inventories on death and provides comprehensive restore functionality.

## Author

**Collin Lerche (zfzfg) | STERRA**  
Website: https://sterra.online  
Email: contact@sterra.online

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

## Features

- **Automatic Death Backups**: Automatically saves player inventories when they die
- **Manual Backups**: Create manual backups for any online player
- **Inventory Restoration**: Restore inventories from saved backups
- **Inventory Preview**: View saved inventories in a GUI
- **Missing Items**: Give only the items that are missing from a player's current inventory
- **Backup Management**: List and delete backups for individual players
- **Auto-Cleanup**: Automatically delete old backup files after a configurable time period
- **Thread-Safe**: Safe for multi-threaded server environments
- **Secure**: Protected against path traversal attacks

## Requirements

- Java 17 or higher
- Minecraft 1.20.1 (Spigot/Paper)

## Installation

1. Build the plugin:
   ```bash
   mvn clean package
   ```

2. The plugin JAR will be in `target/InventoryBackup-0.0.6.jar`

3. Drop the JAR into your server's `plugins` folder

4. Start or reload your server

## Configuration

The plugin creates a `config.yml` file with the following options:

```yaml
# How many days to keep inventory files before auto-deletion
# Set to 0 to disable auto-deletion
auto-delete-days: 30

# Check interval for file cleanup (in hours)
cleanup-interval-hours: 24

# Save inventory on death
save-on-death: true

# Performance settings
cache-cleanup-interval: 30 # seconds

# Notification settings
notify-on-backup: true
notify-ops-only: true
```

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/inv backup all` | Backup all online players | `inventorybackup.backup` |
| `/inv backup <player>` | Backup a specific player | `inventorybackup.backup` |
| `/inv <player> restore <filename>` | Restore a saved inventory | `inventorybackup.restore` |
| `/inv <player> show <filename>` | View saved inventory GUI | `inventorybackup.show` |
| `/inv <player> givemissing <filename>` | Give missing items only | `inventorybackup.givemissing` |
| `/inv <player> list` | List all backups for a player | `inventorybackup.show` |
| `/inv <player> delete <filename>` | Delete a specific backup | `inventorybackup.restore` |
| `/inv <player> delete all` | Delete all backups for a player | `inventorybackup.restore` |

## Permissions

All permissions default to OP only:

- `inventorybackup.use` - Use all inventory backup commands
- `inventorybackup.restore` - Restore inventories
- `inventorybackup.show` - Show saved inventories
- `inventorybackup.givemissing` - Give missing items
- `inventorybackup.backup` - Create manual backups
- `inventorybackup.notify` - Receive backup notification messages

## How It Works

### Death Backup System

The plugin uses a two-stage caching system to ensure reliable inventory capture:

1. **Damage Detection**: When a player takes damage that would bring them to 4 hearts or less, their inventory is cached synchronously
2. **Death Event**: When the player actually dies, the cached inventory is saved to disk asynchronously

This ensures that even one-shot deaths are captured reliably.

### File Structure

Backups are stored in `plugins/InventoryBackup/inventories/<playername>/` with filenames in the format:
```
yyyy-MM-dd_HH-mm-ss_type.yml
```

For example: `2024-05-24_15-30-45_death.yml`

### Auto-Cleanup

Old backup files are automatically deleted based on the `auto-delete-days` setting. The cleanup runs every `cleanup-interval-hours` and parses timestamps from filenames for optimal performance.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for a detailed history of changes.

## Support

For issues, questions, or suggestions, visit https://sterra.online or contact contact@sterra.online

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
