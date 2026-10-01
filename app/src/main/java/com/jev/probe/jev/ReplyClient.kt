package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.ChatContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The generative route: any OpenAI-compatible `/chat/completions` endpoint.
 * Drafts the 3 candidate replies, and (D stage) summarizes text. Reads
 * replyBaseUrl / replyKey / replyModel from [Prefs].
 */
class ReplyClient(private val prefs: Prefs) {

    /**
     * Exactly 3 varied candidate replies in Chinese.
     *
     * @param ctx D-stage knowledge context. When present its background and
     *        history are prepended to the prompt with an instruction to stay
     *        consistent with them and invent nothing beyond them.
     */
    fun draft(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null,
        continuation: Boolean = snapshot.latestFrom == "me"
    ): List<String> {
        val convo = snapshot.messages.takeLast(100).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val mode = if (continuation) {
            "最近一条消息是我刚刚发出的。现在生成的是我下一句要发送的内容，用来接着当前话题自然聊天。" +
                "不要假装在回答对方刚说的话，不要把‘我：…’改写成‘对方：…’，也不要自问自答。" +
                "如果我刚刚问了问题，可以补充问题背景、延展相关细节或自然追问；不要替对方回答，也不要生成结束对话、让我等待的句子。"
        } else {
            "最近一条消息是对方发出的。现在生成我对对方的下一条回复。"
        }
        val strategy = if (continuation) {
            "三条都必须是我现在可以发送的内容，分别采用自然补充、围绕刚才内容延展、相关追问这三种方式；" +
                "不要重复我刚发过的话、替我回答自己的问题、编造对方回复、突然换题或给无关承诺/道歉。"
        } else {
            "三条策略要有区别（例如：一条稳妥承接、一条给具体行动或承诺、一条简短低姿态）。"
        }
        val sys = "你是中文即时通讯回复助手。$mode 只输出一个 JSON 数组，含且仅含 3 条候选回复文本，" +
            strategy +
            "每条不超过 40 字，口语、自然、像真人在聊天软件里发消息。不要解释，不要加引号以外的内容，直接输出 JSON 数组。"
        val user = knowledgeBlock(relationship, ctx) + styleBlock(ctx) +
            "关系：$relationship\n\n最近对话：\n$convo\n\n$mode\n请给出 3 条候选回复。"
        return parseThree(chat(sys, user, temperature = 0.8))
    }

    /** The background + history preamble; empty string when there is no context. */
    private fun knowledgeBlock(relationship: String, ctx: ChatContext?): String {
        ctx ?: return ""
        val background = ctx.background(relationship)
        val history = ctx.history
        if (background.isBlank() && history.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("以下是关于我和对方的背景与知识库，回复必须与之一致，")
            .append("可以直接引用其中事实，不要编造知识库里没有的事实。\n")
        if (background.isNotBlank()) sb.append(background).append('\n')
        if (history.isNotEmpty()) {
            sb.append("\n更早的聊天记录（越靠下越新）：\n")
            history.takeLast(prefs.contextHistoryCount.coerceIn(0, 100)).forEach {
                sb.append(if (it.side == "me") "我：" else "对方：").append(it.text).append('\n')
            }
        }
        sb.append('\n')
        return sb.toString()
    }

    /** Style is a wording hint only; it must never override safety or topic fit. */
    private fun styleBlock(ctx: ChatContext?): String {
        val style = ctx?.style ?: return ""
        if (style.summary.isBlank()) return ""
        return buildString {
            append("以下是根据我过去消息主动综合出的说话风格档案：\n")
            append(style.summary).append('\n')
            append("风格要求是次要要求：先保证风险判断、事实准确、尊重对方、承接当前话题和不自问自答，再尽量贴近这份风格档案。\n\n")
        }
    }

    /** Synthesize a compact wording profile from local outgoing-message history. */
    fun synthesizeStyle(messages: List<String>, previous: String = ""): String {
        val cleaned = messages.map { it.trim() }.filter { it.isNotBlank() }.takeLast(120)
        if (cleaned.isEmpty() && previous.isBlank()) return ""
        val old = if (previous.isBlank()) "无" else previous.trim()
        val source = cleaned.mapIndexed { i, text -> "${i + 1}. $text" }.joinToString("\n")
        val sys = "你是中文聊天风格分析助手。请综合用户过去自己发送的消息，写一份简洁、可执行的说话风格档案，" +
            "用于帮助另一个模型模仿用户表达。只描述表达习惯，不评价人格，不推断隐私，不编造样本。" +
            "重点包括：句子长短、语气、常用语、标点和表情习惯、直接程度、主动程度、面对不同话题的变化。" +
            "如果提供了旧档案，请在其基础上结合新消息修正。输出不超过 260 字的纯文本，不要标题，不要解释。"
        val user = "旧的风格档案：\n$old\n\n本次用于综合的我方历史消息：\n$source"
        return chat(sys, user, temperature = 0.2).trim().take(1000)
    }

    /**
     * One plain chat round trip for the settings connectivity test. Deliberately
     * NOT [summarize]: the test should exercise the ordinary path, not whatever
     * the summary prompt happens to be.
     */
    fun ping(): String =
        chat("你是连通性测试助手，只按要求回答，不要解释。", "请只回复两个字：收到", temperature = 0.0).trim()

    /** Condense a block of text (used by the D-stage contact auto-summary). */
    fun summarize(text: String): String {
        if (text.isBlank()) return ""
        val sys = "你是中文摘要助手。把给到的聊天记录压缩成不超过 120 字的第三人称要点摘要，" +
            "只保留事实、偏好、承诺和待办，不要评论，不要编造。直接输出摘要正文。"
        return chat(sys, text, temperature = 0.2).trim()
    }

    /** One chat-completions round trip; returns the assistant message content. */
    private fun chat(system: String, user: String, temperature: Double): String {
        val url = prefs.replyEndpoint()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", prefs.replyModel)
            .put("messages", messages)
            .put("temperature", temperature)
        val resp = HttpJson.post(url, prefs.effectiveReplyKey(), body, Route.REPLY, HttpJson.headersFor(url))
        return resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
    }

    private fun parseThree(content: String): List<String> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val arr = JSONArray(content.substring(start, end + 1))
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i).trim())
                if (out.size >= 3) return out.take(3)
                while (out.size < 3) out.add("（稍等，我看下）")
                return out
            } catch (_: Exception) { }
        }
        // Fallback: split lines.
        val lines = content.split("\n").map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"') }
            .filter { it.isNotBlank() }
        val out = lines.take(3).toMutableList()
        while (out.size < 3) out.add("（稍等，我看下）")
        return out
    }
}
