package com.guolongli.dinner.feature.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guolongli.dinner.domain.repository.AppLockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用锁状态。
 *
 * [locked] 由 enabled 与本次是否已解锁共同决定：
 * 关了开关时永远不上锁，开了开关时冷启动必定上锁。
 */
data class AppLockUiState(
    val enabled: Boolean = false,
    val unlocked: Boolean = false,
    val errorMessage: String? = null,
    /**
     * 开关是否已从 DataStore 读出来。
     *
     * 首帧读不到时不能直接放行——否则开着应用锁也会先闪一下时间线。
     */
    val ready: Boolean = false,
) {
    val locked: Boolean get() = enabled && !unlocked
}

/**
 * 应用锁 ViewModel。
 *
 * 只在 Activity 级别使用（由 MainActivity 的 Composable 持有），
 * 这样配置变化不会把「已解锁」状态丢掉。
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val repository: AppLockRepository,
) : ViewModel() {

    /**
     * 本次进程内是否已解锁。
     *
     * 刻意做成「冷启动必锁、后台回来不重锁」：授权流程要跳浏览器，
     * 回来还要再验一次指纹会把刚完成的授权结果挡在门外。
     */
    private val unlocked = MutableStateFlow(false)

    private val _error = MutableStateFlow<String?>(null)

    private val _state = MutableStateFlow(AppLockUiState())
    val state: StateFlow<AppLockUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.observeEnabled(), unlocked, _error) { enabled, isUnlocked, error ->
                AppLockUiState(enabled = enabled, unlocked = isUnlocked, errorMessage = error)
            }.collect { next -> _state.value = next.copy(ready = true) }
        }
    }

    fun unlock() {
        unlocked.value = true
        _error.value = null
    }

    fun lock() {
        unlocked.value = false
    }

    fun reportError(message: String?) {
        _error.value = message
    }

    fun setEnabled(on: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(on)
            // 打开开关时先视为已解锁：用户此刻就在应用里，不该立刻被拦住。
            if (on) unlocked.value = true else _error.value = null
        }
    }

    /** 系统没有可用的锁屏 / 生物识别时，给用户一个自行脱困的出口。 */
    fun disableAndUnlock() {
        viewModelScope.launch {
            repository.setEnabled(false)
            unlocked.value = true
            _error.value = null
        }
    }
}
