package com.jingcaicompass.match.application.provider;

import java.time.LocalDate;
import java.util.List;

public interface SportteryProvider {

    String providerCode();

    List<SportteryMatchDto> findDailyMatches(LocalDate lotteryDate);
}
