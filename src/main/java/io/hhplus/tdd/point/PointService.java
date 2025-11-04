package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 포인트 비즈니스 로직을 담당하는 서비스 클래스.
 *
 * 기본 기능:
 *  - 포인트 조회
 *  - 포인트 충전/사용 내역 조회
 *  - 포인트 충전
 *  - 포인트 사용 (잔고 부족 시 실패)
 *
 * 심화 기능(동시성 제어):
 *  - 같은 userId 에 대한 charge/use 요청이 동시에 들어오더라도
 *    "한 번에 하나의 요청만" 처리되도록 사용자별 Lock 을 사용했다.
 */
@Service
public class PointService {

    private final UserPointTable userPointTable;
    private final PointHistoryTable pointHistoryTable;

    /**
     * userId 별로 잠금을 관리하기 위한 맵.
     *
     * - key: userId
     * - value: 해당 유저의 포인트 변경 시 사용할 ReentrantLock
     *
     * ConcurrentHashMap 을 사용하여 멀티 스레드 환경에서도 안전하게 Lock 객체를 조회/생성할 수 있도록 했다.
     */
    private final ConcurrentHashMap<Long, ReentrantLock> lockMap = new ConcurrentHashMap<>();

    public PointService(UserPointTable userPointTable, PointHistoryTable pointHistoryTable) {
        this.userPointTable = userPointTable;
        this.pointHistoryTable = pointHistoryTable;
    }

    /**
     * 특정 userId 에 대한 Lock 을 가져온다.
     * 없으면 새로 생성해서 맵에 넣고, 이미 있으면 기존 Lock 을 재사용한다.
     *
     * 이 메서드 덕분에 같은 userId 에 대한 요청끼리는 항상 같은 Lock 을 공유하게 된다.
     */
    private Lock getLock(long userId) {
        return lockMap.computeIfAbsent(userId, id -> new ReentrantLock());
    }

    /**
     * 특정 유저의 현재 포인트 조회 (읽기만 수행).
     *
     * 동시성 관점에서,
     * - 조회는 단순한 read 이므로 Lock 없이 호출한다.
     * - 엄격한 일관성이 필요하다면 charge/use 와 동일하게 Lock 을 걸어도 되지만,
     *   여기서는 "쓰기 작업의 원자성"에 집중했다.
     */
    public UserPoint getPoint(long userId) {
        return userPointTable.selectById(userId);
    }

    /**
     * 특정 유저의 포인트 히스토리(충전/사용 내역) 조회.
     * 마찬가지로 read-only 작업이므로 Lock 은 사용하지 않았다.
     */
    public List<PointHistory> getHistories(long userId) {
        return pointHistoryTable.selectAllByUserId(userId);
    }

    /**
     * 포인트 충전.
     *
     * 동시성 제어 포인트:
     *  - 같은 userId 에 대해 charge/use 가 동시에 호출되면,
     *    getLock(userId).lock() 으로 인해 한 번에 한 스레드만 임계 구역에 들어올 수 있다.
     *  - 임계 구역 내부에서는
     *      1) 현재 포인트 조회
     *      2) 새로운 포인트 계산
     *      3) insertOrUpdate
     *      4) 히스토리 기록
     *    이 전체가 하나의 "원자적 연산"처럼 동작한다.
     */
    public UserPoint charge(long userId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("충전 금액은 0보다 커야 합니다.");
        }

        Lock lock = getLock(userId);
        lock.lock(); // 임계 구역 진입
        try {
            UserPoint current = userPointTable.selectById(userId);
            long newAmount = current.point() + amount;

            UserPoint updated = userPointTable.insertOrUpdate(userId, newAmount);

            pointHistoryTable.insert(
                    userId,
                    amount,
                    TransactionType.CHARGE,
                    System.currentTimeMillis()
            );

            return updated;
        } finally {
            lock.unlock(); // 반드시 해제되도록 finally 에서 처리
        }
    }

    /**
     * 포인트 사용.
     *
     * 동시성 제어 포인트:
     *  - charge 와 마찬가지로 userId 기준 Lock 을 사용한다.
     *  - "잔고 부족 검사"와 "포인트 차감"이 같은 Lock 안에서 수행되므로,
     *    여러 스레드가 동시에 잔고를 검사하더라도 음수 잔액이 발생하지 않는다.
     */
    public UserPoint use(long userId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("사용 금액은 0보다 커야 합니다.");
        }

        Lock lock = getLock(userId);
        lock.lock();
        try {
            UserPoint current = userPointTable.selectById(userId);

            if (current.point() < amount) {
                // 과제 요구사항: 잔고 부족 시 실패
                throw new IllegalStateException("포인트가 부족합니다.");
            }

            long newAmount = current.point() - amount;

            UserPoint updated = userPointTable.insertOrUpdate(userId, newAmount);

            pointHistoryTable.insert(
                    userId,
                    amount,
                    TransactionType.USE,
                    System.currentTimeMillis()
            );

            return updated;
        } finally {
            lock.unlock();
        }
    }
}
