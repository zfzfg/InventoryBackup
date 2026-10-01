package com.zfzfg.inventorybackup.api;

import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Describes a backup that is about to be created: its type, its origin, and any
 * metadata you want to find it by later.
 *
 * <pre>{@code
 * BackupRequest request = BackupRequest.builder()
 *         .type("quest")
 *         .sourcePlugin(this)
 *         .metadata("quest-id", "42")
 *         .build();
 * }</pre>
 *
 * <p>Metadata is written into the backup file and comes back on the
 * {@link BackupHandle}, so it survives a server restart. Keep the values short -
 * they live in YAML, not a database.
 */
public final class BackupRequest {

    private final String type;
    private final String sourcePlugin;
    private final Map<String, String> metadata;

    private BackupRequest(Builder builder) {
        this.type = BackupType.normalize(builder.type);
        this.sourcePlugin = builder.sourcePlugin;
        this.metadata = builder.metadata.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
    }

    /** A request with the given type and no metadata. */
    public static BackupRequest of(String type) {
        return builder().type(type).build();
    }

    /** A request with the given type, attributed to the calling plugin. */
    public static BackupRequest of(String type, Plugin sourcePlugin) {
        return builder().type(type).sourcePlugin(sourcePlugin).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Normalized backup type; never null. */
    public String type() {
        return type;
    }

    /** Name of the requesting plugin, or null. */
    public String sourcePlugin() {
        return sourcePlugin;
    }

    /** Immutable metadata map; never null. */
    public Map<String, String> metadata() {
        return metadata;
    }

    /** Returns a copy of this request with a different type. */
    public BackupRequest withType(String type) {
        Builder b = builder().type(type).sourcePluginName(sourcePlugin);
        metadata.forEach(b::metadata);
        return b.build();
    }

    /** Returns a copy of this request with different metadata. */
    public BackupRequest withMetadata(Map<String, String> metadata) {
        Builder b = builder().type(type).sourcePluginName(sourcePlugin);
        if (metadata != null) {
            metadata.forEach(b::metadata);
        }
        return b.build();
    }

    public static final class Builder {

        private String type = BackupType.MANUAL;
        private String sourcePlugin;
        private final Map<String, String> metadata = new LinkedHashMap<>();

        /**
         * Sets the backup type. Free-form; it is normalized through
         * {@link BackupType#normalize(String)}. Defaults to {@code manual}.
         */
        public Builder type(String type) {
            this.type = type;
            return this;
        }

        /** Attributes the backup to a plugin. Shown in {@code /inv ... list}. */
        public Builder sourcePlugin(Plugin plugin) {
            this.sourcePlugin = plugin == null ? null : plugin.getName();
            return this;
        }

        /** Same as {@link #sourcePlugin(Plugin)} but by name. */
        public Builder sourcePluginName(String pluginName) {
            this.sourcePlugin = pluginName;
            return this;
        }

        /**
         * Attaches one metadata entry. Null keys and values are ignored.
         *
         * <p>Keys are restricted to {@code [A-Za-z0-9_-]}; anything else becomes
         * an underscore. Metadata lands in a YAML section, where a dot in a key
         * would be read back as nesting and the entry would come out under a
         * name nobody asked for.
         */
        public Builder metadata(String key, String value) {
            if (key != null && value != null) {
                String safeKey = key.replaceAll("[^A-Za-z0-9_-]", "_");
                if (!safeKey.isEmpty()) {
                    metadata.put(safeKey, value);
                }
            }
            return this;
        }

        public BackupRequest build() {
            return new BackupRequest(this);
        }
    }
}
