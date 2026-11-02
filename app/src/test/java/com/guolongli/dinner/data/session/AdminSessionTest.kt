package com.guolongli.dinner.data.session

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.guolongli.dinner.data.ProfileStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 管理员权限的唯一判定处。
 *
 * 这里测试的是「门槛」，不是「按钮」：界面把入口藏起来只是方便，
 * 真正决定能不能改删别人内容的是这几个方法，而仓储在写云端之前会调它们。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class AdminSessionTest {

    private lateinit var profile: ProfileStore
    private lateinit var session: AdminSession

    @Before
    fun setUp() {
        profile = ProfileStore(ApplicationProvider.getApplicationContext())
        session = AdminSession(profile)
    }

    @Test
    fun `默认这台设备就是管理员，但没解锁不算解锁`() {
        assertTrue(session.iAmAdmin)
        assertFalse("没输口令之前不能算解锁", session.isUnlocked)
    }

    @Test
    fun `自己的记录随时能改，别人的不行`() {
        assertTrue(session.canManage(profile.deviceId))
        assertFalse(session.canManage("another-device"))
    }

    @Test
    fun `解锁之后才能改别人的记录`() {
        assertTrue(session.unlock(profile.adminCode))

        assertTrue(session.isUnlocked)
        assertTrue(session.canManage("another-device"))
    }

    @Test
    fun `口令不对不解锁，空口令也不解锁`() {
        assertFalse(session.unlock("wrong-code"))
        assertFalse(session.unlock("   "))
        assertFalse(session.isUnlocked)
    }

    @Test
    fun `锁回去之后权限立刻失效`() {
        session.unlock(profile.adminCode)
        session.lock()

        assertFalse(session.isUnlocked)
        assertFalse(session.canManage("another-device"))
    }

    @Test
    fun `群级操作必须解锁，跟是不是管理员设备无关`() {
        assertFalse("光是管理员设备不够，还得输口令", session.requireAdmin())

        session.unlock(profile.adminCode)
        assertTrue(session.requireAdmin())
    }

    @Test
    fun `转让管理权之后本机不再是管理员`() {
        profile.adminId = "another-device"

        assertFalse(session.iAmAdmin)
        assertTrue(session.isAdmin("another-device"))
        assertFalse(session.isAdmin(profile.deviceId))
    }

    @Test
    fun `换过管理员口令之后旧口令立刻失效`() {
        session.unlock(profile.adminCode)
        session.lock()
        profile.adminCode = "new-secret"

        assertFalse(session.unlock("dinner"))
        assertTrue(session.unlock("new-secret"))
    }
}
