package com.jingcaicompass.snapshot.storage;

/** 快照临时写入、校验或原子发布失败。 */
public class SnapshotStorageException extends RuntimeException {

    public SnapshotStorageException(String message) {
        super(message);
    }

    public SnapshotStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
