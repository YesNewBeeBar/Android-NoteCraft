package com.dsh.notes.util

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 把问题丢给浏览器自己去搜。
 *
 * 大肥鱼没有联网能力，所以「这条我答不了」的时候，与其硬编一个答案，
 * 不如把用户送到真正能搜的地方 —— 问题已经填好了，点一下就是结果页。
 */
fun openWebSearch(context: Context, query: String) {
    val text = query.trim()
    if (text.isEmpty()) return

    // 优先走系统搜索意图（会落到用户自己设的搜索 App）
    val webSearch = Intent(Intent.ACTION_WEB_SEARCH).apply {
        putExtra(SearchManager.QUERY, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(webSearch) }.isSuccess) return

    // 没有处理器就退回浏览器 + 百度
    val uri = Uri.parse("https://www.baidu.com/s?wd=" + Uri.encode(text))
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
