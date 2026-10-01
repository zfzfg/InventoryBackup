package com.zfzfg.inventorybackup.api;

import java.util.Locale;

/**
 * Helpers for backup type strings.
 *
 * <p>The type becomes part of the backup's file name, so it has to survive a
 * round trip through the file system on every platform. {@link #normalize}
 * lower-cases the input and replaces everything outside {@code [a-z0-9-]} with a
 * hyphen. The plugin calls this on every type it is handed, so a consumer may
 * pass {@code "My Quest!"} and get back {@code "my-quest"} - call it yourself if
 * you want to know the exact type a later {@code listBackups(id, type)} has to
 * match.
 */
public final class BackupType {

    /** Type used for the automatic backup taken when a player dies. */
    public static final String DEATH = "death";

    /** Type used for backups triggered through {@code /inv backup}. */
    public static final String MANUAL = "manual";

    /** Longest type the file name may carry; longer input is truncated. */
    public static final int MAX_LENGTH = 32;

    private static final String FALLBACK = "custom";

    private BackupType() {
    }

    /**
     * Normalizes a backup type into the form actually stored on disk.
     *
     * @param type the raw type, may be null or empty
     * @return a non-empty string matching {@code [a-z0-9-]{1,32}}
     */
    public static String normalize(String type) {
        if (type == null || type.isEmpty()) {
            return FALLBACK;
        }

        String lower = type.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(Math.min(lower.length(), MAX_LENGTH));

        for (int i = 0; i < lower.length() && out.length() < MAX_LENGTH; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                // Collapse runs of separators instead of emitting "my---quest".
                out.append('-');
            }
        }

        // A trailing hyphen would collide with the "<timestamp>_<type>" split.
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
            out.setLength(out.length() - 1);
        }

        return out.length() == 0 ? FALLBACK : out.toString();
    }
}
