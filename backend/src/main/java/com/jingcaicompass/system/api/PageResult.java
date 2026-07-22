package com.jingcaicompass.system.api;

import java.util.List;
import java.util.Objects;

public record PageResult<T>(
        /** 当前页记录。 */
        List<T> records,
        /** 当前页码，从 1 开始。 */
        long pageNo,
        /** 当前页大小。 */
        long pageSize,
        /** 符合条件的总记录数。 */
        long total
) {

    public PageResult {
        Objects.requireNonNull(records, "records must not be null");
        if (pageNo < 1) {
            throw new IllegalArgumentException("pageNo must be at least 1");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be at least 1");
        }
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        records = List.copyOf(records);
    }
}
