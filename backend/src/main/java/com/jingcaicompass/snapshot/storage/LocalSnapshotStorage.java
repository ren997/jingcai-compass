package com.jingcaicompass.snapshot.storage;

import com.jingcaicompass.snapshot.enums.SnapshotStorageTypeEnum;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 使用同一文件系统临时目录和原子移动发布本地快照。 */
@Component
@ConditionalOnProperty(
        prefix = "app.snapshot.storage",
        name = "type",
        havingValue = "local",
        matchIfMissing = true
)
public class LocalSnapshotStorage implements SnapshotStorage {

    static final String CONTENT_TYPE = "application/json";
    private static final String SHA256_PATTERN = "^[0-9a-f]{64}$";

    private final Path rootPath;
    private final Path temporaryPath;

    public LocalSnapshotStorage(SnapshotStorageProperties properties) {
        this.rootPath = properties.path().toAbsolutePath().normalize();
        this.temporaryPath = rootPath.resolve(".tmp").normalize();
    }

    @Override
    public SnapshotStorageTypeEnum storageType() {
        return SnapshotStorageTypeEnum.LOCAL;
    }

    @Override
    public SnapshotStagedObject stage(
            String objectKey,
            byte[] content,
            String expectedSha256
    ) {
        // 1) 校验对象键、内容与调用方预期哈希
        Path finalPath = resolveObjectPath(objectKey);
        byte[] snapshotBytes = Objects.requireNonNull(content, "content must not be null");
        String expectedHash = requireSha256(expectedSha256);
        String contentHash = sha256Hex(snapshotBytes);
        if (!MessageDigest.isEqual(
                expectedHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                contentHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII)
        )) {
            throw new SnapshotStorageException("snapshot content hash does not match expected hash");
        }

        Path stagedPath = null;
        try {
            // 2) 在存储根目录的同一文件系统写入唯一临时文件
            Files.createDirectories(temporaryPath);
            Files.createDirectories(finalPath.getParent());
            stagedPath = temporaryPath.resolve(UUID.randomUUID() + ".tmp").normalize();
            Files.write(stagedPath, snapshotBytes);

            // 3) 从临时文件重新读取并校验长度与 SHA-256
            byte[] storedBytes = Files.readAllBytes(stagedPath);
            if (storedBytes.length != snapshotBytes.length
                    || !MessageDigest.isEqual(snapshotBytes, storedBytes)
                    || !expectedHash.equals(sha256Hex(storedBytes))) {
                throw new SnapshotStorageException("staged snapshot verification failed");
            }
            return new SnapshotStagedObject(
                    objectKey,
                    stagedPath.toString(),
                    expectedHash,
                    storedBytes.length
            );
        } catch (IOException exception) {
            deleteQuietly(stagedPath);
            throw new SnapshotStorageException("failed to stage local snapshot", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(stagedPath);
            throw exception;
        }
    }

    @Override
    public SnapshotStoredObject publish(SnapshotStagedObject stagedObject) {
        Objects.requireNonNull(stagedObject, "stagedObject must not be null");
        Path stagedPath = resolveStagingPath(stagedObject.stagingKey());
        Path finalPath = resolveObjectPath(stagedObject.objectKey());
        requireSha256(stagedObject.sha256());

        try {
            // 1) 目标已存在时只复用完全相同的不可变对象
            if (Files.exists(finalPath)) {
                return reuseExistingOrFail(stagedObject, stagedPath, finalPath);
            }

            // 2) 同一文件系统内执行不可替换的原子移动
            try {
                Files.move(stagedPath, finalPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException exception) {
                return reuseExistingOrFail(stagedObject, stagedPath, finalPath);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new SnapshotStorageException(
                        "local filesystem does not support atomic snapshot publication",
                        exception
                );
            }

            // 3) 发布后再次校验最终对象，确保返回元数据可信
            if (!verify(
                    stagedObject.objectKey(),
                    stagedObject.sha256(),
                    stagedObject.contentLength()
            )) {
                throw new SnapshotStorageException("published snapshot verification failed");
            }
            return storedObject(stagedObject, finalPath);
        } catch (IOException exception) {
            throw new SnapshotStorageException("failed to publish local snapshot", exception);
        }
    }

    @Override
    public boolean verify(String objectKey, String expectedSha256, long expectedContentLength) {
        Path objectPath = resolveObjectPath(objectKey);
        String expectedHash = requireSha256(expectedSha256);
        if (expectedContentLength < 0 || !Files.isRegularFile(objectPath)) {
            return false;
        }
        try {
            byte[] bytes = Files.readAllBytes(objectPath);
            return bytes.length == expectedContentLength
                    && expectedHash.equals(sha256Hex(bytes));
        } catch (IOException exception) {
            return false;
        }
    }

    @Override
    public void discard(SnapshotStagedObject stagedObject) {
        if (stagedObject == null || !StringUtils.hasText(stagedObject.stagingKey())) {
            return;
        }
        try {
            deleteQuietly(resolveStagingPath(stagedObject.stagingKey()));
        } catch (RuntimeException ignored) {
            // 临时对象清理是尽力操作，主失败原因由调用方记录。
        }
    }

    private SnapshotStoredObject reuseExistingOrFail(
            SnapshotStagedObject stagedObject,
            Path stagedPath,
            Path finalPath
    ) {
        if (!verify(
                stagedObject.objectKey(),
                stagedObject.sha256(),
                stagedObject.contentLength()
        )) {
            throw new SnapshotStorageException(
                    "snapshot target already exists with different or damaged content"
            );
        }
        deleteQuietly(stagedPath);
        return storedObject(stagedObject, finalPath);
    }

    private SnapshotStoredObject storedObject(
            SnapshotStagedObject stagedObject,
            Path finalPath
    ) {
        return new SnapshotStoredObject(
                SnapshotStorageTypeEnum.LOCAL,
                stagedObject.objectKey(),
                null,
                finalPath.toUri().toString(),
                CONTENT_TYPE,
                stagedObject.contentLength(),
                stagedObject.sha256()
        );
    }

    private Path resolveObjectPath(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            throw new SnapshotStorageException("snapshot object key must not be blank");
        }
        Path relativePath;
        try {
            relativePath = Path.of(objectKey);
        } catch (RuntimeException exception) {
            throw new SnapshotStorageException("snapshot object key is invalid", exception);
        }
        if (relativePath.isAbsolute()) {
            throw new SnapshotStorageException("snapshot object key must be relative");
        }
        Path resolved = rootPath.resolve(relativePath).normalize();
        if (!resolved.startsWith(rootPath) || resolved.startsWith(temporaryPath)) {
            throw new SnapshotStorageException("snapshot object key escapes storage root");
        }
        return resolved;
    }

    private Path resolveStagingPath(String stagingKey) {
        if (!StringUtils.hasText(stagingKey)) {
            throw new SnapshotStorageException("snapshot staging key must not be blank");
        }
        Path resolved;
        try {
            resolved = Path.of(stagingKey).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw new SnapshotStorageException("snapshot staging key is invalid", exception);
        }
        if (!resolved.startsWith(temporaryPath)) {
            throw new SnapshotStorageException("snapshot staging key escapes temporary root");
        }
        return resolved;
    }

    private String requireSha256(String value) {
        if (!StringUtils.hasText(value) || !value.matches(SHA256_PATTERN)) {
            throw new SnapshotStorageException(
                    "snapshot hash must be a lowercase SHA-256 hex value"
            );
        }
        return value;
    }

    private String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不能覆盖原始发布异常。
        }
    }
}
