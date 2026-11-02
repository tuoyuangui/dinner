package com.guolongli.dinner.domain.model

/** 单条记录的同步状态。UI 必须如实展示，不得假装成功。 */
enum class SyncState {
    PENDING,
    SYNCING,
    SYNCED,
    FAILED,
    ;

    companion object {
        fun fromStorage(raw: String?): SyncState =
            entries.firstOrNull { it.name == raw } ?: PENDING
    }
}
