package com.dsh.notes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dsh.notes.AppContainer

/**
 * 手工工厂：项目不用 Hilt，一个 ViewModel 一个分支就够了，
 * 比引入注解处理器省事得多（也不会因为 kapt/ksp 版本不匹配而编不过）。
 */
class NoteCraftViewModelFactory(
    private val container: AppContainer
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val viewModel: ViewModel = when {
            modelClass.isAssignableFrom(MainViewModel::class.java) -> MainViewModel(container)
            modelClass.isAssignableFrom(RecordingViewModel::class.java) -> RecordingViewModel(container)
            modelClass.isAssignableFrom(NotesViewModel::class.java) -> NotesViewModel(container)
            modelClass.isAssignableFrom(NoteDetailViewModel::class.java) -> NoteDetailViewModel(container)
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(container)
            else -> throw IllegalArgumentException("不认识的 ViewModel：${modelClass.name}")
        }
        return viewModel as T
    }
}
