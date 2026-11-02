package com.guolongli.dinner.feature.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guolongli.dinner.data.mapper.normalizeTags
import com.guolongli.dinner.data.session.AdminGate
import com.guolongli.dinner.domain.location.LocationProvider
import com.guolongli.dinner.domain.media.MediaImporter
import com.guolongli.dinner.domain.media.MediaSource
import com.guolongli.dinner.domain.model.GeoLocation
import com.guolongli.dinner.domain.model.MealRecord
import com.guolongli.dinner.domain.model.MealType
import com.guolongli.dinner.domain.model.MediaItem
import com.guolongli.dinner.domain.model.MediaType
import com.guolongli.dinner.domain.model.SyncState
import com.guolongli.dinner.domain.repository.GroupRepository
import com.guolongli.dinner.domain.repository.MealRepository
import com.guolongli.dinner.domain.repository.PairingRepository
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import com.guolongli.dinner.domain.repository.SyncRepository
import com.guolongli.dinner.ui.navigation.DinnerRoute
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.util.DateText
import com.guolongli.dinner.ui.util.durationTextOf
import com.guolongli.dinner.ui.util.runCatchingCancellable
import com.guolongli.dinner.ui.util.toUserText
import com.guolongli.dinner.core.RecordLimits
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 保存的各个阶段，UI 需如实展示，绝不假装成功。 */
enum class SavePhase { IDLE, IMPORTING, SAVING, UPLOADING, DONE, PARTIAL, FAILED }

/**
 * 编辑页里的一段媒体。
 *
 * 与领域模型 [MediaItem] 的区别：这里允许 [localPath] 必有值（新导入的一定有本地文件），
 * 并保留 [remotePath]，编辑已有记录时不会因为「重新保存」把已上传信息丢掉。
 */
data class EditingMedia(
    val id: String,
    val localPath: String,
    val thumbPath: String?,
    val mediaType: MediaType,
    val durationMs: Long?,
    val remotePath: String? = null,
    val md5: String? = null,
) {
    /** 视频时长文案，非视频为 null。 */
    val durationText: String? get() = if (mediaType == MediaType.VIDEO) durationTextOf(durationMs) else null
}

/**
 * 编辑页不可变 UI 状态。
 */
data class EditUiState(
    val recordId: String? = null,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val mealType: MealType = MealType.DEFAULT,
    val dateMillis: Long = System.currentTimeMillis(),
    val timeSourceMillis: Long = System.currentTimeMillis(),
    val note: String = "",
    val noteLength: Int = 0,
    val media: List<EditingMedia> = emptyList(),
    val rating: Int? = null,
    val marked: Boolean = false,
    /** 当前「压缩后存储」设置，仅用于在媒体区如实说明这一批会怎么存。 */
    val compressMedia: Boolean = StorageConfigRepository.DEFAULT_COMPRESS_MEDIA,
    /**
     * 记录作者。空表示「还不知道」，保存时再取当前身份。
     *
     * 绝不能用占位字符串（早先是 "me"）：那个值会跟着记录进库、上云，
     * 然后所有设备都查不到这个成员，作者名只能显示成「成员」。
     */
    val authorId: String = "",
    /** 记录作者昵称，用于「这是谁记的」提示。 */
    val authorName: String = "",
    /**
     * 是否有权修改这条。
     *
     * 自己的随便改；管理员可以改所有人的；其他人的记录只读。
     */
    val canEdit: Boolean = true,
    val savePhase: SavePhase = SavePhase.IDLE,
    val errorMessage: String? = null,
    /** 记录时所在位置。null 表示还没取 / 用户关掉了定位。 */
    val location: GeoLocation? = null,
    /** 是否正在取位置，按钮据此转圈。 */
    val isLocating: Boolean = false,
    /** 用户是否显式关掉了位置记录。关掉就不再自动取。 */
    val locationDisabled: Boolean = false,
    /** 标签列表，随记录上云，其他成员可见。 */
    val tags: List<String> = emptyList(),
    /** 标签输入框当前内容。 */
    val tagInput: String = "",
) {
    /** 是否有可保存内容：备注非空或已选媒体。由字段派生，避免多处手算不一致。 */
    val canSave: Boolean get() = note.isNotBlank() || media.isNotEmpty()

    /** 还能再加几段媒体。 */
    val mediaLimit: Int get() = MediaItem.MAX_ITEMS
    val canAddMedia: Boolean get() = media.size < MediaItem.MAX_ITEMS

    /** 还能再加几个标签。 */
    val canAddTag: Boolean get() = tags.size < RecordLimits.MAX_TAGS

    /** 是否混排了视频——提示文案要用。 */
    val hasVideo: Boolean get() = media.any { it.mediaType == MediaType.VIDEO }
}

