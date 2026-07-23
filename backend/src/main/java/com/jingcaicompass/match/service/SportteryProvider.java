package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.SportteryMatchDto;

import java.time.LocalDate;
import java.util.List;

public interface SportteryProvider {

    String providerCode();

    List<SportteryMatchDto> findDailyMatches(LocalDate lotteryDate);
}
