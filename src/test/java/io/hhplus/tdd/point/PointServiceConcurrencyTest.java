package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PointService 의 동시성 제어를 검증하는 통합 테스트.
 *
 * 실제 스프링 컨텍스트를 띄운 상태에서 여러 스레드가 동시에 charge/use 를 호출했을 때
 * 잔액과 히스토리가 기대한 값으로 유지되는지 확인한다.
 */
@SpringBootTest
class PointServiceConcurrencyTest {

    @Autowired
    PointService pointService;

    @Autowired
    UserPointTable userPointTable;

    @Autowired
    PointHistoryTable pointHistoryTable;

    /**
     * [테스트 작성 이유]
     * - 동일한 userId 에 대해 여러 스레드가 동시에 충전하면
     *   Lost Update 없이 모든 충전이 누적되는지 확인하기 위한 테스트.
     *
     * 시나리오:
     *  - 100개의 스레드가 동시에 userId=100 에 대해 100원씩 charge 호출
     *  - 최종 잔액은 100 * 100 = 10_000 이어야 한다.
     *  - 히스토리 개수도 100개여야 한다.
     *  - 동시성 제어가 없다면 최종 잔액이 더 작게 나오는 경우가 발생할 수 있다.
     */
    @Test
    @DisplayName("동시에 여러 요청이 충전해도 모든 금액이 반영되어야 한다")
    void concurrentCharge_shouldAccumulateAll() throws Exception {
        // given
        long userId = 100L;
        int threadCount = 100;
        long amountPerRequest = 100L;

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // when
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    pointService.charge(userId, amountPerRequest);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // then
        UserPoint result = pointService.getPoint(userId);
        assertEquals(threadCount * amountPerRequest, result.point());

        List<PointHistory> histories = pointService.getHistories(userId);
        assertEquals(threadCount, histories.size());
        assertTrue(histories.stream().allMatch(h -> h.type() == TransactionType.CHARGE));
    }

    /**
     * [테스트 작성 이유]
     * - 여러 스레드가 동시에 포인트를 사용할 때,
     *   잔액을 초과한 사용은 실패하고, 잔액이 절대로 0 미만으로 내려가지 않는지 검증하기 위한 테스트.
     *
     * 시나리오:
     *  - 초기 잔액: 10_000 포인트 (userId=200)
     *  - 각 스레드가 1_000 포인트씩 사용 시도, 스레드 수는 50개 (총 50_000 요청)
     *  - 이 중 최대 10번만 성공할 수 있고, 나머지는 IllegalStateException(잔고 부족) 발생해야 한다.
     *  - 최종 잔액은 0 이상이어야 하고,
     *    "성공한 사용 횟수 * 1_000 + 최종 잔액 = 10_000" 관계가 성립해야 한다.
     */
    @Test
    @DisplayName("동시에 여러 번 사용해도 잔액을 초과해서 사용될 수 없다")
    void concurrentUse_shouldNotOverdraw() throws Exception {
        // given
        long userId = 200L;
        long initialBalance = 10_000L;
        long useAmount = 1_000L;
        int threadCount = 50;

        // 초기 잔액 세팅
        pointService.charge(userId, initialBalance);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // when
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    pointService.use(userId, useAmount);
                    successCount.incrementAndGet();
                } catch (IllegalStateException e) {
                    // 잔고 부족으로 실패한 경우
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // then
        UserPoint result = pointService.getPoint(userId);
        long finalBalance = result.point();
        int success = successCount.get();
        int fail = failCount.get();

        // 1) 성공 + 실패 개수 == 총 요청 수
        assertEquals(threadCount, success + fail);

        // 2) 최종 잔액은 절대로 0 미만이 될 수 없다.
        assertTrue(finalBalance >= 0);

        // 3) 사용된 총 금액 = 성공 횟수 * useAmount
        long usedTotal = success * useAmount;

        // 4) 초기 잔액보다 더 많이 사용될 수 없다.
        assertTrue(usedTotal <= initialBalance);

        // 5) 초기 잔액 = 사용된 총 금액 + 최종 잔액
        assertEquals(initialBalance, usedTotal + finalBalance);

        // 6) 히스토리에서도 성공한 사용 횟수만큼 USE 내역이 있는지 확인
        List<PointHistory> histories = pointService.getHistories(userId);
        long useHistoryCount = histories.stream()
                .filter(h -> h.type() == TransactionType.USE)
                .count();
        assertEquals(success, useHistoryCount);
    }
}
