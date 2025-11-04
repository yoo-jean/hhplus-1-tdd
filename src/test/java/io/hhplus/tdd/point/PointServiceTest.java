package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PointService 단위 테스트.
 *
 * <p>테스트 대상</p>
 * <ul>
 *     <li>포인트 조회</li>
 *     <li>포인트 내역 조회</li>
 *     <li>포인트 충전</li>
 *     <li>포인트 사용 + 잔고 부족 예외</li>
 *     <li>0 이하 금액에 대한 검증</li>
 * </ul>
 *
 * 각 테스트 메서드 위에 "이 테스트를 왜 작성했는지" 주석을 작성하였다.
 */
class PointServiceTest {

    private PointService pointService;
    private UserPointTable userPointTable;
    private PointHistoryTable pointHistoryTable;

    /**
     * @BeforeEach
     * - 각 테스트 메서드가 실행되기 전에 새 UserPointTable, PointHistoryTable, PointService 를 생성한다.
     * - 테스트 간 상태 공유를 막기 위해, 매번 깨끗한(in-memory) 테이블을 사용하는 것이 목적이다.
     */
    @BeforeEach
    void setUp() {
        userPointTable = new UserPointTable();
        pointHistoryTable = new PointHistoryTable();
        pointService = new PointService(userPointTable, pointHistoryTable);
    }

    // ----------------------------------------------------------------------
    // 포인트 조회 관련 테스트
    // ----------------------------------------------------------------------

    /**
     * [테스트 작성 이유]
     * - 아직 아무 데이터도 없는 상태에서 유저 포인트를 조회했을 때,
     *   UserPointTable.selectById 가 "기본값 0원"을 가진 UserPoint 를 반환하는지 검증하기 위해 작성.
     * - 과제에서 초기 잔액에 대한 명시적 조건은 없지만,
     *   Table 구현체에서 empty 유저에 대해 0 포인트를 주도록 되어 있으므로 이 동작을 보장해야 한다.
     */
    @Test
    @DisplayName("새 유저의 포인트 조회 시 기본 잔액은 0원이다")
    void getPoint_defaultIsZero() {
        // given
        long userId = 1L;

        // when
        UserPoint point = pointService.getPoint(userId);

        // then
        assertEquals(0L, point.point());
    }

    // ----------------------------------------------------------------------
    // 충전 관련 테스트
    // ----------------------------------------------------------------------

    /**
     * [테스트 작성 이유]
     * - 포인트 충전 기능의 핵심 요구사항은
     *   1) 잔액이 충전 금액만큼 증가해야 하고,
     *   2) CHARGE 타입의 히스토리 기록이 남아야 한다는 점이다.
     * - 이 테스트는 위 두 가지를 동시에 검증하여, 충전 기능이 올바르게 구현되었는지 확인한다.
     */
    @Test
    @DisplayName("포인트를 충전하면 잔액이 증가하고 CHARGE 내역이 기록된다")
    void charge_increaseBalance_andInsertHistory() {
        // given
        long userId = 1L;
        long chargeAmount = 1000L;

        // when
        pointService.charge(userId, chargeAmount);

        // then - 잔액 검증
        UserPoint point = pointService.getPoint(userId);
        assertEquals(1000L, point.point());

        // then - 히스토리 검증
        List<PointHistory> histories = pointService.getHistories(userId);
        assertEquals(1, histories.size());
        PointHistory history = histories.get(0);

        assertEquals(userId, history.userId());
        assertEquals(chargeAmount, history.amount());
        assertEquals(TransactionType.CHARGE, history.type());
    }

    /**
     * [테스트 작성 이유]
     * - 잘못된 입력(0원 또는 음수)에 대한 방어 로직이 꼭 필요하다.
     * - 금액 검증을 하지 않으면, API 사용자가 실수로 0원/음수 충전 요청을 보내도
     *   정상처리될 수 있기 때문에, IllegalArgumentException 이 발생하는지 확인한다.
     */
    @Test
    @DisplayName("0 이하 금액으로 충전을 요청하면 예외가 발생한다")
    void charge_nonPositiveAmount_shouldThrow() {
        // given
        long userId = 1L;

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> pointService.charge(userId, 0L));

        assertThrows(IllegalArgumentException.class,
                () -> pointService.charge(userId, -100L));
    }

    // ----------------------------------------------------------------------
    // 사용 관련 테스트
    // ----------------------------------------------------------------------

    /**
     * [테스트 작성 이유]
     * - 포인트 사용 기능의 핵심 요구사항은
     *   1) 잔액이 사용 금액만큼 감소해야 하고,
     *   2) USE 타입의 히스토리 기록이 남아야 한다는 점이다.
     * - 이 테스트는 "충전 후 사용"이라는 실제 시나리오에 맞게 작성하여,
     *   양쪽 기능이 함께 잘 동작하는지 검증한다.
     */
    @Test
    @DisplayName("포인트를 사용하면 잔액이 감소하고 USE 내역이 기록된다")
    void use_decreaseBalance_andInsertHistory() {
        // given
        long userId = 1L;
        long chargeAmount = 1000L;
        long useAmount = 400L;

        pointService.charge(userId, chargeAmount);

        // when
        pointService.use(userId, useAmount);

        // then - 잔액 검증
        UserPoint point = pointService.getPoint(userId);
        assertEquals(600L, point.point());

        // then - 히스토리 검증 (CHARGE 1건 + USE 1건)
        List<PointHistory> histories = pointService.getHistories(userId);
        assertEquals(2, histories.size());

        PointHistory useHistory = histories.get(1); // 두 번째가 USE 라고 가정
        assertEquals(userId, useHistory.userId());
        assertEquals(useAmount, useHistory.amount());
        assertEquals(TransactionType.USE, useHistory.type());
    }

    /**
     * [테스트 작성 이유]
     * - 과제 명시 요구사항: "잔고가 부족할 경우, 포인트 사용은 실패해야 한다."
     * - 이 요구사항을 보장하기 위해, 현재 잔액보다 큰 금액을 사용할 때
     *   IllegalStateException 이 발생하는지 검증한다.
     */
    @Test
    @DisplayName("잔액보다 큰 금액을 사용하면 잔고 부족 예외가 발생한다")
    void use_overBalance_shouldThrow() {
        // given
        long userId = 1L;

        // 잔액 500원 충전
        pointService.charge(userId, 500L);

        // when & then - 1000원을 사용하려고 하면 예외
        assertThrows(IllegalStateException.class,
                () -> pointService.use(userId, 1000L));
    }

    /**
     * [테스트 작성 이유]
     * - 사용 기능 역시 0원 또는 음수 요청이 들어올 수 있으므로, 방어 로직이 필요하다.
     * - 이 테스트는 0 이하 금액으로 사용할 때 IllegalArgumentException 이 발생하는지 확인한다.
     */
    @Test
    @DisplayName("0 이하 금액으로 사용을 요청하면 예외가 발생한다")
    void use_nonPositiveAmount_shouldThrow() {
        // given
        long userId = 1L;
        pointService.charge(userId, 1000L); // 충분히 충전

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> pointService.use(userId, 0L));

        assertThrows(IllegalArgumentException.class,
                () -> pointService.use(userId, -100L));
    }
}
