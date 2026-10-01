# Compatibility verification — InventoryBackup 0.2.0

Checked on 2026-10-01. All **55 unit/MockBukkit tests passed**, with no skipped tests. Maven verification, Java 21 package checks and historical fixture checksum checks passed. The shared plugin and public API compile against Spigot 1.21.8; the single plugin JAR contains Java 21 bytecode and no bundled server dependencies.

| Minecraft | Java | Spigot | Paper | Purpur |
|---|---:|---|---|---|
| 1.21.8 | 21 | PASS | PASS | PASS |
| 1.21.9 | 21 | Unavailable¹ | PASS | PASS |
| 1.21.10 | 21 | PASS | PASS | PASS |
| 1.21.11 | 21 | PASS | PASS | PASS |
| 26.1.2 | 25 | PASS | PASS | PASS |
| 26.2 | 25 | PASS | PASS | PASS |
| 26.3 | 25 | PASS — experimental | PASS — experimental | PASS — experimental |

¹ Official BuildTools resolves the requested Spigot 1.21.9 revision to Minecraft 1.21.10. No exact 1.21.9 artifact was tested or claimed as supported. This is recorded as unavailable rather than a successful test.

## Storage and historical archives

Paper/Purpur use their native item byte API; Spigot uses an isolated CraftBukkit/NMS codec with Minecraft's registry-aware item serialization and data conversion. Format 3 is retained across platforms, including `DataVersion`. Adapter detection and an NBT roundtrip run before configuration, ZIP or inventory mutations. Unknown adapters and unsupported server versions disable the plugin; there is no alternate lossy writer. Folia is not supported.

The original public serializer helpers are again matched ObjectStream write/read pairs. New backups use the explicit NBT codec. Legacy archives and new NBT archives have separate read paths. Newer data versions, corrupt payloads, unknown formats and missing inventory/armor are rejected before application.

Unmodified original **0.0.7 and 0.1.0** JARs generated the checked-in historical fixtures on Purpur 1.20.1 build 2062 with Java 17. Provenance, source JAR hashes, server hash and every original file hash are in [tests/fixtures](tests/fixtures/README.md). These are freshly exported historical-format datasets, not a sample of every production archive ever written.

Both complete release directories were upgraded on every available matrix combination. The checks cover historical listing/loading, 41-to-36 slot normalization, player-name-to-UUID migration, original inventory bytes remaining unchanged, and the pre-upgrade ZIP containing every original file byte for byte. Item checks include names, lore, enchantments, PDC, books, potions, filled shulker boxes, armor, offhand and XP. Legacy block entity NBT is converted before Bukkit deserializes it, preserving the old shulker contents.

## Transfers, upgrades and clients

Each available platform exported populated format-3 backups. Other platforms on the same Minecraft version loaded them and compared complete item NBT semantically, including attributes and additional nested custom data/byte arrays. Every newer target also loaded backups exported by all three 1.21.8 platforms. Null slots, armor, offhand, XP, public legacy helpers and 12 concurrent writes were verified on real servers.

Two real protocol clients tested **Spigot, Paper and Purpur 1.21.8**. Eight vanilla deaths cover combat, fall, void and `/kill`, each with keepInventory enabled and disabled. Preview GUI checks send survival and creative click packets, shift clicks, number keys, offhand swaps, double clicks and drags. A creative packet duplication defect found by these clients was fixed. Clients also disconnect, cancel and replace a pending restore, reconnect and verify that the replacement applies and the queue entry is consumed.

Unit regressions additionally cover startup failure without archive changes, bounded NBT expansion and nesting, multiline Base64, cyclic legacy objects, incomplete backups, future data versions, filename/migration collisions, capacity rejection without partial changes, pending queue persistence/races, retention protection and shutdown completion. Public backup/restore API signatures and events are unchanged.

## Evidence and reproduction

[tests/server-results.json](tests/server-results.json) records the exact server builds/revisions, SHA-256 values, transfer counts, historical checks and client results. Full local server logs and results are under `target/server-tests/`; client logs are under `target/client-tests/`. CI repeats the matrix, baseline upgrades, both historical archives and baseline client checks, and uploads evidence. CI itself has not been run remotely during this implementation.

```text
mvn verify -Pserver-tests,client-tests,historical-fixtures
python tests/check_package.py
python tests/check_fixtures.py
python tests/run_servers.py --java21 <java21> --java25 <java25> --historical-data tests/fixtures/0.0.7/data
python tests/run_servers.py --no-transfers --java21 <java21> --java25 <java25> --historical-data tests/fixtures/0.1.0/data
npm ci --ignore-scripts --prefix tests/clients
python tests/run_clients.py --java21 <java21>
```

Spigot is built using official BuildTools; Paper downloads are checksum verified and cached with their build manifests; Purpur builds are pinned. Runners create disposable localhost servers and accept the Minecraft EULA in those directories. Never install test harness plugins on production servers; they shut down after testing.

Verified plugin SHA-256: `f462f9a7bf804bf2025ea928fd2ebdddcc1924305f2b7f69d0e266119c5516b3`.

## Release limits

No production release has been published. Minecraft 26.3 remains experimental. Future versions and Minecraft versions below 1.21.8 are excluded. Downgrading newer item data to older servers remains unsupported.

Real client coverage is limited to 1.21.8. Before production deployment, exercise the intended server's plugin set, representative actual player archives and disconnects during an in-flight restore on the selected target version. Historical fixture checks establish the representative formats above, not universal historical compatibility. Filesystem replacement is not a transaction with Minecraft player data: a hard crash between applying a restore and persisting queue removal can replay it.
