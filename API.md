# InventoryBackup Developer API

Create, list, restore and delete inventory backups from your own plugin, and hook
into every backup the plugin takes on its own.

- **Artifact:** `com.zfzfg:InventoryBackup-API:0.2.0`
- **Requires:** Java 21 bytecode, Purpur 1.21.8–26.3 (Java 25 runtime on 26.1+)
- **API version:** 2 (`InventoryBackupAPI.API_VERSION`)

---

## 1. Adding the dependency

The API jar is built from the `api/` module of this repository. Build and install
it locally with:

```bash
mvn clean install
```

### Maven

```xml
<dependency>
    <groupId>com.zfzfg</groupId>
    <artifactId>InventoryBackup-API</artifactId>
    <version>0.2.0</version>
    <scope>provided</scope>
</dependency>
```

### Gradle

```groovy
compileOnly 'com.zfzfg:InventoryBackup-API:0.2.0'
```

`provided` / `compileOnly` is correct: the API classes ship inside
`InventoryBackup.jar`, so the server already has them at runtime. Do not shade
the API into your own plugin — two copies of the same class will not match.

### plugin.yml

```yaml
softdepend: [InventoryBackup]
```

`softdepend` (not `depend`) keeps your plugin loading when InventoryBackup is
absent, as long as you check availability before calling. Use `depend` only if
your plugin is useless without it.

---

## 2. Getting the API

```java
import com.zfzfg.inventorybackup.api.*;

InventoryBackupAPI api = InventoryBackupProvider.get();
```

`get()` throws `IllegalStateException` if InventoryBackup is not installed. For
an optional integration:

```java
InventoryBackupProvider.getOptional().ifPresent(api -> {
    getLogger().info("InventoryBackup found, API v" + api.getApiVersion());
    // wire up your integration here
});
```

Look the API up when you need it rather than caching it in a field — a
`/reload` replaces the registration, and a cached reference would then point at a
dead instance.

---

## 3. Threading

**Successful asynchronous results are delivered on the server thread.** Internal operations explicitly dispatch events and player access.
Failures may complete on any thread. A callback attached after a future has completed runs on the attaching thread, so consumers needing an unconditional Bukkit-thread guarantee must dispatch explicitly.
Callbacks attached immediately from the main thread can use Bukkit as below:

```java
api.getLatestBackup(playerId, "death").thenAccept(handle ->
        handle.ifPresent(h -> Bukkit.broadcastMessage("last death: " + h.createdAt())));
```

The file I/O behind each call runs off the main thread, so nothing here blocks
the server.

> **Never call `.join()` or `.get()` from the main thread.** The future is
> completed *by* the main thread, so blocking it deadlocks the server. If you
> need the result before continuing, restructure into a callback.

Two calls are main-thread-only because they read or write live Bukkit state, and
say so by returning immediately:

| Method | Why |
|---|---|
| `createBackup(Player, BackupRequest)` | reads the player's live inventory |
| `openPreview(Player, BackupHandle)` | opens a window |

`createBackup(Player, ...)` fails its future with `IllegalStateException` when
called off the main thread; `openPreview` returns `false`.

---

## 4. Creating backups

```java
api.createBackup(player, BackupRequest.builder()
                .type("quest")                 // free-form, see below
                .sourcePlugin(this)            // shown in /inv <player> list
                .metadata("quest-id", "42")    // your own key/values
                .build())
   .thenAccept(handle -> handle.ifPresent(h ->
           questStorage.remember(player.getUniqueId(), h.id())));
```

The future completes **empty** when another plugin cancelled the backup or the
write failed. Store `handle.id()` if you want to restore this exact backup later —
it is stable for the lifetime of the file.

### Backup types

The type is free-form and becomes part of the file name, so it is normalized to
`[a-z0-9-]` (max 32 chars): `"My Quest!"` becomes `"my-quest"`. Call
`BackupType.normalize(...)` yourself if you need to know the exact string a later
`listBackups(id, type)` has to match. The plugin's own types are
`BackupType.DEATH` and `BackupType.MANUAL`.

### Backing up an offline player, or something other than the live inventory

Build the `BackupSnapshot` yourself. This overload is safe from any thread:

```java
BackupSnapshot snapshot = new BackupSnapshot(
        null,             // no handle yet - it has not been stored
        contents,         // ItemStack[]
        armor,            // ItemStack[] in Bukkit order: boots, leggings, chest, helmet
        offhand,          // ItemStack or null
        level, exp);

api.createBackup(playerId, playerName, snapshot, BackupRequest.of("vault", this));
```

