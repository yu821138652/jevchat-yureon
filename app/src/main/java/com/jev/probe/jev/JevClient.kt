package com.jev.probe.jev

import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.kb.ChatContext

/**
 * Thin facade over the three split clients so callers keep one entry point.
 * Construct with [Prefs] — every route reads its own address / key / model from
 * there, so switching providers in settings takes effect on the next call.
 */
class JevClient(prefs: Prefs) {

    private val judgeClient = JudgeClient(prefs)
    private val replyClient = ReplyClient(prefs)

    /** The 7 judgment questions. Errors come back inside [Analysis.error]. */
    fun judge(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        if (snapshot.latestFrom == "me") {
            // The calibrated questions all ask about the other person's latest message.
            // Running them on my own line creates confident but meaningless judgments.
            return Analysis(
                trueIntent = null,
                dangerLevel = null,
                sheNeeds = null,
                shouldReplyNow = null,
                bestAction = null,
                tensionResolved = null,
                literalQuestion = null,
                rankedReplies = emptyList(),
                latencyMs = 0,
                continuation = true
            )
        }
        return judgeClient.judge(snapshot, relationship, ctx)
    }

    /** Draft 3 candidates on the reply route, then rank them on the judge route. */
    fun draftAndRank(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null,
        continuation: Boolean = snapshot.latestFrom == "me"
    ): List<RankedReply> {
        val candidates = replyClient.draft(snapshot, relationship, ctx, continuation)
        return judgeClient.rank(snapshot, relationship, candidates, ctx, continuation)
    }

    /** Judge + replies, sequential. Used by the settings connectivity test. */
    fun analyze(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        val a = judge(snapshot, relationship, ctx)
        if (a.error != null) return a
        val ranked = try { draftAndRank(snapshot, relationship, ctx) } catch (e: Exception) { emptyList() }
        return a.copy(rankedReplies = ranked)
    }
}
