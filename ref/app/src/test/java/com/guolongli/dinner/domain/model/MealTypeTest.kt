package com.guolongli.dinner.domain.model

import com.guolongli.dinner.ui.util.mealLabelOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记录类型的持久化与文案。
 *
 * 类型以 name() 落库并写进 records.json，因此「顺序不变、只能追加」
 * 是数据兼容的底线；改名或插入会让两端对同一条记录理解不一致。
 */
class MealTypeTest {

    @Test
    fun `餐别与生活场景都在枚举里`() {
        assertEquals(9, MealType.entries.size)
        assertEquals(
            listOf("早餐", "午餐", "晚餐", "加餐", "风景", "学习", "运动", "创作", "其他"),
            MealType.entries.map { mealLabelOf(it) },
        )
    }

    @Test
    fun `前四项是餐别，其余不是`() {
        MealType.entries.forEachIndexed { index, type ->
            assertEquals("index=$index type=$type", index < 4, type.isMeal)
        }
    }

    @Test
    fun `存储名可以是往返的`() {
        MealType.entries.forEach { type ->
            assertEquals(type, MealType.fromStorage(type.name))
        }
    }

    @Test
    fun `新增类型不影响旧数据的解析`() {
        assertEquals(MealType.BREAKFAST, MealType.fromStorage("BREAKFAST"))
        assertEquals(MealType.SNACK, MealType.fromStorage("SNACK"))
    }

    @Test
    fun `未知或缺失的存储值回退到默认类型`() {
        assertEquals(MealType.DINNER, MealType.fromStorage(null))
        assertEquals(MealType.DINNER, MealType.fromStorage(""))
        assertEquals(MealType.DINNER, MealType.fromStorage("NOODLES"))
    }

    @Test
    fun `生活场景不是餐别`() {
        listOf(MealType.SCENERY, MealType.STUDY, MealType.SPORT, MealType.CREATION, MealType.OTHER)
            .forEach { assertFalse(it.isMeal) }
        assertTrue(MealType.LUNCH.isMeal)
    }
}
