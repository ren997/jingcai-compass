package com.jingcaicompass.data.service;

import com.jingcaicompass.data.dto.DataPipelineResultDto;
import java.time.LocalDate;

/** 体彩、标准化、亚盘映射和盘口快照的业务日编排入口。 */
public interface DataPipelineService {

    /**
     * 按业务日依次执行体彩同步、标准化回填和亚盘同步。
     *
     * @param businessDate 竞彩业务日
     * @return 包含各阶段状态、计数和覆盖率的流水线报告
     */
    DataPipelineResultDto run(LocalDate businessDate);
}
