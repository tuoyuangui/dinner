package com.guolongli.dinner.data

/**
 * 删除的三种范围。名字与界面上的三档一一对应，不要在这里合并——
 * 「本机不想看」和「让所有人都删掉」是两件事，混成一个按钮就会误删别人的内容。
 */
enum class DeleteScope(val label: String, val explanation: String) {
    LOCAL("只删本地", "这台设备不再显示，云端与别人都不受影响"),
    CLOUD("只删云端", "删掉网盘上的文件，别人下次同步就看不到了"),
    BOTH("本地和云端都删", "两边一起删，并把删除意图同步给所有人"),
}

/** 删除结果。失败要能说清楚是「没权限」还是「记录没了」，界面才好给提示 */
enum class DeleteOutcome { DONE, NOT_FOUND, NOT_ALLOWED, CLOUD_NOT_ALLOWED }
