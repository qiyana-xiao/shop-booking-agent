package com.shopbooking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopbooking.entity.Booking;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface BookingMapper extends BaseMapper<Booking> {

    @Select("SELECT * FROM bookings WHERE slot_id = #{slotId} AND customer_session_id = #{sessionId} LIMIT 1")
    Booking findBySlotAndSession(@Param("slotId") Long slotId, @Param("sessionId") String sessionId);

    @Select("SELECT * FROM bookings WHERE slot_id = #{slotId} AND customer_session_id = #{sessionId} " +
            "AND status IN ('HELD','CONFIRMED','CHECKED_IN') LIMIT 1")
    Booking findActiveBySlotAndSession(@Param("slotId") Long slotId, @Param("sessionId") String sessionId);

    @Select("SELECT * FROM bookings WHERE booking_no = #{bookingNo} LIMIT 1")
    Booking findByNo(@Param("bookingNo") String bookingNo);

    /** 会话维度最近一笔有效预约（HELD/CONFIRMED），用于对话中的预约卡片 */
    @Select("SELECT * FROM bookings WHERE customer_session_id = #{sessionId} " +
            "AND status IN ('HELD','CONFIRMED','CHECKED_IN') ORDER BY id DESC LIMIT 1")
    Booking findLatestActiveBySession(@Param("sessionId") String sessionId);

    /* ---- 以下看板聚合由 resources/mapper/BookingMapper.xml 实现 ---- */

    long countConversations(@Param("shopId") Long shopId,
                            @Param("from") LocalDateTime from,
                            @Param("to") LocalDateTime to);

    long countEscalatedConversations(@Param("shopId") Long shopId,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    long countBookingsByStatus(@Param("shopId") Long shopId,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to,
                               @Param("statuses") List<String> statuses);

    List<Map<String, Object>> heatBySlotTime(@Param("shopId") Long shopId,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);

    List<Map<String, Object>> topEscalationReasons(@Param("shopId") Long shopId,
                                                   @Param("from") LocalDateTime from);

    List<Map<String, Object>> listDayViews(@Param("shopId") Long shopId, @Param("date") LocalDate date);

    /** 未来预约按天汇总（今日预约页的"后面几天还有哪些预约"总览） */
    List<Map<String, Object>> upcomingSummary(@Param("shopId") Long shopId,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to);

    List<Map<String, Object>> listExportRows(@Param("shopId") Long shopId,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);
}