---

## 5. Reading backups

```java
api.listBackups(playerId)                    // all, newest first
api.listBackups(playerId, "quest")           // filtered by type
api.getLatestBackup(playerId, "death")       // most recent of a type
api.getBackup(playerId, "2026-08-09_12-00-00_quest.yml")
api.loadBackup(handle)                       // the actual items
```

`BackupHandle` is a lightweight reference — id, owner, timestamp, type, source
plugin and your metadata, but no items. Loading the items costs a file read, so
it only happens when you ask for it via `loadBackup`.

```java
api.listBackups(playerId, "quest").thenAccept(handles -> {
    for (BackupHandle handle : handles) {
        getLogger().info(handle.id() + " -> quest " + handle.metadata("quest-id"));
    }
});
```

`loadBackup` completes **empty** if the file is gone *or* its contents cannot be
decoded. That second case is deliberate: a failed decode produces an empty item
array, and handing that back as a valid snapshot would wipe the inventory of
whoever it got applied to.

---

## 6. Restoring

```java
api.restore(playerId, handle, RestoreOptions.all())
   .thenAccept(result -> {
       switch (result) {
           case APPLIED:         /* done */                       break;
           case QUEUED_FOR_JOIN: /* offline, will run on join */   break;
           case NOT_FOUND:       /* backup gone */                 break;
           case CANCELLED:       /* another plugin said no */      break;
           case FAILED:          /* see server log */              break;
       }
   });
```

If the target is **offline**, the restore is stored in `pending-restores.yml` and
applied on their next join — it survives a server restart. `result.isSuccess()`
covers both `APPLIED` and `QUEUED_FOR_JOIN`.

The backup does not have to belong to the target: restoring one player's backup
onto another is allowed, which is how "hand this loadout to everyone" flows work.

### Restoring only part of a backup

```java
RestoreOptions options = RestoreOptions.builder()
        .contents(true).armor(true).offhand(true)
        .level(false).exp(false)     // leave XP alone
        .clearBefore(false)          // add to what they carry instead of replacing it
        .dropOverflow(true)          // drop what no longer fits
        .build();
```

`RestoreOptions.all()` replaces everything; `RestoreOptions.itemsOnly()` leaves
level and experience untouched.

### Other restore flows

```java
api.giveMissingItems(playerId, handle);                       // only what they lack; -1 if offline
api.queueRestoreOnJoin(playerId, handle, RestoreOptions.all()); // force the join path
api.getPendingRestore(playerId);
api.cancelPendingRestore(playerId);
```

---

## 7. Deleting

```java
api.deleteBackup(handle);                  // one
api.deleteBackups(playerId, "quest");      // all of a type
api.deleteBackups(playerId, null);         // all of a player
```

---

## 8. Events

The events live in the plugin jar, so **you can listen for them without
compiling against the API module at all**. All fire on the main thread.

| Event | Cancellable | When |
|---|---|---|
| `BackupCreateEvent` | ✔ | before a backup is written |
| `BackupCreatedEvent` | ✘ | after it was written |
| `InventoryRestoreEvent` | ✔ | before a restore is applied |
| `InventoryRestoredEvent` | ✘ | after it was applied |
| `BackupDeletedEvent` | ✘ | after a backup was deleted |

This covers the plugin's own backups too, including the automatic one on death.

### Blocking backups

```java
@EventHandler
public void onBackupCreate(BackupCreateEvent event) {
    if (arena.contains(event.getOwnerId())) {
        event.setCancelled(true);   // arena deaths stay out of the archive
    }
}
```

### Retyping or tagging someone else's backup

```java
@EventHandler
public void onBackupCreate(BackupCreateEvent event) {
    event.setRequest(event.getRequest().withType("world-" + currentWorld));
}
```

Derive from `event.getRequest()` rather than building a fresh one, so you do not
drop what another listener already set.

### Filtering what a player gets back

```java
@EventHandler
public void onRestore(InventoryRestoreEvent event) {
    ItemStack[] contents = event.getSnapshot().contents();
    for (int i = 0; i < contents.length; i++) {
        if (isBanned(contents[i])) {
            contents[i] = null;
        }
    }
    event.setSnapshot(event.getSnapshot().withItems(
            contents, event.getSnapshot().armor(), event.getSnapshot().offhand()));
}
```

