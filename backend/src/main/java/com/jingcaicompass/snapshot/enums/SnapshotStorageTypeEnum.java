package com.jingcaicompass.snapshot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 公开预测快照的存储实现类型。 */
@Getter
public enum SnapshotStorageTypeEnum {
    LOCAL("LOCAL", "本地文件");

    public static final String DESC = "快照存储类型: LOCAL-本地文件";

    private static final Map<String, SnapshotStorageTypeEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(SnapshotStorageTypeEnum::getCode, Function.identity()));

    /** 持久化与对外编码 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 可读说明 */
    private final String desc;

    SnapshotStorageTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按编码解析枚举。 */
    public static SnapshotStorageTypeEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
