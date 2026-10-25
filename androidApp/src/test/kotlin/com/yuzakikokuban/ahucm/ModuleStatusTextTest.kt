package com.yuzakikokuban.ahucm

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleStatusTextTest {
    @Test fun canceledJobExplainsTheQueueInsteadOfClaimingTheModuleIsInactive() {
        assertEquals("上次小爱任务仍在取消或核验，请稍后重新开启自动管理", moduleEnableFailureText(IllegalStateException("canceled_write_unresolved")))
        assertEquals("上次小爱同步尚未结束，请稍后重试", moduleEnableFailureText(IllegalStateException("previous_revision_unresolved")))
        assertEquals("小爱自动管理未开启，请检查教务连接和小爱同步状态", moduleEnableFailureText(IllegalStateException("network_error")))
    }

    @Test fun backgroundFailuresAndDeferredUpdatesHaveAccurateMessages() {
        assertEquals("小爱后台通道尚未就绪，请检查模块状态或重启小爱进程", moduleStatusText("accepted", "verifying", "background_host_unavailable"))
        assertEquals("最新课表已排队，当前任务完成后立即后台更新", moduleStatusText("accepted", "queued", "queued_after_current"))
    }

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
        assertEquals("正在后台核验并修复小爱课表", moduleStatusText("pending", "verifying", ""))
    }

    @Test fun pendingBeforeHostTakeKeepsTheSetupHint() {
        assertEquals("小爱课表已排队，正在后台同步", moduleStatusText("pending", "accepted", ""))
    }

    @Test fun unknownStateKeepsTheSetupHint() {
        assertEquals("小爱课表已排队，正在后台同步", moduleStatusText(null, null, null))
    }
}
