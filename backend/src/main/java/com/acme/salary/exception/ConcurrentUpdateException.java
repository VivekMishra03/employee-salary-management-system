package com.acme.salary.exception;

/**
 * FR-2.6: the caller edited a stale copy. Someone else changed the record after it was read, so
 * applying this write would silently overwrite their change -- the failure Excel cannot prevent.
 * The client must re-read and reapply.
 */
public class ConcurrentUpdateException extends ConflictException {

    public static final String CODE = "CONCURRENT_UPDATE";

    public ConcurrentUpdateException() {
        super(CODE, "The record was modified by someone else. Reload it and try again");
    }
}
