package com.guolongli.dinner.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.guolongli.dinner.data.session.AdminGate
import androidx.lifecycle.viewModelScope
import com.guolongli.dinner.domain.model.MediaType
import com.guolongli.dinner.domain.model.MealRecord
import com.guolongli.dinner.domain.model.MealType
import com.guolongli.dinner.domain.model.SyncState
import com.guolongli.dinner.domain.repository.AvatarRepository
import com.guolongli.dinner.domain.repository.MealRepository
import com.guolongli.dinner.domain.repository.MediaCache
import com.guolongli.dinner.domain.repository.PairingRepository
import com.guolongli.dinner.ui.navigation.DinnerRoute
import com.guolongli.dinner.ui.util.DateText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** 详情页的一件媒体。图片与视频都走同一个结构，视频只看封面帧。 */
data class DetailMediaUi(
    val id: String,
    val localPath: String?,
    val thumbPath: String?,
    val remotePath: String?,
    val mediaType: MediaType,
    val durationText: String?,
    /** 正在从网盘取这一张。 */
    val isFetching: Boolean = false,
    /** 取失败，界面上给「点一下重试」。 */
    val fetchFailed: Boolean = false,
)

/** 详情页不可变状态。只读展示，编辑走单独的页面。 */
data class RecordDetailUiState(
    val isLoading: Boolean = true,
    val found: Boolean = true,
    /** 当前记录 id。点「编辑」时要把它带到编辑页，不能靠 UI 层自己猜。 */
    val recordId: String = "",
    val typeLabel: String = "",
    val dateLabel: String = "",
    val timeLabel: String = "",
    val note: String = "",
    val rating: Int? = null,
    val marked: Boolean = false,
    /** 标签列表，随记录上云。 */
    val tags: List<String> = emptyList(),
    /** 记录位置文案，没有定位为 null。 */
    val locationText: String? = null,
    val authorName: String = "",
    val authorAvatar: File? = null,
    val isMine: Boolean = false,
    val syncState: SyncState = SyncState.SYNCED,
    val media: List<DetailMediaUi> = emptyList(),
    /** 能否修改（本人或管理员）。不能改就不显示编辑入口，避免点了才发现没权限。 */
    val canModify: Boolean = false,
    val isDeleted: Boolean = false,
)

/**
 * 记录详情。
 *
 * 只读：这里不做任何写入。时间线点卡片、日历点某天再点某条，都先进来看，
 * 要改内容必须显式点右上角「编辑」。
 */
