package com.guolongli.dinner.feature.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guolongli.dinner.domain.model.MealRecord
import com.guolongli.dinner.domain.model.MealType
import com.guolongli.dinner.domain.model.MediaItem
import com.guolongli.dinner.domain.model.MediaType
import com.guolongli.dinner.domain.model.Member
import com.guolongli.dinner.domain.model.SyncState
import com.guolongli.dinner.domain.repository.MealRepository
import com.guolongli.dinner.domain.repository.PairingRepository
import com.guolongli.dinner.ui.navigation.DinnerRoute
import com.guolongli.dinner.ui.profile.LocalProfileStore
import com.guolongli.dinner.ui.util.DateText
import com.guolongli.dinner.ui.util.mealLabelOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 记录列表的筛选档位。
 *
 * 路由里传 [key] 而不是枚举名：URL 里出现中文或大写枚举名都不好维护。
 */
enum class RecordFilter(val key: String) {
    ALL("all"),
    MINE("mine"),
    SYNCED("synced"),
    DAY("day"),
    ;

    companion object {
        fun fromKey(raw: String?): RecordFilter = entries.firstOrNull { it.key == raw } ?: ALL
    }
}

/** 列表里的一行。 */
data class RecordRowUi(
    val id: String,
    val mealType: MealType,
    val mealLabel: String,
    val timeText: String,
    val dayLabel: String,
    val authorName: String,
    val isMine: Boolean,
    val note: String,
    val mediaCount: Int,
    val firstMediaPath: String?,
    val firstMediaThumb: String?,
    val firstMediaType: MediaType?,
    val syncState: SyncState,
    val marked: Boolean,
)

data class RecordListUiState(
    val isLoading: Boolean = true,
    val title: String = "记录",
    val subtitle: String = "",
    val emptyMessage: String = "这里还没有记录",
    val emptyHint: String? = null,
    val entries: List<RecordRowUi> = emptyList(),
)

/**
 * 记录明细列表。
 *
 * 一个页面服务三种入口：我的页面的三个统计、月历里的某一天、以及后续可能的
 * 「待上传」入口。筛选条件由路由参数给定，页面本身不知道入口是谁，
 * 只负责把符合条件的那批记录按时间倒序列出来。
 */
@HiltViewModel
class RecordListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mealRepository: MealRepository,
    private val pairingRepository: PairingRepository,
    private val profileStore: LocalProfileStore,
) : ViewModel() {

    private val filter = RecordFilter.fromKey(savedStateHandle[DinnerRoute.ARG_FILTER])
    private val dayKey: String? = savedStateHandle[DinnerRoute.ARG_DAY]

    private val _uiState = MutableStateFlow(RecordListUiState())
    val uiState: StateFlow<RecordListUiState> = _uiState.asStateFlow()

    init {
        observe()
    }

    private fun observe() {
        viewModelScope.launch {
            combine(
                mealRepository.observeAll(),
                pairingRepository.observe(),
                profileStore.observeMyName(),
            ) { records, group, myName ->
                Triple(records, group, myName)
            }.collect { (records, group, myName) ->
                val myId = group.myId
                val members = group.members.associateBy { it.id }
                val matched = records
                    .filter { matches(it, myId) }
                    .sortedByDescending { it.timestamp }
                // 行组装要 stat 本地文件，切到 IO 线程；主线程只负责发 UI state。
                withContext(Dispatchers.IO) {
                    _uiState.publish(matched, members, myId, myName)
                }
            }
        }
    }

    private fun MutableStateFlow<RecordListUiState>.publish(
        matched: List<MealRecord>,
        members: Map<String, Member>,
        myId: String,
        myName: String,
    ) {
        val rows = matched.map { record -> toRow(record, members, myId, myName) }
        // 用 update 而不是读改写：并发的多次收集不会互相覆盖中间的字段。
        update { state ->
            state.copy(
                isLoading = false,
                title = titleOf(),
                subtitle = subtitleOf(rows.size),
                emptyMessage = emptyMessageOf(),
                emptyHint = emptyHintOf(),
                entries = rows,
            )
        }
    }

    private fun matches(record: MealRecord, myId: String): Boolean = when (filter) {
        RecordFilter.ALL -> true
        RecordFilter.MINE -> record.createdBy == myId
        RecordFilter.SYNCED -> record.syncState == SyncState.SYNCED
        RecordFilter.DAY -> dayKey != null && DateText.dayKey(record.timestamp) == dayKey
    }

    private fun titleOf(): String = when (filter) {
        RecordFilter.ALL -> "总记录"
        RecordFilter.MINE -> "我记的"
        RecordFilter.SYNCED -> "已同步"
        RecordFilter.DAY -> {
            val millis = DateText.parseDayKey(dayKey)
            if (millis == null) "这一天的记录" else DateText.dayTitle(millis)
        }
    }

    private fun subtitleOf(count: Int): String = when {
        count == 0 -> "暂无记录"
        filter == RecordFilter.SYNCED -> "共 $count 条已在网盘上有副本"
        else -> "共 $count 条"
    }

    private fun emptyMessageOf(): String = when (filter) {
        RecordFilter.ALL -> "还没有任何记录"
        RecordFilter.MINE -> "你还没有记过"
        RecordFilter.SYNCED -> "还没有已同步的记录"
        RecordFilter.DAY -> "这一天没有记录"
    }

    private fun emptyHintOf(): String? = when (filter) {
        RecordFilter.ALL -> "回到时间线，点右下角「记一顿」开始记"
        RecordFilter.MINE -> "时间线里别人记的也会出现在这里"
        RecordFilter.SYNCED -> "有网络时切到时间线，会自动把待上传的记录推上去"
        RecordFilter.DAY -> "换个日期看看，或者回到日历选一天"
    }

    private fun toRow(
        record: MealRecord,
        members: Map<String, Member>,
        myId: String,
        myName: String,
    ): RecordRowUi {
        val mine = record.createdBy == myId
        val authorName = when {
            mine -> myName
            members[record.createdBy] != null -> members.getValue(record.createdBy).name.ifBlank { "成员" }
            else -> "成员"
        }
        val first: MediaItem? = record.media.firstOrNull()
        return RecordRowUi(
            id = record.id,
            mealType = record.mealType,
            mealLabel = mealLabelOf(record.mealType),
            timeText = DateText.timeText(record.timestamp),
            dayLabel = DateText.dateLabel(record.timestamp),
            authorName = authorName,
            isMine = mine,
            note = record.note,
            mediaCount = record.media.size,
            // 路径有值不代表文件还在：本地清理过 / 缓存被系统清掉的旧行要当成没图，
            // 交给卡片显示占位，而不是报一个加载失败。
            firstMediaPath = first?.let { MealRecord.existingFile(it.localPath)?.absolutePath },
            firstMediaThumb = first?.let { MealRecord.existingFile(it.thumbPath)?.absolutePath },
            firstMediaType = first?.mediaType,
            syncState = record.syncState,
            marked = record.marked,
        )
    }
}
