package com.shopbooking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shopbooking.common.BusinessException;
import com.shopbooking.common.MaskUtil;
import com.shopbooking.config.AppProperties;
import com.shopbooking.entity.Booking;
import com.shopbooking.entity.ServiceItem;
import com.shopbooking.entity.TimeSlot;
import com.shopbooking.mapper.BookingMapper;
import com.shopbooking.mapper.ServiceItemMapper;
import com.shopbooking.mapper.TimeSlotMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预约事务核心：锁座、确认、改期、取消、核销全部走事务 + 乐观锁，
 * 并发正确性由数据库保证，不由模型保证。
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final List<String> ACTIVE_STATUSES = List.of(
            Booking.STATUS_HELD, Booking.STATUS_CONFIRMED, Booking.STATUS_CHECKED_IN);

    private final BookingMapper bookingMapper;
    private final TimeSlotMapper slotMapper;
    private final ServiceItemMapper itemMapper;
    private final SafeRedisService redis;
    private final AppProperties props;

    public BookingService(BookingMapper bookingMapper, TimeSlotMapper slotMapper,
                          ServiceItemMapper itemMapper, SafeRedisService redis, AppProperties props) {
        this.bookingMapper = bookingMapper;
        this.slotMapper = slotMapper;
        this.itemMapper = itemMapper;
        this.redis = redis;
        this.props = props;
    }

    /* ==================== 锁座 ==================== */

    @Transactional
    public Map<String, Object> hold(Long slotId, Integer partySize, String sessionKey, Long userId) {
        releaseExpiredHolds();
        TimeSlot slot = requireOpenSlot(slotId);
        ServiceItem item = requireActiveItem(slot.getServiceItemId());
        validateSlotBookable(slot, item, partySize);

        // 同会话已有生效预约：幂等返回（弱网重试/连点/模型重复调用只生效一次）
        Booking existing = bookingMapper.findActiveBySlotAndSession(slotId, sessionKey);
        if (existing != null) {
            return view(existing);
        }

        // 行级版本乐观锁占座：影响行数=0 表示已满或被并发抢占
        tryBookWithRetry(slotId);

        Booking booking = new Booking();
        booking.setBookingNo(generateBookingNo());
        booking.setShopId(slot.getShopId());
        booking.setSlotId(slot.getId());
        booking.setServiceItemId(slot.getServiceItemId());
        booking.setCustomerSessionId(sessionKey);
        booking.setCustomerUserId(userId);
        booking.setPartySize(partySize == null ? 1 : partySize);
        booking.setStatus(Booking.STATUS_HELD);
        booking.setHoldExpireAt(LocalDateTime.now().plusMinutes(props.getBooking().getHoldMinutes()));
        booking.setSource("CHAT");
        bookingMapper.insert(booking);

        // Redis 锁座快通道（快通道挂了也不影响：数据库定时扫是最终保障）
        redis.set(holdKey(booking.getBookingNo()), String.valueOf(booking.getId()),
                Duration.ofMinutes(props.getBooking().getHoldMinutes()));

        log.info("锁座成功 booking={} slot={} session={} 到期 {}",
                booking.getBookingNo(), slotId, sessionKey, booking.getHoldExpireAt());
        return view(booking);
    }

    /* ==================== 确认 ==================== */

    @Transactional
    public Map<String, Object> confirm(String bookingNo, String customerName, String customerPhone,
                                       String remark, String sessionKey, Long userId, String actorRole) {
        Booking booking = requireByNo(bookingNo);
        authorize(booking, sessionKey, userId, actorRole);
        releaseExpiredHolds();
        booking = requireByNo(bookingNo);

        if (Booking.STATUS_CONFIRMED.equals(booking.getStatus())) {
            return view(booking); // 幂等：重复确认返回首次结果
        }
        if (!Booking.STATUS_HELD.equals(booking.getStatus())) {
            throw BusinessException.badRequest("预约单已失效（锁座可能已超时释放），请重新查询档期");
        }
        if (customerName == null || customerName.isBlank() || customerName.length() > 50) {
            throw BusinessException.badRequest("请提供顾客姓名（50 字以内）");
        }
        if (customerPhone == null || !customerPhone.matches("^1\\d{10}$")) {
            throw BusinessException.badRequest("请提供正确的 11 位手机号");
        }
        booking.setCustomerName(customerName.trim());
        booking.setCustomerPhone(customerPhone.trim());
        booking.setRemark(remark);
        booking.setCustomerUserId(userId);
        booking.setStatus(Booking.STATUS_CONFIRMED);
        bookingMapper.updateById(booking);
        redis.delete(holdKey(bookingNo));
        log.info("预约确认 booking={} phone={}", bookingNo, MaskUtil.maskPhone(customerPhone));
        return view(booking);
    }

    /* ==================== 改期（原子释放旧时段） ==================== */

    @Transactional
    public Map<String, Object> reschedule(String bookingNo, Long newSlotId, String sessionKey,
                                          Long userId, String actorRole) {
        Booking booking = requireByNo(bookingNo);
        authorize(booking, sessionKey, userId, actorRole);
        releaseExpiredHolds();
        booking = requireByNo(bookingNo);

        if (!Booking.STATUS_HELD.equals(booking.getStatus())
                && !Booking.STATUS_CONFIRMED.equals(booking.getStatus())) {
            throw BusinessException.badRequest("当前状态的预约单不能改期");
        }
        if (newSlotId != null && newSlotId.equals(booking.getSlotId())) {
            return view(booking); // 幂等：改到同时段直接返回
        }
        TimeSlot newSlot = requireOpenSlot(newSlotId);
        ServiceItem newItem = requireActiveItem(newSlot.getServiceItemId());
        validateSlotBookable(newSlot, newItem, booking.getPartySize());

        Booking clash = bookingMapper.findActiveBySlotAndSession(newSlotId, booking.getCustomerSessionId());
        if (clash != null && !clash.getId().equals(booking.getId())) {
            throw BusinessException.badRequest("您在该时段已有一笔生效预约，请先取消再改期");
        }

        tryBookWithRetry(newSlotId);   // 占新时段
        slotMapper.release(booking.getSlotId()); // 释放旧时段（同事务，失败整体回滚）
        booking.setSlotId(newSlot.getId());
        booking.setServiceItemId(newSlot.getServiceItemId());
        if (Booking.STATUS_HELD.equals(booking.getStatus())) {
            booking.setHoldExpireAt(LocalDateTime.now().plusMinutes(props.getBooking().getHoldMinutes()));
        }
        bookingMapper.updateById(booking);
        log.info("预约改期 booking={} -> slot={}", bookingNo, newSlotId);
        return view(booking);
    }

    /* ==================== 取消 ==================== */

    @Transactional
    public Map<String, Object> cancel(String bookingNo, String reason, String sessionKey,
                                      Long userId, String actorRole) {
        Booking booking = requireByNo(bookingNo);
        authorize(booking, sessionKey, userId, actorRole);
        if (Booking.STATUS_CANCELLED.equals(booking.getStatus())) {
            return view(booking); // 幂等
        }
        if (!Booking.STATUS_HELD.equals(booking.getStatus())
                && !Booking.STATUS_CONFIRMED.equals(booking.getStatus())) {
            throw BusinessException.badRequest("当前状态的预约单不能取消");
        }
        slotMapper.release(booking.getSlotId());
        booking.setStatus(Booking.STATUS_CANCELLED);
        booking.setCancelReason(reason);
        bookingMapper.updateById(booking);
        redis.delete(holdKey(bookingNo));
        log.info("预约取消 booking={} 原因={}", bookingNo, reason);
        return view(booking);
    }

    /* ==================== 到店核销 ==================== */

    @Transactional
    public Map<String, Object> checkin(String bookingNo) {
        Booking booking = requireByNo(bookingNo);
        if (!Booking.STATUS_CONFIRMED.equals(booking.getStatus())) {
            throw BusinessException.badRequest("仅已确认的预约可以核销到店");
        }
        booking.setStatus(Booking.STATUS_CHECKED_IN);
        booking.setCheckedInAt(LocalDateTime.now());
        bookingMapper.updateById(booking);
        return view(booking);
    }

    @Transactional
    public Map<String, Object> markNoShow(String bookingNo) {
        Booking booking = requireByNo(bookingNo);
        if (!Booking.STATUS_CONFIRMED.equals(booking.getStatus())) {
            throw BusinessException.badRequest("仅已确认的预约可以标记爽约");
        }
        booking.setStatus(Booking.STATUS_NO_SHOW);
        slotMapper.release(booking.getSlotId());
        bookingMapper.updateById(booking);
        return view(booking);
    }

    /* ==================== 锁座超时释放（两道保险之一：数据库定时扫） ==================== */

    /** 释放所有过期锁座并回补库存；返回释放条数。定时任务与档期查询前都会调用（懒清理）。 */
    @Transactional
    public int releaseExpiredHolds() {
        List<Booking> expired = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getStatus, Booking.STATUS_HELD)
                .lt(Booking::getHoldExpireAt, LocalDateTime.now())
                .last("LIMIT 200"));
        for (Booking booking : expired) {
            slotMapper.release(booking.getSlotId());
            booking.setStatus(Booking.STATUS_EXPIRED);
            bookingMapper.updateById(booking);
            redis.delete(holdKey(booking.getBookingNo()));
        }
        if (!expired.isEmpty()) {
            log.info("锁座超时释放 {} 条并回补库存", expired.size());
        }
        return expired.size();
    }

    /* ==================== 查询与视图 ==================== */

    public Map<String, Object> view(Booking booking) {
        TimeSlot slot = slotMapper.selectById(booking.getSlotId());
        ServiceItem item = booking.getServiceItemId() == null ? null : itemMapper.selectById(booking.getServiceItemId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookingNo", booking.getBookingNo());
        m.put("status", booking.getStatus());
        m.put("serviceName", item == null ? "" : item.getName());
        m.put("date", slot == null ? null : slot.getSlotDate().toString());
        m.put("startTime", slot == null ? null : slot.getStartTime().toString());
        m.put("endTime", slot == null ? null : slot.getEndTime().toString());
        m.put("partySize", booking.getPartySize());
        m.put("customerName", booking.getCustomerName());
        m.put("customerPhone", MaskUtil.maskPhone(booking.getCustomerPhone()));
        m.put("remark", booking.getRemark());
        m.put("cancelReason", booking.getCancelReason());
        if (booking.getHoldExpireAt() != null) {
            m.put("holdExpireAt", booking.getHoldExpireAt().toString());
            m.put("holdRemainingSeconds", Math.max(0,
                    Duration.between(LocalDateTime.now(), booking.getHoldExpireAt()).getSeconds()));
        }
        m.put("createdAt", booking.getCreatedAt() == null ? null : booking.getCreatedAt().toString());
        return m;
    }

    /**
     * 顾客"我的预约"：登录顾客按账号（userId）或当前会话键归属（跨设备不丢单）；
     * 游客只按会话键归属。不支持按手机号查询——任何人都无法查看或并入他人的预约。
     */
    public List<Map<String, Object>> listMyBookings(String sessionKey, Long userId) {
        LambdaQueryWrapper<Booking> q = new LambdaQueryWrapper<Booking>()
                .orderByDesc(Booking::getId)
                .last("LIMIT 50");
        if (userId != null) {
            q.and(w -> w.eq(Booking::getCustomerUserId, userId)
                    .or().eq(Booking::getCustomerSessionId, sessionKey));
        } else {
            q.eq(Booking::getCustomerSessionId, sessionKey);
        }
        return bookingMapper.selectList(q).stream().map(this::view).toList();
    }

    /* ==================== 内部方法 ==================== */

    private TimeSlot requireOpenSlot(Long slotId) {
        if (slotId == null) {
            throw BusinessException.badRequest("缺少档期 ID");
        }
        TimeSlot slot = slotMapper.selectById(slotId);
        if (slot == null) {
            throw BusinessException.badRequest("档期不存在，请重新查询可用时段");
        }
        if (!"OPEN".equals(slot.getStatus())) {
            throw BusinessException.badRequest("该时段已关闭，请选择其他时段");
        }
        return slot;
    }

    private ServiceItem requireActiveItem(Long itemId) {
        ServiceItem item = itemMapper.selectById(itemId);
        if (item == null || !"ACTIVE".equals(item.getStatus())) {
            throw BusinessException.badRequest("该服务已下架，请选择其他服务");
        }
        return item;
    }

    private void validateSlotBookable(TimeSlot slot, ServiceItem item, Integer partySize) {
        LocalDateTime slotStart = LocalDateTime.of(slot.getSlotDate(), slot.getStartTime());
        if (!slotStart.isAfter(LocalDateTime.now())) {
            throw BusinessException.badRequest("该时段已开始或已过去，请选择之后的时段");
        }
        LocalDate maxDate = LocalDate.now().plusDays(item.getAdvanceDays());
        if (slot.getSlotDate().isAfter(maxDate)) {
            throw BusinessException.badRequest("超出可预约范围，最多提前 " + item.getAdvanceDays() + " 天预约");
        }
        if (partySize != null) {
            if (partySize < 1 || partySize > 200) {
                throw BusinessException.badRequest("人数需在 1-200 之间");
            }
            if (partySize > item.getCapacityPerUnit()) {
                throw BusinessException.badRequest("「" + item.getName() + "」最多容纳 "
                        + item.getCapacityPerUnit() + " 人，请更换更大的服务类型");
            }
        }
    }

    /** 乐观锁占座，冲突时重读版本重试（最多 3 次），仍失败即档期已满 */
    private void tryBookWithRetry(Long slotId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            TimeSlot slot = slotMapper.selectById(slotId);
            if (slot == null) {
                throw BusinessException.badRequest("档期不存在");
            }
            if (slotMapper.tryBook(slotId, slot.getVersion()) > 0) {
                return;
            }
        }
        throw BusinessException.conflict("手慢了，该时段刚刚被订满，请为顾客推荐邻近时段");
    }

    private Booking requireByNo(String bookingNo) {
        if (bookingNo == null || bookingNo.isBlank()) {
            throw BusinessException.badRequest("缺少预约单号");
        }
        Booking booking = bookingMapper.findByNo(bookingNo.trim());
        if (booking == null) {
            throw BusinessException.notFound("预约单不存在：" + bookingNo);
        }
        return booking;
    }

    /** 顾客只能操作自己的预约；店员/老板可代操作。模型给的参数永远不当作可信输入。 */
    private void authorize(Booking booking, String sessionKey, Long userId, String actorRole) {
        if ("staff".equals(actorRole) || "owner".equals(actorRole)) {
            return;
        }
        boolean own = (sessionKey != null && sessionKey.equals(booking.getCustomerSessionId()))
                || (userId != null && userId.equals(booking.getCustomerUserId()));
        if (!own) {
            throw BusinessException.forbidden("无权操作该预约单");
        }
    }

    private String holdKey(String bookingNo) {
        return "shop-booking:hold:" + bookingNo;
    }

    private String generateBookingNo() {
        String date = LocalDate.now().format(NO_DATE);
        StringBuilder sb = new StringBuilder("SB").append(date);
        for (int i = 0; i < 6; i++) {
            sb.append(Character.forDigit(RANDOM.nextInt(36), 36));
        }
        return sb.toString().toUpperCase();
    }
}