For a restore queued to an offline player, `InventoryRestoreEvent` fires **on
join**, not when it was queued.

### Reacting to deletions

```java
@EventHandler
public void onDeleted(BackupDeletedEvent event) {
    if (event.getReason() == BackupDeletedEvent.Reason.EXPIRED) {
        questStorage.forget(event.getHandle().id());
    }
}
```

`Reason` is `API`, `COMMAND` or `EXPIRED`.

---

## 9. Name and UUID lookups

Backups are filed under the player's UUID. To go from a name:

```java
api.resolvePlayerId("Steve").thenAccept(id -> id.ifPresent(this::doSomething));
api.resolvePlayerName(playerId);
```

This reads the plugin's own name index, which is filled on join, on every backup
written, and by the storage migration — it never blocks on a Mojang request. Only
players this server has actually seen resolve, which is the honest answer anyway:
a name the server has never seen has no backups either.

---

## 10. Complete example

```java
public final class MyQuests extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        if (!InventoryBackupProvider.isAvailable()) {
            getLogger().warning("InventoryBackup not found - backups disabled.");
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
    }

    /** Snapshot the player before a dangerous quest starts. */
    public void onQuestStart(Player player, String questId) {
        InventoryBackupProvider.get()
                .createBackup(player, BackupRequest.builder()
                        .type("quest")
                        .sourcePlugin(this)
                        .metadata("quest-id", questId)
                        .build())
                .thenAccept(handle -> handle.ifPresent(h -> {
                    getConfig().set("active." + player.getUniqueId(), h.id());
                    saveConfig();
                }));
    }

    /** Hand it back if the quest is failed - works offline too. */
    public void onQuestFailed(UUID playerId) {
        String backupId = getConfig().getString("active." + playerId);
        if (backupId == null) {
            return;
        }

        InventoryBackupAPI api = InventoryBackupProvider.get();
        api.getBackup(playerId, backupId)
                .thenCompose(handle -> handle
                        .map(h -> api.restore(playerId, h, RestoreOptions.itemsOnly()))
                        .orElse(CompletableFuture.completedFuture(RestoreResult.NOT_FOUND)))
                .thenAccept(result -> {
                    if (result.isSuccess()) {
                        getConfig().set("active." + playerId, null);
                        saveConfig();
                    }
                });
    }

    /** Never archive an inventory that is full of quest-only items. */
    @EventHandler
    public void onBackupCreate(BackupCreateEvent event) {
        if ("death".equals(event.getRequest().type()) && inQuestZone(event.getOwnerId())) {
            event.setCancelled(true);
        }
    }
}
```

---

## 11. Storage format

For reference — do not read these files directly, the API is the supported way in.

```
plugins/InventoryBackup/
├─ inventories/<owner-uuid>/<yyyy-MM-dd_HH-mm-ss>_<type>.yml
├─ names.yml               name <-> uuid index
└─ pending-restores.yml    restores waiting for a join
```

```yaml
storage-version: 2
player: Steve            # last known name, display only
uuid: 11111111-2222-3333-4444-555555555555
timestamp: 1786000000000
type: quest
source-plugin: MyQuests
metadata:
  quest-id: '42'
inventory: <base64>
armor: <base64>
offhand: <base64>
level: 30
exp: 0.5
```

Upgrading from 0.0.7 or earlier: backups used to live under
`inventories/<PlayerName>/`. They are moved to UUID folders automatically on
first start. Nothing is deleted — a folder whose owner cannot be determined ends
up in `inventories/_unmigrated/` for you to sort out by hand.

## API revision 2 behavior

`RestoreResult` adds `INVALID_BACKUP`, `INCOMPATIBLE_VERSION`, and `INSUFFICIENT_SPACE`.
An absent backup remains `NOT_FOUND`; invalid payloads and newer Minecraft data versions are distinct failures.
`loadBackup` fails its future for unreadable data; it returns empty only for an absent file.
`createBackup` returns empty for event cancellation; serialization and persistence failures complete exceptionally.
Queue futures succeed only after persistence. Shutdown rejects new work and completes outstanding futures exceptionally.

Snapshots defensively clone all items on input and output. The current contract is 36 storage slots plus four armor slots and separate offhand.
`giveMissingItems` sums similar items across storage, armor and offhand, equips empty armor/offhand slots and adds only the remaining deficit.
For additive restores, occupied equipment slots send saved items to storage/overflow. With `dropOverflow=false`, insufficient capacity rejects the entire restore before mutation.
