package com.jingcaicompass.snapshot.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jingcaicompass.snapshot.enums.SnapshotStorageTypeEnum;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalSnapshotStorageTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void stagesVerifiesAndAtomicallyPublishesLocalObject() throws Exception {
        LocalSnapshotStorage storage = storage();
        byte[] content = "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(content);
        String objectKey = "prediction-snapshots/2026-07-26/v000001-" + hash + ".json";

        SnapshotStagedObject staged = storage.stage(objectKey, content, hash);
        assertThat(Files.isRegularFile(Path.of(staged.stagingKey()))).isTrue();

        SnapshotStoredObject stored = storage.publish(staged);

        assertThat(stored.storageType()).isEqualTo(SnapshotStorageTypeEnum.LOCAL);
        assertThat(stored.objectKey()).isEqualTo(objectKey);
        assertThat(stored.objectVersion()).isNull();
        assertThat(stored.fileUrl()).startsWith("file:");
        assertThat(stored.contentType()).isEqualTo("application/json");
        assertThat(stored.contentLength()).isEqualTo(content.length);
        assertThat(storage.verify(objectKey, hash, content.length)).isTrue();
        assertThat(Files.exists(Path.of(staged.stagingKey()))).isFalse();
    }

    @Test
    void reusesExistingObjectOnlyWhenContentMatches() throws Exception {
        LocalSnapshotStorage storage = storage();
        byte[] content = "same".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(content);
        String objectKey = "prediction-snapshots/2026-07-26/object.json";

        storage.publish(storage.stage(objectKey, content, hash));
        SnapshotStagedObject repeated = storage.stage(objectKey, content, hash);
        SnapshotStoredObject reused = storage.publish(repeated);

        assertThat(reused.sha256()).isEqualTo(hash);
        assertThat(Files.exists(Path.of(repeated.stagingKey()))).isFalse();
        assertThat(storage.verify(objectKey, hash, content.length)).isTrue();
    }

    @Test
    void refusesToOverwriteDamagedExistingObject() throws Exception {
        LocalSnapshotStorage storage = storage();
        byte[] content = "expected".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(content);
        String objectKey = "prediction-snapshots/2026-07-26/damaged.json";
        Path target = temporaryDirectory.resolve(objectKey);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "damaged", StandardCharsets.UTF_8);
        SnapshotStagedObject staged = storage.stage(objectKey, content, hash);

        assertThatThrownBy(() -> storage.publish(staged))
                .isInstanceOf(SnapshotStorageException.class)
                .hasMessageContaining("different or damaged");
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("damaged");

        storage.discard(staged);
        assertThat(Files.exists(Path.of(staged.stagingKey()))).isFalse();
    }

    @Test
    void rejectsPathTraversalAndForgedHash() {
        LocalSnapshotStorage storage = storage();
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> storage.stage("../outside.json", content, sha256(content)))
                .isInstanceOf(SnapshotStorageException.class)
                .hasMessageContaining("escapes storage root");
        assertThatThrownBy(() -> storage.stage("safe.json", content, "0".repeat(64)))
                .isInstanceOf(SnapshotStorageException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void verifyReturnsFalseForMissingWrongLengthOrCorruptedFile() throws Exception {
        LocalSnapshotStorage storage = storage();
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(content);
        String objectKey = "prediction-snapshots/2026-07-26/verify.json";

        assertThat(storage.verify(objectKey, hash, content.length)).isFalse();
        storage.publish(storage.stage(objectKey, content, hash));
        assertThat(storage.verify(objectKey, hash, content.length + 1)).isFalse();

        Files.writeString(temporaryDirectory.resolve(objectKey), "corrupted", StandardCharsets.UTF_8);
        assertThat(storage.verify(objectKey, hash, content.length)).isFalse();
    }

    private LocalSnapshotStorage storage() {
        return new LocalSnapshotStorage(new SnapshotStorageProperties(
                SnapshotStorageTypeEnum.LOCAL,
                temporaryDirectory
        ));
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
