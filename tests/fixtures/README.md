# Historical release archives

These are newly generated fixtures written by the **unmodified original plugin JARs**, not by an emulation of their serializer. Both original releases ran on Purpur 1.20.1 build 2062 with Java 17. Their source JARs were read from the local archived projects; they are not redistributed here.

Each release directory contains the complete generated plugin data directory, its original export console log, and a manifest with SHA-256 values for the historical plugin, server, fixture generator and every data file. The original writer stored the inventories, armor, offhand and XP. The 0.0.7 fixture uses a player-name folder and a 41-slot inventory; 0.1.0 uses the public API and a UUID folder with 36 inventory slots.

The fixed dataset contains an enchanted named sword with lore and PDC, a written book, a healing potion, a shulker box containing that book, diamond boots, a shield, level 35 and 0.25 experience. These are representative historical-format fixtures, not backups collected from a production player.

Run `python tests/check_fixtures.py` to verify that nothing has changed. `tests/run_servers.py --historical-data tests/fixtures/0.0.7/data` (or `0.1.0/data`) copies the archive into disposable servers, checks the loaded items, verifies the pre-upgrade ZIP byte for byte and checks that the inventory files are preserved after migration.

To regenerate in a separate checkout, first build `mvn package -Phistorical-fixtures -pl tests/fixture-plugin -am`, then run:

```text
python tests/export_historical.py --release 0.0.7 --plugin-jar <original-0.0.7.jar> --java <java17>
python tests/export_historical.py --release 0.1.0 --plugin-jar <original-0.1.0.jar> --java <java17>
```

The exporter refuses to overwrite an existing fixture directory. Never install either test harness on a production server.
