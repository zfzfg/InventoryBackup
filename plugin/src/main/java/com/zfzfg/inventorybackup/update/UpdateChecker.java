package com.zfzfg.inventorybackup.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zfzfg.inventorybackup.InventoryBackup;
import org.bukkit.Bukkit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Prueft auf Modrinth, ob eine neuere Version veroeffentlicht wurde.
 * Haengt nur an der Bukkit-API und an Gson (liegt Spigot/Paper bei).
 */
public final class UpdateChecker {

    /** Rueckmeldung nach Abschluss -- laeuft im Main-Thread. */
    @FunctionalInterface
    public interface Callback {
        void finished(UpdateChecker checker);
    }

    private static final String API = "https://api.modrinth.com/v2/project/%s/version";
    private static final int TIMEOUT_MS = 10_000;

    // Bewusst InventoryBackup statt JavaPlugin: der Checker braucht den
    // LanguageManager fuer seine Konsolenausgaben.
    private final InventoryBackup plugin;
    private final String projectId;
    private final String currentVersion;
    private final String contact;
    private final boolean stableOnly;

    private volatile String latestVersion;
    private volatile boolean updateAvailable;
    private volatile boolean checked;
    private volatile long lastCheckTime;

    /**
     * @param plugin     Das JavaPlugin
     * @param projectId  Modrinth-Projekt-ID oder Slug
     * @param contact    Kontakt fuer den User-Agent -- Modrinth verlangt das
     * @param stableOnly true = Vorabversionen (beta/alpha) ignorieren
     */
    public UpdateChecker(InventoryBackup plugin, String projectId, String contact, boolean stableOnly) {
        this.plugin = plugin;
        this.projectId = projectId;
        this.contact = contact;
        this.stableOnly = stableOnly;
        this.currentVersion = plugin.getDescription().getVersion();
    }

    // ---------------------------------------------------------------- API

    /** Sofortiger Abruf mit Rueckmeldung. */
    public void check(Callback callback) {
        check(0L, callback);
    }

    /**
     * @param delayTicks Verzoegerung vor dem Request (beim Start sinnvoll,
     *                   damit onEnable nicht mit Netzwerk-IO konkurriert)
     * @param callback   darf null sein
     */
    public void check(long delayTicks, Callback callback) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            try {
                performCheck();
            } catch (Exception e) {
                // Ein Update-Check ist Komfort, keine Kernfunktion:
                // er darf niemals das Plugin stoeren.
                plugin.getLogger().log(Level.WARNING,
                        plugin.getLanguageManager().getConsoleMsg(
                                "update-check-failed", "error", String.valueOf(e.getMessage())), e);
            } finally {
                // Rueckmeldung IMMER -- auch nach einem Fehler. Sonst wartet der
                // Aufrufer ewig. Und immer im Main-Thread: die Bukkit-API ist
                // nicht thread-sicher.
                if (callback != null && plugin.isEnabled()) {
                    Bukkit.getScheduler().runTask(plugin, () -> callback.finished(this));
                }
            }
        }, Math.max(0L, delayTicks));
    }

    /** true nur, wenn wirklich geprueft wurde UND eine neuere Version existiert. */
    public boolean isUpdateAvailable() { return checked && updateAvailable; }

    /** false = noch kein Ergebnis (nie gelaufen oder fehlgeschlagen). */
    public boolean hasChecked()        { return checked; }

    public String getLatestVersion()   { return latestVersion; }
    public String getCurrentVersion()  { return currentVersion; }
    public long   getLastCheckTime()   { return lastCheckTime; }
    public String getDownloadUrl()     { return "https://modrinth.com/project/" + projectId; }

    // ------------------------------------------------------------- intern

    private void performCheck() throws Exception {
        URL url = URI.create(String.format(API, projectId)).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("GET");
            // Modrinth verlangt einen aussagekraeftigen User-Agent mit Kontakt.
            connection.setRequestProperty("User-Agent",
                    plugin.getName() + "/" + currentVersion + " (" + contact + ")");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);

            int status = connection.getResponseCode();
            if (status != 200) {
                plugin.getLogger().warning(plugin.getLanguageManager().getConsoleMsg(
                        "update-check-http", "status", String.valueOf(status)));
                return;
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }

            JsonArray versions = JsonParser.parseString(body.toString()).getAsJsonArray();

            // Hoechste Version suchen, statt auf die Sortierung der API zu vertrauen.
            String highest = null;
            for (JsonElement element : versions) {
                JsonObject version = element.getAsJsonObject();
                if (stableOnly && version.has("version_type")
                        && !"release".equals(version.get("version_type").getAsString())) {
                    continue;
                }
                if (!version.has("version_number")) {
                    continue;
                }
                String number = version.get("version_number").getAsString();
                if (highest == null || isNewer(highest, number)) {
                    highest = number;
                }
            }
            if (highest == null) {
                return;
            }

            latestVersion = highest;
            updateAvailable = isNewer(currentVersion, highest);
            checked = true;
            lastCheckTime = System.currentTimeMillis();

            if (updateAvailable) {
                plugin.getLogger().info(plugin.getLanguageManager().getConsoleMsg("update-available",
                        "current", currentVersion, "latest", latestVersion, "url", getDownloadUrl()));
            } else {
                plugin.getLogger().info(plugin.getLanguageManager().getConsoleMsg(
                        "update-up-to-date", "version", currentVersion));
            }
        } finally {
            connection.disconnect();
        }
    }

    /** true, wenn {@code candidate} neuer ist als {@code current}. */
    public static boolean isNewer(String current, String candidate) {
        int[] a = numericParts(current);
        int[] b = numericParts(candidate);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int left  = i < a.length ? a[i] : 0;   // fehlendes Segment zaehlt als 0
            int right = i < b.length ? b[i] : 0;   // -> 1.2 == 1.2.0
            if (right != left) {
                return right > left;
            }
        }
        // Zahlen gleich: eine Vorabversion ist AELTER als das fertige Release.
        // 1.2.0-RC1 < 1.2.0, aber 1.2.0 == 1.2.0
        return isPreRelease(current) && !isPreRelease(candidate);
    }

    private static int[] numericParts(String version) {
        if (version == null) {
            return new int[0];
        }
        String core = version.split("[-+]", 2)[0];        // Suffix abschneiden
        List<Integer> parts = new ArrayList<>();
        for (String segment : core.split("\\.")) {
            String digits = segment.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) {
                parts.add(0);
                continue;
            }
            try {
                parts.add(Integer.parseInt(digits));
            } catch (NumberFormatException e) {
                // Zahl zu gross fuer int: als sehr gross werten, nicht als 0.
                // Sonst wuerde eine unsinnige lokale Version wie "999999999999"
                // zu 0 und der Checker meldete ein Update, das es nicht gibt.
                parts.add(Integer.MAX_VALUE);
            }
        }
        int[] out = new int[parts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = parts.get(i);
        }
        return out;
    }

    private static boolean isPreRelease(String version) {
        if (version == null) {
            return false;
        }
        String lower = version.toLowerCase(Locale.ROOT);
        return lower.contains("-rc") || lower.contains("-beta")
                || lower.contains("-alpha") || lower.contains("-pre")
                || lower.contains("-snapshot");
    }
}
