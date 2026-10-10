package com.xiaoyiyuan.study.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class GoalStateTest {

    @ParameterizedTest
    @EnumSource(GoalStatus.class)
    void labelRoundTrips(GoalStatus s) {
        assertEquals(s, GoalStatus.fromLabel(s.label()));
    }

    @Test
    void labelsMatchSpec() {
        assertEquals("进行中", GoalStatus.ACTIVE.label());
        assertEquals("暂缓", GoalStatus.DEFERRED.label());
        assertEquals("已取消", GoalStatus.CANCELLED.label());
        assertEquals("已完成", GoalStatus.DONE.label());
    }

    @Test
    void unknownLabel_throwsWithOriginalText() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> GoalStatus.fromLabel("暂停中"));
        assertTrue(ex.getMessage().contains("暂停中"), "异常必须带原文，方便定位");
    }

    @Test
    void deferredAndCancelled_areNotFailures() {
        // 四态一律中性：没有任何状态被建模成「失败」
        for (GoalStatus s : GoalStatus.values()) {
            assertFalse(s.name().toUpperCase().contains("FAIL"));
            assertFalse(s.label().contains("失败"));
            assertFalse(s.label().contains("放弃"));
        }
    }
}
