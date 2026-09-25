package com.shopbooking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopbooking.entity.TimeSlot;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface TimeSlotMapper extends BaseMapper<TimeSlot> {

    /**
     * 行级版本乐观锁占座：影响行数 = 0 表示档期已满或被并发抢占。
     */
    @Update("UPDATE time_slots SET booked_count = booked_count + 1, version = version + 1 " +
            "WHERE id = #{id} AND version = #{version} AND booked_count < capacity AND status = 'OPEN'")
    int tryBook(@Param("id") Long id, @Param("version") Integer version);

    /** 释放座位：booked_count 单调递减，不会超卖，无需乐观锁前置校验 */
    @Update("UPDATE time_slots SET booked_count = booked_count - 1, version = version + 1 " +
            "WHERE id = #{id} AND booked_count > 0")
    int release(@Param("id") Long id);
}
