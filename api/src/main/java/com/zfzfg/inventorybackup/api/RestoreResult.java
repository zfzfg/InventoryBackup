package com.zfzfg.inventorybackup.api;

/**
 * Outcome of a restore attempt.
 */
public enum RestoreResult {

    /** The player was online and the backup was written to their inventory. */
    APPLIED,

    /**
     * The player was offline, so the restore was stored and will run the next
     * time they join. Survives a server restart.
     */
    QUEUED_FOR_JOIN,

    /** The backup file does not exist, or its contents could not be read. */
    NOT_FOUND,

    /** Another plugin cancelled the {@code InventoryRestoreEvent}. */
    CANCELLED,

    /** Something went wrong; details are in the server log. */
    FAILED;

    /** True for {@link #APPLIED} and {@link #QUEUED_FOR_JOIN}. */
    public boolean isSuccess() {
        return this == APPLIED || this == QUEUED_FOR_JOIN;
    }
}