@HiltViewModel
class EditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mealRepository: MealRepository,
    private val syncRepository: SyncRepository,
    private val pairingRepository: PairingRepository,
    private val mediaImporter: MediaImporter,
    private val storageConfig: StorageConfigRepository,
    private val locationProvider: LocationProvider,
    private val groupRepository: GroupRepository,
    private val adminGate: AdminGate,
) : ViewModel() {

    private val recordId: String? = savedStateHandle[DinnerRoute.ARG_RECORD_ID]

    private val _uiState = MutableStateFlow(
        EditUiState(
            recordId = recordId,
            isLoading = recordId != null,
        )
    )
    val uiState: StateFlow<EditUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            storageConfig.observeCompressMedia().collect { enabled ->
                _uiState.update { it.copy(compressMedia = enabled) }
            }
        }
        if (recordId != null) {
            viewModelScope.launch { loadExisting(recordId) }
        } else {
            val now = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    dateMillis = now,
                    timeSourceMillis = now,
                )
            }
            // 新建记录的作者必须是本机当前身份。
            //
            // 早先这里留着 UI 状态的占位值 "me" 没换，保存时直接写进 created_by。
            // 后果是每一条新记录的作者都不在任何成员名单里：时间线上查不到，
            // 一律退化成「成员」，头像也永远是空的。
            viewModelScope.launch {
                val me = runCatchingCancellable { pairingRepository.current() }
                    .getOrNull()?.myId.orEmpty()
                if (me.isNotBlank()) {
                    _uiState.update { it.copy(authorId = me) }
                }
            }
            // 顺便取一次当前位置：新记录默认带上「在哪吃」。
            refreshLocation()
        }
    }

    /**
     * 取当前位置。
     *
     * 只在用户点按钮或新建记录时取一次，不订阅位置更新。
     * 没权限 / 定位关闭 / 取不到都只影响这一条记录的位置，保存照样成功。
     */
    fun refreshLocation() {
        val current = _uiState.value
        if (current.savePhase != SavePhase.IDLE || current.isLocating) return
        if (current.locationDisabled) return
        _uiState.update { it.copy(isLocating = true) }
        viewModelScope.launch {
            locationProvider.current()
                .onSuccess { location ->
                    _uiState.update { it.copy(isLocating = false, location = location) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLocating = false,
                            errorMessage = error.toUserText(),
                        )
                    }
                }
        }
    }

    /** 关掉位置记录。之后不再自动取，已取到的也清掉。 */
    fun disableLocation() {
        _uiState.update {
            it.copy(locationDisabled = true, location = null, isLocating = false)
        }
    }

    /** 重新打开位置记录并立刻取一次。 */
    fun enableLocation() {
        _uiState.update { it.copy(locationDisabled = false) }
        refreshLocation()
    }

    private suspend fun loadExisting(id: String) {
        val group = runCatchingCancellable { pairingRepository.current() }.getOrNull()
        runCatchingCancellable { mealRepository.getById(id) }
            .onSuccess { record ->
                if (record == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loadFailed = true,
                            errorMessage = "这条记录不存在或已删除",
                        )
                    }
                } else {
                    // 身份读不到时不能放行：GroupState.effectiveAdminId 在 myId 为空时
                    // 会回落成 ""，把自己当成管理员，等于任何人都能改别人的记录。
                    val editableGroup = group?.takeIf { it.myId.isNotBlank() }
                    // 别人的记录还要求本次运行已验过管理员口令：
                    // 只判 amIAdmin 会让未解锁的管理员也能进来改，口令等于白设。
                    val editable = editableGroup != null && adminGate.canModifyContent(
                        authorId = record.createdBy,
                        myId = editableGroup.myId,
                        amIAdmin = editableGroup.amIAdmin,
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            mealType = record.mealType,
                            dateMillis = record.timestamp,
                            timeSourceMillis = record.timestamp,
                            note = record.note,
                            noteLength = record.note.length,
                            media = record.media.map { item -> item.toEditing() },
                            rating = record.rating,
                            marked = record.marked,
                            authorId = record.createdBy,
                            authorName = group?.nameOf(record.createdBy) ?: "",
                            location = record.location,
                            locationDisabled = record.location == null && _uiState.value.locationDisabled,
                            tags = record.tags,
                            canEdit = editable,
                            // 红线：对无权修改的记录必须只读并说明原因，不能「点了再失败」。
                            errorMessage = when {
                                editable -> null
                                editableGroup == null -> "读不到本机身份，暂时无法编辑，请稍后重试"
                                editableGroup.amIAdmin -> "这条是别人记的：请先在群组页验证管理员口令，再回来编辑"
                                else -> "这条记录只有本人或管理员能改"
                            },
                        )
                    }
                }
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadFailed = true,
                        errorMessage = error.toUserText(),
                    )
                }
            }
    }

    fun setMealType(type: MealType) {
        if (_uiState.value.savePhase != SavePhase.IDLE) return
        _uiState.update { it.copy(mealType = type) }
    }

    fun setNote(note: String) {
        val trimmed = note.take(DinnerDimens.noteMaxLength)
        _uiState.update {
            it.copy(
                note = trimmed,
                noteLength = trimmed.length,
            )
        }
    }

    /** 设置日期，保留原有时刻。 */
    fun setDate(dateMillis: Long) {
        val source = _uiState.value.timeSourceMillis
        val merged = DateText.combineDateAndTime(dateMillis, source)
        _uiState.update {
            it.copy(dateMillis = merged)
        }
    }

    fun setRating(rating: Int?) {
        if (_uiState.value.savePhase != SavePhase.IDLE) return
        // 存库与落盘都按 1..5 记：越界值会让对方的星标控件读到一个不存在的档位。
        _uiState.update { it.copy(rating = rating?.coerceIn(RecordLimits.MIN_RATING, RecordLimits.MAX_RATING)) }
    }

    // ---------- 标签 ----------

    fun setTagInput(value: String) {
        _uiState.update { it.copy(tagInput = value.take(RecordLimits.MAX_TAG_LENGTH), errorMessage = null) }
    }

    /** 回车或点「添加」：把输入框内容收成一个标签。 */
    fun addTag() {
        val current = _uiState.value
        if (current.savePhase != SavePhase.IDLE) return
        val tag = current.tagInput.trim()
        if (tag.isBlank()) return
        if (current.tags.size >= RecordLimits.MAX_TAGS) {
            _uiState.update { it.copy(errorMessage = "一条记录最多 ${RecordLimits.MAX_TAGS} 个标签") }
            return
        }
        if (tag in current.tags) {
            _uiState.update { it.copy(tagInput = "") }
            return
        }
        _uiState.update {
            it.copy(tags = it.tags + tag, tagInput = "", errorMessage = null)
        }
    }

    fun removeTag(tag: String) {
        if (_uiState.value.savePhase != SavePhase.IDLE) return
        _uiState.update { it.copy(tags = it.tags.filterNot { t -> t == tag }) }
    }

    /** 星标：只是本机索引，不参与同步。 */
    fun toggleMarked() {
        if (_uiState.value.savePhase != SavePhase.IDLE) return
        _uiState.update { it.copy(marked = !it.marked) }
    }

    /** 相册 / 相机返回的媒体，统一走导入：按「压缩后存储」设置落盘，并取视频封面帧与时长。 */
    fun onMediaPicked(source: MediaSource) {
        val current = _uiState.value
        if (current.savePhase != SavePhase.IDLE) return
        if (!current.canAddMedia) {
            _uiState.update { it.copy(errorMessage = "一条记录最多 ${current.mediaLimit} 张") }
            return
        }
        _uiState.update { it.copy(savePhase = SavePhase.IMPORTING, errorMessage = null) }
        viewModelScope.launch {
            mediaImporter.import(source)
                .onSuccess { imported ->
                    _uiState.update { state ->
                        if (!state.canAddMedia) {
                            // 导入期间用户可能已经加满，丢弃这次结果而不是越界。
                            return@update state.copy(savePhase = SavePhase.IDLE)
                        }
                        state.copy(
                            savePhase = SavePhase.IDLE,
                            media = state.media + EditingMedia(
                                id = UUID.randomUUID().toString(),
                                localPath = imported.localPath,
                                thumbPath = imported.thumbnailPath,
                                mediaType = imported.mediaType,
                                durationMs = imported.durationMs,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            savePhase = SavePhase.IDLE,
                            errorMessage = error.toUserText(),
                        )
                    }
                }
        }
    }

    /** 移除指定的一段媒体。 */
    fun removeMedia(id: String) {
        if (_uiState.value.savePhase != SavePhase.IDLE) return
        _uiState.update { state -> state.copy(media = state.media.filterNot { it.id == id }) }
    }

    /** 只保存到本地：落 Room 标 PENDING，不主动上传。 */
    fun saveOnly() = startSave(upload = false)

    /** 保存并立即上传：落 Room 后马上触发一次同步。 */
    fun saveAndUpload() = startSave(upload = true)

    private fun startSave(upload: Boolean) {
        val current = _uiState.value
        if (current.savePhase != SavePhase.IDLE) return
        if (!current.canEdit) {
            _uiState.update { it.copy(errorMessage = "这条记录只有本人或管理员能改") }
            return
        }
        if (!current.canSave) {
            _uiState.update { it.copy(errorMessage = "写点什么，或者选几张照片 / 视频") }
            return
        }
        _uiState.update { it.copy(savePhase = SavePhase.SAVING, errorMessage = null) }

        viewModelScope.launch {
            saveInternal(upload)
        }
    }

    /** 保存后回调此函数，由导航层弹出输入页。 */
    var onSaved: ((String) -> Unit)? = null

    private suspend fun saveInternal(upload: Boolean) {
        val current = _uiState.value
        val now = System.currentTimeMillis()
        val merged = DateText.combineDateAndTime(current.dateMillis, current.timeSourceMillis)

        // 作者必须是一个真实身份。拿不到就停下来报错，
        // 不能退化成占位值——那样记录会带着一个谁也查不到的 id 上云。
        val author = current.authorId.takeIf { it.isNotBlank() }
            ?: runCatchingCancellable { pairingRepository.current() }
                .getOrNull()?.myId.orEmpty()
        if (author.isBlank()) {
            _uiState.update {
                it.copy(savePhase = SavePhase.IDLE, errorMessage = "还拿不到本机身份，请稍后重试")
            }
            return
        }

        // 没有群组就没有可写目录：一条记录的所有文件都只能落在 group_<管理员手机号>/ 之内。
        // UI 上「记一顿」按钮在未入组时已隐藏，这里是最后一道防线，防止绕过界面直接保存。
        val inGroup = runCatchingCancellable { groupRepository.groupId() }.getOrNull()
        if (inGroup.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    savePhase = SavePhase.IDLE,
                    errorMessage = "还没加入群组。记录只能存在群组目录里，请先建群或入群",
                )
            }
            return
        }

        val newRecord = MealRecord(
            id = current.recordId ?: UUID.randomUUID().toString(),
            timestamp = merged,
            mealType = current.mealType,
            note = current.note,
            createdBy = author,
            syncState = SyncState.PENDING,
            updatedAt = now,
            deletedAt = null,
            rating = current.rating,
            media = current.media.map { item ->
                MediaItem(
                    id = item.id,
                    mediaType = item.mediaType,
                    // 编辑已有记录时，云端独有、本机还没下载的那几段用空串占位；
                    // 落库前归一成 null，避免 "空路径" 被当成有效本地文件。
                    localPath = item.localPath.takeIf { it.isNotBlank() },
                    remotePath = item.remotePath,
                    md5 = item.md5,
                    thumbPath = item.thumbPath,
                    durationMs = item.durationMs,
                )
            },
            marked = current.marked,
            location = current.location,
            // 落库前再归一化一次：trim / 去空 / 截长度 / 去重 / 限条数，
            // 与 MealRecordMapper.normalizeTags 同一套规则，避免两端各算一套。
            tags = normalizeTags(current.tags),
        )
        runCatchingCancellable { mealRepository.upsert(newRecord) }
            .onSuccess {
                // 本地已存，立即导航返回。两个按钮的差别只在「要不要现在上传」。
                onSaved?.invoke(newRecord.id)
                if (!upload) {
                    _uiState.update { it.copy(savePhase = SavePhase.DONE) }
                    return@onSuccess
                }

                _uiState.update { it.copy(savePhase = SavePhase.UPLOADING) }
                // 后台触发同步上传（auto=true：只推新的 PENDING，
                // 不自动重试历史 FAILED，由用户在「我的」页手动重试）。如实显示结果。
                viewModelScope.launch {
                    val outcome = runCatchingCancellable {
                        syncRepository.syncNow(auto = true)
                    }.getOrNull()
                    // 三种结局必须分开：跑了并成功、压根没跑起来、跑了但没成功。
                    //
                    // 早先把「跑了但 isSuccess == false」也置成 DONE，界面显示「已保存」——
                    // 记录确实落在本机了，但它没传上云，别人看不到。这是「假装成功」。
                    when {
                        outcome?.isSuccess == true -> _uiState.update {
                            it.copy(savePhase = SavePhase.DONE, errorMessage = null)
                        }
                        outcome == null -> _uiState.update {
                            it.copy(
                                savePhase = SavePhase.FAILED,
                                errorMessage = "记录已存本机，但同步没能启动。可稍后在「我的」页手动同步",
                            )
                        }
                        else -> _uiState.update {
                            it.copy(
                                savePhase = SavePhase.PARTIAL,
                                errorMessage = "记录已存本机，但没传上云盘：" +
                                    (outcome.message ?: "同步未完成") +
                                    "。别人暂时看不到，可稍后手动同步",
                            )
                        }
                    }
                }
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(
                        savePhase = SavePhase.FAILED,
                        errorMessage = error.toUserText(),
                    )
                }
            }
    }
}

/**
 * 领域媒体 -> 编辑态媒体。
 *
 * 已经在云端、但本机还没下载回来的那几段，用空串占位保留下来：
 * 编辑页显示占位而不是把它们从列表里悄悄删掉，
 * 否则用户改个备注就会把别人的照片从记录里弄丢。
 */
private fun MediaItem.toEditing(): EditingMedia = EditingMedia(
    id = id,
    localPath = localPath.orEmpty(),
    thumbPath = thumbPath,
    mediaType = mediaType,
    durationMs = durationMs,
    remotePath = remotePath,
    md5 = md5,
)

private fun MutableStateFlow<EditUiState>.update(transform: (EditUiState) -> EditUiState) {
    value = transform(value)
}
