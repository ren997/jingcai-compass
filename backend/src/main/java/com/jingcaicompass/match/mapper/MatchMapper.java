package com.jingcaicompass.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.match.entity.MatchEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDate;

@Mapper
public interface MatchMapper extends BaseMapper<MatchEntity> {

    /** 在预测发布等状态事务中锁定单场比赛。 */
    @Select("SELECT * FROM matches WHERE id = #{id} FOR UPDATE")
    MatchEntity selectByIdForUpdate(@Param("id") Long id);

    /** 按体彩自然键锁定赛果投影，串行化同场事实版本写入。 */
    @Select("""
            SELECT * FROM matches
            WHERE lottery_date = #{lotteryDate}
              AND lottery_match_no = #{lotteryMatchNo}
            FOR UPDATE
            """)
    MatchEntity selectByLotteryIdentityForUpdate(
            @Param("lotteryDate") LocalDate lotteryDate,
            @Param("lotteryMatchNo") String lotteryMatchNo
    );
}
