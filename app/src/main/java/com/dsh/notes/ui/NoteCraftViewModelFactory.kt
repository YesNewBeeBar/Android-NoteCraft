package com.dsh.notes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dsh.notes.AppContainer

/**
 * 手工工厂：项目不用 Hilt，一个 ViewModel 一个分支就够了，
 * 比引入注解处理器省事得多（也不会因为 kapt/ksp 版本不匹配而编不过）。
 */
class NoteCraftViewModelFactory(
    private val container: AppContainer,
    /** 只有详情页需要：它按 noteId 取数据 */
    private val noteId: Long = -1L
) : ViewModelProvider.Factory {

    /** 详情页用这个派生一个带 noteId 的工厂；其余页面继续用共享的这一个 */
    fun forNote(noteId: Long): NoteCraftViewModelFactory = NoteCraftViewModelFactory(container, noteId)

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val viewModel: ViewModel = when {
            modelClass.isAssignableFrom(MainViewModel::class.java) -> MainViewModel(container)
            modelClass.isAssignableFrom(RecordingViewModel::class.java) -> RecordingViewModel(container)
            modelClass.isAssignableFrom(NotesViewModel::class.java) -> NotesViewModel(container)
            modelClass.isAssignableFrom(NoteDetailViewModel::class.java) -> NoteDetailViewModel(container, noteId)
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(container)
            else -> throw IllegalArgumentException("不认识的 ViewModel：${modelClass.name}")
        }
        return viewModel as T
    }
}
