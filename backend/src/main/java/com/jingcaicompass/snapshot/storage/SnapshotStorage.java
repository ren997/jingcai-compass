package com.jingcaicompass.snapshot.storage;

import com.jingcaicompass.snapshot.enums.SnapshotStorageTypeEnum;

/** 公开预测快照的临时写入、校验与不可覆盖发布契约。 */
public interface SnapshotStorage {

    /** 返回当前存储实现类型。 */
    SnapshotStorageTypeEnum storageType();

    /**
     * 把规范化字节写入临时对象并重新读取校验。
     *
     * @param objectKey 计划发布的相对对象键
     * @param content 规范化快照字节
     * @param expectedSha256 预期的小写 SHA-256
     * @return 已校验的临时对象
     */
    SnapshotStagedObject stage(String objectKey, byte[] content, String expectedSha256);

    /** 将已校验临时对象原子发布到最终对象键，不覆盖不同内容。 */
    SnapshotStoredObject publish(SnapshotStagedObject stagedObject);

    /** 校验已发布对象的长度和 SHA-256；缺失或损坏时返回 false。 */
    boolean verify(String objectKey, String expectedSha256, long expectedContentLength);

    /** 尽力清理尚未发布的临时对象。 */
    void discard(SnapshotStagedObject stagedObject);
}
