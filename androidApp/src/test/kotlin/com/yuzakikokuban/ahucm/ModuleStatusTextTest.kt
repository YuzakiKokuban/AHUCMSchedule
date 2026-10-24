package com.yuzakikokuban.ahucm

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleStatusTextTest {
    @Test fun verifiedStateIsExplicit() {
        assertEquals("小爱课表已更新并核验", moduleStatusText("applied", "verified", "server_readback_verified"))
    }

    @Test fun silentCourseDropAsksForAnotherAttempt() {
        assertEquals("小爱未保存课程，请重新点击「开启小爱课表自动管理」", moduleStatusText("failed", "accepted", "course_write_not_persisted"))
    }

    @Test fun readbackMismatchKeepsASpecificHint() {
        assertEquals("小爱读回内容不一致，请重新点击「开启小爱课表自动管理」", moduleStatusText("failed", "accepted", "server_readback_mismatch"))
    }

    @Test fun occupiedManagedNameExplainsHowToRecover() {
        assertEquals("小爱中已存在同名课表，请在小爱课程表中删除后重试", moduleStatusText("failed", "accepted", "managed_table_identity_changed"))
    }

    @Test fun unknownFailureKeepsTheExistingMessage() {
        assertEquals("小爱更新失败，请检查小爱账号与模块状态", moduleStatusText("failed", "accepted", "host_failure"))
    }

    @Test fun pendingVerificationNeverTellsTheUserToStop() {
        assertEquals("小爱课表正在核验或修复；请保持小爱课程表打开，或重新点击「开启小爱课表自动管理」", moduleStatusText("pending", "verifying", ""))
    }

    @Test fun pendingBeforeHostTakeKeepsTheSetupHint() {
        assertEquals("等待小爱助手完成更新；请打开小爱课程表并保持前台", moduleStatusText("pending", "accepted", ""))
    }

    @Test fun unknownStateKeepsTheSetupHint() {
        assertEquals("等待小爱助手完成更新；请打开小爱课程表并保持前台", moduleStatusText(null, null, null))
    }
}
