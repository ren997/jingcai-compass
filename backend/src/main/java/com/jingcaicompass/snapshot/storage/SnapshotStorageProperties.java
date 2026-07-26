package com.jingcaicompass.snapshot.storage;

import com.jingcaicompass.snapshot.enums.SnapshotStorageTypeEnum;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.StringUtils;

/**
 * 公开预测快照存储配置。
 *
 * @param type 存储实现类型
 * @param path 本地存储根目录
 */
@Validated
@ConfigurationProperties("app.snapshot.storage")
public record SnapshotStorageProperties(
        @NotNull SnapshotStorageTypeEnum type,
        @NotNull Path path
) {

    @AssertTrue(message = "app.snapshot.storage.path must not be blank")
    public boolean isPathValid() {
        return path != null && StringUtils.hasText(path.toString());
    }
}
