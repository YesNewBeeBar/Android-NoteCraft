package com.dsh.notes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.notes.AppContainer
import com.dsh.notes.ai.FishPrompt
import com.dsh.notes.data.AppSettings
import com.dsh.notes.data.ChatMessage
import com.dsh.notes.data.ChatSession
import com.dsh.notes.data.Folder
import com.dsh.notes.data.Note
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 这次的参考范围：勾了哪些笔记、哪些文件夹。都为空 = 以全部笔记为参考 */
data class FishReference(
    val noteIds: Set<Long> = emptySet(),
    val folderIds: Set<Long> = emptySet()
) {
    val isEmpty: Boolean get() = noteIds.isEmpty() && folderIds.isEmpty()
    val count: Int get() = noteIds.size + folderIds.size

    companion object {
        /** 「未分类」不是真文件夹，用这个哨兵 id 表示 */
        const val UNFILED_ID = -1L
    }
}

/**
 * 大肥鱼问答页。
 *
 * 只从笔记页那个机器人球进来，不走导航栏 —— 它是一个「随手问一句」的旁路，
 * 不是一个和「录音 / 笔记 / 设置」平级的一级页面。
 */
class FishAiViewModel(private val container: AppContainer) : ViewModel() {

    private val chatDao = container.chatDao

    val settings: StateFlow<AppSettings> = container.settings.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val allNotes: StateFlow<List<Note>> = container.repository.observeSearch("")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<Folder>> = container.repository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 历史对话，最近的在前 */
    val sessions: StateFlow<List<ChatSession>> = chatDao.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 打开某段历史对话 */
    fun openSession(id: Long) {
        if (_sending.value) return
        viewModelScope.launch {
            sessionId = id
            _messages.value = chatDao.messages(id)
            _streaming.value = null
            _error.value = null
        }
    }

    fun deleteSession(id: Long) {
        viewModelScope.launch {
            chatDao.deleteMessages(id)
            chatDao.deleteSession(id)
            if (id == sessionId) {
                val next = chatDao.latestSession()
                if (next != null) {
                    sessionId = next.id
                    _messages.value = chatDao.messages(next.id)
                } else {
                    sessionId = chatDao.insertSession(ChatSession())
                    _messages.value = emptyList()
                }
            }
        }
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** 正在流式输出的那半截回答；null 表示没在回答 */
    private val _streaming = MutableStateFlow<String?>(null)

    val streaming: StateFlow<String?> = _streaming.asStateFlow()

    private val _sending = MutableStateFlow(false)

    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _reference = MutableStateFlow(FishReference())

    val reference: StateFlow<FishReference> = _reference.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    val error: StateFlow<String?> = _error.asStateFlow()

    private var sessionId = 0L
    private var sendJob: Job? = null

    init {
        viewModelScope.launch {
            val existing = chatDao.latestSession()
            if (existing != null) {
                sessionId = existing.id
                _messages.value = chatDao.messages(existing.id)
            } else {
                sessionId = chatDao.insertSession(ChatSession())
            }
        }
    }

    fun setReference(value: FishReference) {
        _reference.value = value
    }

    fun clearError() {
        _error.value = null
    }

    /** 开一段新对话；旧对话留在库里，可以从历史里翻回来 */
    fun newSession() {
        if (_sending.value) return
        viewModelScope.launch {
            sessionId = chatDao.insertSession(ChatSession())
            _messages.value = emptyList()
            _streaming.value = null
            _error.value = null
            // 新对话不该继承上一次的参考范围
            _reference.value = FishReference()
        }
    }

    fun stop() {
        sendJob?.cancel()
        sendJob = null
        _sending.value = false
        _streaming.value = null
    }

    /**
     * 发一条问题。
     *
     * 参考内容只取「整理后的正文」——原始转写一个字都不发，
     * 那是没校对过的毛坯，只会干扰模型。
     */
    fun send(question: String) {
        val text = question.trim()
        if (text.isEmpty() || _sending.value || sessionId == 0L) return

        sendJob = viewModelScope.launch {
            _sending.value = true
            _error.value = null

            val userMessage = ChatMessage(
                sessionId = sessionId,
                role = ChatMessage.ROLE_USER,
                content = text
            )
            val userId = chatDao.insertMessage(userMessage)
            _messages.value = _messages.value + userMessage.copy(id = userId)

            val current = settings.value
            val baseUrl = current.fishBaseUrl.ifBlank { current.deepseekBaseUrl }
            val apiKey = current.fishApiKey.ifBlank { current.deepseekApiKey }
            val model = current.fishModel.ifBlank { current.deepseekModel }

            val referenceText = buildReference()
            val system = buildString {
                append(FishPrompt.systemPrompt(current.fishName))
                if (referenceText.isNotBlank()) {
                    appendLine()
                    appendLine()
                    appendLine("----- 参考笔记开始 -----")
                    append(referenceText)
                    appendLine()
                    append("----- 参考笔记结束 -----")
                }
            }

            // 只带最近几轮，避免上下文无限膨胀
            val history = _messages.value
                .filter { it.id != userId }
                .takeLast(10)
                .map { it.role to it.content }

            val turns = buildList {
                add("system" to system)
                addAll(history)
                add("user" to text)
            }

            _streaming.value = ""
            try {
                val answer = container.ai.deepseek.chatStream(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    messages = turns
                ) { delta ->
                    _streaming.value = (_streaming.value ?: "") + delta
                }

                val finalText = answer.ifBlank {
                    (_streaming.value ?: "").ifBlank { "（这次没有返回内容）" }
                }
                val assistant = ChatMessage(
                    sessionId = sessionId,
                    role = ChatMessage.ROLE_ASSISTANT,
                    content = finalText
                )
                val id = chatDao.insertMessage(assistant)
                _messages.value = _messages.value + assistant.copy(id = id)
                chatDao.touch(sessionId, text.take(18), System.currentTimeMillis())
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _error.value = error.message ?: "请求失败"
            } finally {
                _streaming.value = null
                _sending.value = false
            }
        }
    }

    private suspend fun buildReference(): String {
        val selection = _reference.value
        val all = runCatching { container.repository.observeSearch("").first() }.getOrDefault(emptyList())
        if (all.isEmpty()) return ""

        if (selection.isEmpty) {
            // 没勾选：全部笔记，只发标题+摘要+要点，控制长度
            return buildString {
                appendLine("（用户没有指定范围，以下是全部 ${all.size} 篇笔记的摘要）")
                append(FishPrompt.buildReference(all, FishPrompt.ALL_NOTES_LIMIT, fullText = false))
            }
        }

        val picked = all.filter { note ->
            note.id in selection.noteIds ||
                (note.folderId != null && note.folderId in selection.folderIds) ||
                (note.folderId == null && FishReference.UNFILED_ID in selection.folderIds)
        }
        if (picked.isEmpty()) return ""

        // 明确勾选的：发整理后的正文
        return buildString {
            appendLine("（用户选中了 ${picked.size} 篇笔记，以下是它们的整理后正文）")
            append(FishPrompt.buildReference(picked, FishPrompt.SELECTED_LIMIT, fullText = true))
        }
    }
}
