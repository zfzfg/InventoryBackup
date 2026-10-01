package com.zfzfg.inventorybackup.i18n;

import com.zfzfg.inventorybackup.InventoryBackup;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Laedt die Spieler- und Konsolentexte aus messages_&lt;lang&gt;.yml.
 *
 * <p>Die Sprache steht als {@code language} in der config.yml und laesst sich
 * mit /inv lang zur Laufzeit umschalten. Fehlt ein Schluessel in der gewaehlten
 * Sprache, greift ueber {@link FileConfiguration#setDefaults} der englische
 * Text -- eine unvollstaendige Uebersetzung darf das Plugin nicht kaputt machen.
 */
public final class LanguageManager {

    /** Sprachen, fuer die ein Bundle im Jar liegt. */
    private static final List<String> SUPPORTED = Collections.unmodifiableList(Arrays.asList("en", "de"));

    private static final String FALLBACK_LANGUAGE = "en";
    private static final String LEGACY_DEFAULTS_RESOURCE = "legacy-defaults.yml";

    /** Minecraft schneidet laengere Inventartitel ab. */
    private static final int INVENTORY_TITLE_LIMIT = 32;

    private final InventoryBackup plugin;

    private volatile FileConfiguration messages;
    private FileConfiguration bundledEnglish;
    private volatile String language = FALLBACK_LANGUAGE;

    public LanguageManager(InventoryBackup plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------- Laden

    /**
     * Laedt das Bundle zur konfigurierten Sprache. Eine unbekannte Sprache
     * faellt auf Englisch zurueck, ohne die config.yml zu veraendern -- ein
     * Tippfehler soll die Einstellung des Admins nicht ueberschreiben.
     */
    public void load() {
        String configured = plugin.getConfig().getString("language", FALLBACK_LANGUAGE);
        String resolved = normalize(configured);

        if (resolved == null) {
            resolved = FALLBACK_LANGUAGE;
            // Kommt aus dem Jar-Bundle, ist also englisch -- passend, denn
            // genau darauf faellt das Plugin gerade zurueck.
            plugin.getLogger().warning(getConsoleMsg("lang-unknown",
                    "lang", String.valueOf(configured), "fallback", FALLBACK_LANGUAGE));
        }

        this.language = resolved;
        String fileName = bundleName(resolved);
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false);
        }

        YamlConfiguration loaded = new YamlConfiguration();
        try { loaded.load(file); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid language bundle: " + fileName, error); }
        applyEnglishFallback(loaded);
        this.messages = loaded;

        plugin.getLogger().info(getConsoleMsg("lang-loaded", "lang", resolved, "file", fileName));
    }

    /** Bundle neu einlesen. Die config.yml laedt der Aufrufer selbst neu. */
    public void reload() {
        load();
    }

    /**
     * Legt messages_en.yml aus dem Jar als Default-Ebene unter das geladene
     * Bundle. Fehlende Schluessel liefern damit den englischen Text statt eines
     * Fehlermarkers.
     */
    private void applyEnglishFallback(FileConfiguration target) {
        FileConfiguration english = englishFromJar();
        if (english != null) {
            target.setDefaults(english);
        } else {
            // Kein Abbruch: ohne Fallback fehlen nur einzelne Texte.
            plugin.getLogger().warning("Could not load the English fallback messages ("
                    + bundleName(FALLBACK_LANGUAGE) + " missing from the jar).");
        }
    }

    private static String bundleName(String lang) {
        return "messages_" + lang + ".yml";
    }

    /** @return normalisierter Sprachcode oder {@code null}, wenn nicht unterstuetzt */
    private static String normalize(String lang) {
        if (lang == null) {
            return null;
        }
        String lower = lang.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED.contains(lower) ? lower : null;
    }

    // ------------------------------------------------------------ Sprache

    public String getLanguage() {
        return language;
    }

    public List<String> getSupportedLanguages() {
        return SUPPORTED;
    }

    /** Die unterstuetzten Sprachen als "en, de" -- fuer {available}. */
    public String getSupportedLanguagesDisplay() {
        return String.join(", ", SUPPORTED);
    }

    /**
     * Wechselt die Sprache, schreibt sie in die config.yml und laedt das Bundle
     * sofort neu.
     *
     * @return false, wenn die Sprache unbekannt ist; es wird dann nichts geaendert
     */
    public boolean setLanguage(String lang) {
        String resolved = normalize(lang);
        if (resolved == null) {
            return false;
        }

        plugin.getConfig().set("language", resolved);
        try {
            plugin.saveConfig();
        } catch (Exception e) {
            // Speichern gescheitert: Bundle trotzdem laden, damit der Wechsel
            // wenigstens bis zum Neustart wirkt, aber deutlich protokollieren.
            plugin.getLogger().severe(getConsoleMsg("lang-save-error", "error", String.valueOf(e.getMessage())));
        }

        load();
        plugin.getLogger().info(getConsoleMsg("lang-changed", "lang", resolved));
        return true;
    }

    // ---------------------------------------------------------- Nachrichten

    /** Spielernachricht mit Prefix. */
    public String getMessage(String path, String... replacements) {
        return colorize(prefix() + " " + rawText(path), replacements);
    }

    /**
     * Spielernachricht ohne Prefix -- fuer Listenzeilen und GUI-Titel, wo ein
     * vorangestelltes Prefix nur stoert bzw. Titellaenge kostet.
     */
    public String getRaw(String path, String... replacements) {
        return colorize(rawText(path), replacements);
    }

    /**
     * GUI-Titel: ohne Prefix und auf {@value #INVENTORY_TITLE_LIMIT} Zeichen
     * gekuerzt, weil Minecraft laengere Titel abschneidet.
     */
    public String getInventoryTitle(String path, String... replacements) {
        String title = getRaw(path, replacements);
        return title.length() <= INVENTORY_TITLE_LIMIT
                ? title
                : title.substring(0, INVENTORY_TITLE_LIMIT);
    }

    /**
     * Konsolentext aus {@code console.<key>}. Farbcodes werden entfernt -- im
     * Server-Log sind sie unlesbarer Ballast.
     */
    public String getConsoleMsg(String key, String... replacements) {
        String text = null;
        if (messages != null) {
            text = messages.getString("console." + key);
        }
        if (text == null) {
            // Die Migration laeuft vor load(), hat also noch kein Bundle. Statt
            // dem Admin einen Fehlermarker zu zeigen, direkt aus dem Jar lesen.
            FileConfiguration bundled = englishFromJar();
            if (bundled != null) {
                text = bundled.getString("console." + key);
            }
        }
        if (text == null) {
            text = "[missing: console." + key + "]";
        }
        return ChatColor.stripColor(colorize(text, replacements));
    }

    /** messages_en.yml aus dem Jar, einmalig geladen und gemerkt. */
    private FileConfiguration englishFromJar() {
        if (bundledEnglish == null) {
            try (InputStream in = plugin.getResource(bundleName(FALLBACK_LANGUAGE))) {
                if (in != null) {
                    bundledEnglish = YamlConfiguration.loadConfiguration(
                            new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                return null;
            }
        }
        return bundledEnglish;
    }

    private String prefix() {
        return messages == null
                ? "&8[&6InvBackup&8]&r"
                : messages.getString("messages.prefix", "&8[&6InvBackup&8]&r");
    }

    /**
     * Rohtext eines Schluessels. Der Fehlermarker bleibt bewusst unuebersetzt:
     * ein lokalisierter Marker wuerde verschleiern, welcher Schluessel fehlt.
     */
    private String rawText(String path) {
        String fallback = "&c[missing: messages." + path + "]";
        if (messages == null) {
            return fallback;
        }
        return messages.getString("messages." + path, fallback);
    }

    /**
     * Setzt Platzhalter ein und uebersetzt Farbcodes.
     *
     * @param replacements Paare aus Name und Wert, z. B. {@code "player", name}
     *                     fuer {@code {player}}
     */
    private String colorize(String text, String... replacements) {
        if (text == null) {
            return "";
        }
        String result = text;
        if (replacements != null) {
            // Schrittweite 2; ein unvollstaendiges letztes Paar wird ignoriert.
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                String value = replacements[i + 1] == null ? "" : replacements[i + 1];
                result = result.replace("{" + replacements[i] + "}", value);
            }
        }
        return ChatColor.translateAlternateColorCodes('&', result);
    }

    // ------------------------------------------------------------ Migration

    /**
     * Holt die Texte einmalig aus dem alten {@code messages:}-Block der
     * config.yml ins Sprachbundle.
     *
     * <p>Uebernommen wird ein Text nur, wenn er von jedem je ausgelieferten
     * Standardwert abweicht (siehe legacy-defaults.yml). Unveraenderte Werte
     * bleiben liegen, damit die neuen, sauber uebersetzten Texte greifen --
     * sonst wanderten z. B. die deutschen update-*-Texte aus 0.0.7 in
     * messages_en.yml.
     *
     * <p>Muss vor {@link #load()} laufen. Ist idempotent: nach dem ersten
     * Durchlauf steht {@code messages-migrated: true} in der config.yml.
     */
    public void migrateFromConfig() {
        FileConfiguration config = plugin.getConfig();

        // Bestandsconfigs kennen den Schluessel noch nicht. Explizit setzen,
        // damit er sichtbar in der Datei landet und nicht nur als Default gilt.
        boolean dirty = false;
        if (!config.isSet("language")) {
            config.set("language", FALLBACK_LANGUAGE);
            dirty = true;
        }

        if (config.getBoolean("messages-migrated", false)) {
            if (dirty) {
                plugin.saveConfig();
            }
            return;
        }

        ConfigurationSection legacy = config.getConfigurationSection("messages");
        if (legacy == null) {
            // Frische Installation: nichts zu holen, aber gleich als erledigt
            // markieren, damit spaeter niemand darueber stolpert.
            config.set("messages-migrated", true);
            plugin.saveConfig();
            return;
        }

        try {
            String targetLanguage = normalize(config.getString("language", FALLBACK_LANGUAGE));
            if (targetLanguage == null) {
                targetLanguage = FALLBACK_LANGUAGE;
            }

            String bundleFileName = bundleName(targetLanguage);
            File bundleFile = new File(plugin.getDataFolder(), bundleFileName);
            if (!bundleFile.exists()) {
                plugin.saveResource(bundleFileName, false);
            }

            File backup = new File(plugin.getDataFolder(), "config.yml.pre-i18n.bak");
            File configFile = new File(plugin.getDataFolder(), "config.yml");
            if (configFile.exists()) {
                Files.copy(configFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            ConfigurationSection shipped = loadLegacyDefaults();
            FileConfiguration bundle = YamlConfiguration.loadConfiguration(bundleFile);

            int carried = 0;
            for (String key : legacy.getKeys(true)) {
                if (legacy.isConfigurationSection(key)) {
                    continue;
                }
                String value = legacy.getString(key);
                if (value == null || wasShipped(shipped, key, value)) {
                    continue;
                }
                bundle.set("messages." + key, value);
                carried++;
            }

            if (carried > 0) {
                bundle.save(bundleFile);
            }

            config.set("messages", null);
            config.set("messages-migrated", true);
            plugin.saveConfig();

            plugin.getLogger().info(getConsoleMsg("migration-done",
                    "count", String.valueOf(carried),
                    "file", bundleFileName,
                    "backup", backup.getName()));
        } catch (Exception e) {
            // Fehlschlag darf den Start nicht verhindern: ohne
            // messages-migrated laeuft der Versuch beim naechsten Start erneut.
            plugin.getLogger().severe(getConsoleMsg("migration-failed", "error", String.valueOf(e.getMessage())));
        }
    }

    /** @return true, wenn {@code value} ein je ausgelieferter Standardwert ist */
    private boolean wasShipped(ConfigurationSection shipped, String key, String value) {
        if (shipped == null) {
            return false;
        }
        List<String> variants = shipped.getStringList(key);
        if (variants.isEmpty()) {
            String single = shipped.getString(key);
            return single != null && single.equals(value);
        }
        return variants.contains(value);
    }

    private ConfigurationSection loadLegacyDefaults() {
        try (InputStream in = plugin.getResource(LEGACY_DEFAULTS_RESOURCE)) {
            if (in == null) {
                return null;
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml.getConfigurationSection("legacy-defaults");
        } catch (IOException e) {
            // Ohne die Liste gilt jeder Wert als angepasst. Das ist die sichere
            // Richtung: lieber einen Text zu viel uebernehmen als einen zu wenig.
            plugin.getLogger().warning("Could not read " + LEGACY_DEFAULTS_RESOURCE + ": " + e.getMessage());
            return null;
        }
    }
}
