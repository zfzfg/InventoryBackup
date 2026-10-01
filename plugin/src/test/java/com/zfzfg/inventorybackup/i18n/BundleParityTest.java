package com.zfzfg.inventorybackup.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Haelt die Sprachbundles deckungsgleich.
 *
 * <p>Ohne diesen Test faellt eine vergessene Uebersetzung erst auf dem Server
 * auf -- und dort still, weil der englische Fallback einspringt. Er liest die
 * YAML-Dateien direkt mit SnakeYAML, braucht also keinen laufenden Server.
 */
class BundleParityTest {

    private static final String MASTER = "messages_en.yml";
    private static final List<String> TRANSLATIONS = Collections.singletonList("messages_de.yml");

    /** Platzhalter der Form {player}, {count}, ... */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_-]+)}");

    @Test
    @DisplayName("Jede Uebersetzung hat exakt die Schluessel des englischen Bundles")
    void translationsHaveTheSameKeys() {
        Set<String> master = flatten(load(MASTER)).keySet();

        for (String bundle : TRANSLATIONS) {
            Set<String> keys = flatten(load(bundle)).keySet();

            Set<String> missing = new TreeSet<>(master);
            missing.removeAll(keys);
            assertTrue(missing.isEmpty(), bundle + " fehlen Schluessel: " + missing);

            Set<String> extra = new TreeSet<>(keys);
            extra.removeAll(master);
            assertTrue(extra.isEmpty(),
                    bundle + " hat Schluessel, die " + MASTER + " nicht kennt: " + extra
                            + " (im englischen Bundle nachtragen, sonst gibt es keinen Fallback)");
        }
    }

    @Test
    @DisplayName("Kein Text ist leer oder noch ein Platzhalter")
    void noEmptyOrPlaceholderValues() {
        List<String> bundles = new ArrayList<>(TRANSLATIONS);
        bundles.add(MASTER);

        for (String bundle : bundles) {
            for (Map.Entry<String, String> entry : flatten(load(bundle)).entrySet()) {
                String value = entry.getValue();
                assertFalse(value.trim().isEmpty(),
                        bundle + ": " + entry.getKey() + " ist leer");
                assertFalse(value.trim().equalsIgnoreCase("TODO"),
                        bundle + ": " + entry.getKey() + " ist noch nicht uebersetzt");
            }
        }
    }

    @Test
    @DisplayName("Uebersetzungen verwenden dieselben Platzhalter wie das Original")
    void placeholdersMatch() {
        Map<String, String> master = flatten(load(MASTER));

        for (String bundle : TRANSLATIONS) {
            Map<String, String> translated = flatten(load(bundle));

            for (Map.Entry<String, String> entry : master.entrySet()) {
                String value = translated.get(entry.getKey());
                if (value == null) {
                    continue; // schon vom Schluessel-Test abgedeckt
                }
                // Reihenfolge und Haeufigkeit duerfen abweichen, die Menge nicht:
                // ein fehlender Platzhalter zeigt dem Spieler eine Luecke, ein
                // ueberzaehliger bleibt roh als {foo} stehen.
                assertEquals(placeholders(entry.getValue()), placeholders(value),
                        bundle + ": " + entry.getKey() + " verwendet andere Platzhalter");
            }
        }
    }

    @Test
    @DisplayName("Jeder Schluessel aus legacy-defaults.yml existiert im englischen Bundle")
    void legacyDefaultsAreCovered() {
        Map<String, Object> legacy = load("legacy-defaults.yml");
        Object section = legacy.get("legacy-defaults");
        assertNotNull(section, "legacy-defaults.yml hat keinen legacy-defaults-Abschnitt");
        assertTrue(section instanceof Map, "legacy-defaults muss eine Zuordnung sein");

        Set<String> master = flatten(load(MASTER)).keySet();

        for (Object key : ((Map<?, ?>) section).keySet()) {
            String path = "messages." + key;
            // Faellt ein Schluessel hier durch, laeuft die Migration ins Leere:
            // sie schriebe einen Text, den nichts mehr liest.
            assertTrue(master.contains(path),
                    "legacy-defaults kennt '" + key + "', " + MASTER + " nicht");
        }
    }

    // ------------------------------------------------------------- Hilfen

    private static Set<String> placeholders(String text) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static Map<String, Object> load(String resource) {
        try (InputStream in = BundleParityTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "Ressource nicht gefunden: " + resource);
            Map<String, Object> parsed = new Yaml().load(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertNotNull(parsed, resource + " ist leer");
            return parsed;
        } catch (IOException e) {
            throw new IllegalStateException("Konnte " + resource + " nicht lesen", e);
        }
    }

    /** Verschachtelte Zuordnungen zu Pfaden wie {@code messages.prefix} plaetten. */
    private static Map<String, String> flatten(Map<String, Object> yaml) {
        Map<String, String> flat = new LinkedHashMap<>();
        flatten("", yaml, flat);
        return flat;
    }

    private static void flatten(String prefix, Map<?, ?> node, Map<String, String> out) {
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String path = prefix.isEmpty() ? String.valueOf(entry.getKey())
                                           : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flatten(path, (Map<?, ?>) value, out);
            } else {
                out.put(path, String.valueOf(value));
            }
        }
    }
}