@HiltViewModel
class RecordDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mealRepository: MealRepository,
    private val mediaCache: MediaCache,
    pairingRepository: PairingRepository,
    avatarRepository: AvatarRepository,
    private val adminGate: AdminGate,
) : ViewModel() {

    private val recordId: String =
        savedStateHandle.get<String>(DinnerRoute.ARG_RECORD_ID).orEmpty()

    private val _uiState = MutableStateFlow(RecordDetailUiState())
    val uiState: StateFlow<RecordDetailUiState> = _uiState.asStateFlow()

    /** 正在从网盘取的媒体 id。 */
    private val fetching = mutableSetOf<String>()

    /** 取失败的媒体 id：用户点一下才会重试，避免在详情页里反复打接口。 */
    private val failed = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            combine(
                mealRepository.observeById(recordId),
                pairingRepository.observe(),
                avatarRepository.observeAvatars(),
            ) { record, group, avatars ->
                if (record == null) {
                    RecordDetailUiState(isLoading = false, found = false)
                } else {
                    // toUiState 里要 stat 本地媒体文件（existingFile），属于磁盘 I/O，
                    // 切到 IO 线程再组装，别让它落在主线程上。
                    withContext(Dispatchers.IO) {
                        record.toUiState(
                        authorName = group.nameOf(record.createdBy),
                        authorAvatar = avatars[record.createdBy],
                        isMine = record.createdBy == group.myId,
                        // 别人的内容还要求本次运行已验过管理员口令：
                        // 只判 amIAdmin 会让未解锁的管理员也能进编辑页，口令等于白设。
                        canModify = adminGate.canModifyContent(
                            authorId = record.createdBy,
                            myId = group.myId,
                            amIAdmin = group.amIAdmin,
                        ),
                        )
                    }
                }
            }.collect { state -> _uiState.value = state }
        }
        viewModelScope.launch { fetchRemoteMedia() }
    }

    /**
     * 把还没有本机副本的媒体逐张取回来。
     *
     * 以前详情页只读 Room 里的 localPath：从云端恢复的记录这个字段是空的，
     * 于是点开一条老记录，看到的是一排「这张图还没从云端取回」——
     * 明明网盘上就有。这里按顺序一张张取，取到就回填 Room。
     */
    private suspend fun fetchRemoteMedia() {
        val ready = uiState.first { !it.isLoading }
        if (!ready.found) return
        for (media in ready.media) {
            val remote = media.remotePath ?: continue
            if (media.localPath != null) continue
            fetchOne(media.id, remote)
        }
    }

    /** 用户点了那张没取回来的图：清掉失败标记再试一次。 */
    fun retryMedia(mediaId: String) {
        val media = _uiState.value.media.firstOrNull { it.id == mediaId } ?: return
        val remote = media.remotePath ?: return
        failed.remove(mediaId)
        viewModelScope.launch { fetchOne(mediaId, remote) }
    }

    private suspend fun fetchOne(mediaId: String, remotePath: String) {
        if (!fetching.add(mediaId)) return
        pushFetchState()
        val result = mediaCache.resolve(remotePath)
        fetching.remove(mediaId)
        result
            .onSuccess { file -> bindLocalMedia(mediaId, file.absolutePath) }
            .onFailure { failed.add(mediaId) }
        pushFetchState()
    }

    /** 回填本地路径。缓存属于设备私有数据，不改同步状态。 */
    private suspend fun bindLocalMedia(mediaId: String, localPath: String) {
        val current = runCatching { mealRepository.getById(recordId) }.getOrNull() ?: return
        runCatching {
            mealRepository.updateMedia(
                recordId,
                current.media.map { if (it.id == mediaId) it.copy(localPath = localPath) else it },
            )
        }
    }

    private fun pushFetchState() {
        _uiState.value = _uiState.value.copy(
            media = _uiState.value.media.map {
                it.copy(isFetching = it.id in fetching, fetchFailed = it.id in failed)
            },
        )
    }

    private fun MealRecord.toUiState(
        authorName: String,
        authorAvatar: File?,
        isMine: Boolean,
        canModify: Boolean,
    ): RecordDetailUiState = RecordDetailUiState(
        isLoading = false,
        found = true,
        recordId = id,
        typeLabel = typeLabelOf(mealType),
        dateLabel = DateText.dateLabel(timestamp),
        timeLabel = DateText.timeText(timestamp),
        note = note,
        rating = rating,
        marked = marked,
        tags = tags,
        locationText = location?.displayText,
        authorName = authorName,
        authorAvatar = authorAvatar,
        isMine = isMine,
        syncState = syncState,
        media = media.map { item ->
            DetailMediaUi(
                id = item.id,
                localPath = MealRecord.existingFile(item.localPath)?.absolutePath,
                thumbPath = MealRecord.existingFile(item.thumbPath)?.absolutePath,
                remotePath = item.remotePath,
                mediaType = item.mediaType,
                durationText = item.durationMs?.let(::durationTextOf),
                isFetching = item.id in fetching,
                fetchFailed = item.id in failed,
            )
        },
        canModify = canModify,
        isDeleted = isDeleted,
    )

    private fun typeLabelOf(type: MealType): String =
        com.guolongli.dinner.ui.util.mealLabelOf(type)

    private fun durationTextOf(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
}
