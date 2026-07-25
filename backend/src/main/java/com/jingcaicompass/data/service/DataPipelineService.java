package com.jingcaicompass.data.service;

import com.jingcaicompass.data.dto.DataPipelineResultDto;
import java.time.LocalDate;

/** 体彩、标准化、亚盘映射和盘口快照的业务日编排入口。 */
public interface DataPipelineService {

    DataPipelineResultDto run(LocalDate businessDate);
}
