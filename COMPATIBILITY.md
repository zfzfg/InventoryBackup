# Compatibility verification — InventoryBackup 0.2.0

Checked on 2026-10-01. All 46 unit/MockBukkit tests passed; package checks passed.
Build completed with JDK 21 and `release=21`.

| Purpur version | Build | Java | Server integration |
|---|---:|---:|---|
| 1.21.8 | 2497 | 21 | PASS |
| 1.21.9 | 2505 | 21 | PASS |
| 1.21.10 | 2535 | 21 | PASS |
| 1.21.11 | 2568 | 21 | PASS |
| 26.1.2 | 2592 | 25 | PASS |
| 26.2 | 2633 | 25 | PASS |
| 26.3 | 2642 | 25 | PASS |

## What was verified

Real disposable servers loaded the plugin and its API, saved and loaded NBT snapshots,
read legacy ObjectStream fixtures with 41-slot inventories, and completed 12 concurrent backups without overwriting files.
Item equality was checked for enchanted swords with names, lore, PDC and attribute modifiers,
written books, potions, filled shulker boxes, armor and offhand; level and experience also matched.
A separate upgrade test successfully loaded the populated 1.21.8 NBT fixture on 26.3 build 2642 and verified the same item equality checks.
The legacy fixtures were generated on each test server in the old encoding; these are not historical archives exported by a previous plugin release.

Regression tests additionally cover damaged payloads, missing armor, newer data versions,
immutable snapshots, quantity-aware missing items, insufficient capacity without partial changes,
queue persistence failures, concurrent flushes, stale queue callbacks, restart loading,
disconnect-to-offline queueing, retention protection, 1000 filename collisions, rejected reloads and shutdown completion.
Synthetic death events cover generic damage, fall, void and kill sources with both keepInventory settings.
Mock GUI events cover shift-click, number keys, double-click, creative, offhand swap and dragging.

## Release status and remaining checks

**Compatibility candidate; no production release has been published.** Purpur 26.3 build 2642 is experimental.
The following checks require real player sessions and the intended server's plugin set; they are not claimed as completed:

- Trigger combat, fall, void and `/kill` deaths with populated inventories, with keepInventory on and off; compare the captured inventory before death.
- Join after queuing a restore, disconnect during loading, reconnect and replace/cancel a pending entry; confirm only the current session and current request are applied.
- Perform actual client inventory interactions, including creative mode, to confirm no preview items can leave the GUI.
- Upgrade a copy of an actual 0.0.7/0.1.0 plugin data directory; inspect the pre-upgrade ZIP, UUID migration and restore representative historical backups.

Release only after these checks are recorded as successful. Future Minecraft versions are not covered by this matrix.
Filesystem replacement is not a transaction with Minecraft player data: a hard crash between application and queue removal persistence can replay a restore.

## Reproduction and evidence

Run `mvn clean verify -Pserver-tests`, then `python tests/check_package.py`.
Run `python tests/run_servers.py --java21 <java21> --java25 <java25>` to repeat the pinned matrix.
The runner creates isolated localhost-only servers and records console logs, server checksums and plugin checksums under `target/server-tests/`.
Never install the integration-test plugin on a production server; it shuts the server down after testing.

Full-matrix plugin SHA-256: `dec4b87b263b15034e0bb1fac5d5bc994a3da9ef17b392b75bc05dd4d5a32d4f`.

Upgrade-test plugin SHA-256: `a52ca3cec1fc0209778705228d6c6a6930cdd45dea247400a37acc3a29900cb1`. The rebuild since the full matrix changed production comments and indentation only.
