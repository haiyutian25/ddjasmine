package jasmine.sample.example.common.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI 状态通用契约：加载中 / 出错 / 错误信息。 */
interface BaseUiState {
    val isLoading: Boolean
    val isError: Boolean
    val errorMessage: String?
}

/**
 * 极简 ViewModel 基类：持有 [BaseUiState] 的 StateFlow，提供 [updateState]。
 * 替代容器注入（本框架无 Koin）。
 */
abstract class BaseViewModel<S : BaseUiState>(initial: S) : androidx.lifecycle.ViewModel() {
    private val _uiState = MutableStateFlow(initial)
    val uiState: StateFlow<S> = _uiState.asStateFlow()

    protected fun updateState(transform: S.() -> S) {
        _uiState.value = _uiState.value.transform()
    }
}
