package com.shopbooking.service;

import com.shopbooking.IntegrationTestBase;
import com.shopbooking.common.BusinessException;
import com.shopbooking.entity.ServiceItem;
import com.shopbooking.entity.Shop;
import com.shopbooking.entity.TimeSlot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发 50 线程抢同一档期：数据库乐观锁 + 唯一索引保证成功数 == capacity，无超卖 */
class BookingConcurrencyTest extends IntegrationTestBase {

    @Autowired
    private BookingService bookingService;

    @Test
    void 并发50线程抢10容量_只成功10单() throws Exception {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "大包间 10 人", 10, 120);
        TimeSlot slot = createSlot(shop, item, futureOpenDate(1), LocalTime.of(18, 0), 120, 10);

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String session = "cust-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    bookingService.hold(slot.getId(), 2, session, null);
                    success.incrementAndGet();
                } catch (BusinessException expected) {
                    // 档期满/并发冲突：正确的失败路径
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(10, success.get(), "成功锁座数必须等于容量");
        assertEquals(10, slotMapper.selectById(slot.getId()).getBookedCount(), "已约数必须等于容量，无超卖");
        Integer heldCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE slot_id = ? AND status = 'HELD'",
                Integer.class, slot.getId());
        assertEquals(10, heldCount);
    }
}
