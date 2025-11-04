package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 포인트 비즈니스 로직을 담당하는 서비스 클래스.
 *
 * 요구사항
 * 
 *     특정 유저의 현재 포인트 조회
 *     특정 유저의 포인트 충전 내역 / 사용 내역 조회
 *     포인트 충전
 *     포인트 사용 (잔고 부족 시 실패)
 * 
 *
 * 주의사항
 * 
 *     실제 DB 대신 /database 패키지의 UserPointTable, PointHistoryTable 을 사용한다.
 *     /database 패키지의 구현체는 수정하지 않고, 제공된 공개 메서드만 사용해야 한다.
 *     분산 환경(동시성)은 고려하지 않는다는 과제 조건에 따라, 단순 로직으로 구현한다.
 * 
 */
@Service
public class PointService {

    private final UserPointTable userPointTable;
    private final PointHistoryTable pointHistoryTable;

    /**
     * UserPointTable, PointHistoryTable 을 주입받는 생성자.
     * 이 두 클래스는 실제 DB 대신 메모리(Map)를 사용하는 "가짜 테이블" 역할을 한다.
     */
    public PointService(UserPointTable userPointTable, PointHistoryTable pointHistoryTable) {
        this.userPointTable = userPointTable;
        this.pointHistoryTable = pointHistoryTable;
    }

    /**
     * 특정 유저의 현재 포인트를 조회한다.
     *
     * 로직
     * <ol>
     *     UserPointTable 에서 userId 로 조회한다.
     *     해당 유저가 없으면 UserPointTable 이 빈 UserPoint(0원)를 반환해 준다.
     * </ol>
     *
     * @param userId 조회할 유저 ID
     * @return 유저의 현재 포인트 정보
     */
    public UserPoint getPoint(long userId) {
        return userPointTable.selectById(userId);
    }

    /**
     * 특정 유저의 포인트 히스토리(충전/사용 내역)를 조회한다.
     *
     * 로직
     * <ol>
     *     PointHistoryTable 에서 userId 에 해당하는 전체 내역 리스트를 조회한다.
     *     충전/사용 모두 포함하여 최신 순 또는 저장된 순으로 반환한다. (정렬 규칙은 Table 구현체에 따름)
     * </ol>
     *
     * @param userId 조회할 유저 ID
     * @return 포인트 히스토리 리스트
     */
    public List<PointHistory> getHistories(long userId) {
        return pointHistoryTable.selectAllByUserId(userId);
    }

    /**
     * 특정 유저의 포인트를 충전한다.
     *
     * 로직
     * <ol>
     *     충전 금액이 0 이하이면 잘못된 요청으로 보고 IllegalArgumentException 을 던진다.
     *     UserPointTable 에서 현재 포인트를 조회한다.
     *     기존 포인트 + 충전 금액으로 새로운 잔액을 계산한다.
     *     UserPointTable.insertOrUpdate 를 호출하여 잔액을 갱신한다.
     *     PointHistoryTable.insert 를 호출하여 CHARGE 타입 내역을 기록한다.
     * </ol>
     *
     * @param userId 충전할 유저 ID
     * @param amount 충전 금액 (0보다 커야 함)
     * @return 충전 후 갱신된 UserPoint
     */
    public UserPoint charge(long userId, long amount) {
        // 1. 유효성 검증: 0 이하 금액은 잘못된 요청
        if (amount <= 0) {
            throw new IllegalArgumentException("충전 금액은 0보다 커야 합니다.");
        }

        // 2. 현재 잔액 조회
        UserPoint current = userPointTable.selectById(userId);
        long newAmount = current.point() + amount; // 기존 포인트 + 충전 금액

        // 3. 포인트 테이블 업데이트
        UserPoint updated = userPointTable.insertOrUpdate(userId, newAmount);

        // 4. 히스토리 테이블에 CHARGE 내역 기록
        pointHistoryTable.insert(
                userId,
                amount,
                TransactionType.CHARGE,
                System.currentTimeMillis()
        );

        return updated;
    }

    /**
     * 특정 유저의 포인트를 사용한다.
     *
     * 로직
     * <ol>
     *     사용 금액이 0 이하이면 IllegalArgumentException 을 던진다.
     *     UserPointTable 에서 현재 포인트를 조회한다.
     *     현재 포인트가 사용 금액보다 적으면 잔고 부족으로 보고 IllegalStateException 을 던진다.
     *     현재 포인트 - 사용 금액으로 새로운 잔액을 계산한다.
     *     UserPointTable.insertOrUpdate 를 호출하여 잔액을 갱신한다.
     *     PointHistoryTable.insert 를 호출하여 USE 타입 내역을 기록한다.
     * </ol>
     *
     * @param userId 포인트를 사용할 유저 ID
     * @param amount 사용 금액 (0보다 커야 함)
     * @return 사용 후 갱신된 UserPoint
     */
    public UserPoint use(long userId, long amount) {
        // 1. 유효성 검증
        if (amount <= 0) {
            throw new IllegalArgumentException("사용 금액은 0보다 커야 합니다.");
        }

        // 2. 현재 잔액 조회
        UserPoint current = userPointTable.selectById(userId);

        // 3. 잔고 부족 체크
        if (current.point() < amount) {
            // 과제 요구사항: 잔고 부족 시 포인트 사용은 실패해야 한다.
            throw new IllegalStateException("포인트가 부족합니다.");
        }

        // 4. 포인트 차감
        long newAmount = current.point() - amount;

        // 5. 포인트 테이블 업데이트
        UserPoint updated = userPointTable.insertOrUpdate(userId, newAmount);

        // 6. 히스토리 테이블에 USE 내역 기록
        pointHistoryTable.insert(
                userId,
                amount,
                TransactionType.USE,
                System.currentTimeMillis()
        );

        return updated;
    }
}
