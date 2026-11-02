package com.guolongli.dinner.data.remote.datasource

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 建目录的路径链。
 *
 * 「远程没有则创建」全靠这里：链里必须包含 `/apps/dinner`，
 * 但绝不能包含 `/apps` —— 那是网盘的「我的应用数据」容器，
 * 应用无权创建，去试只会白挨一次失败。
 */
class DirectoryChainTest {

    @Test
    fun `从容器下一级开始逐级生成`() {
        assertEquals(
            listOf(
                "/apps/dinner",
                "/apps/dinner/meals",
                "/apps/dinner/meals/2026",
                "/apps/dinner/meals/2026/10",
            ),
            BaiduFileDataSource.directoryChain("/apps/dinner/meals/2026/10"),
        )
    }

    @Test
    fun `根目录本身也在链里但容器不在`() {
        val chain = BaiduFileDataSource.directoryChain("/apps/dinner")
        assertEquals(listOf("/apps/dinner"), chain)
        assertEquals(false, chain.contains("/apps"))
    }

    @Test
    fun `忽略结尾斜杠与重复斜杠`() {
        assertEquals(
            listOf("/apps/dinner"),
            BaiduFileDataSource.directoryChain("/apps//dinner/"),
        )
    }
}
