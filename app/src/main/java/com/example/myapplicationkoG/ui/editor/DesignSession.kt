package com.example.myapplicationkoG.ui.editor

import com.example.myapplicationkoG.domain.model.GarmentSideId

data class LoadedDesign(
    val dye: Map<GarmentSideId, DyeState>,
    val buttons: Map<GarmentSideId, List<PlacedButton>>
)

/**
 * One-shot handoff from the Continue screen to the editor.
 * Consumed once so recomposition never re-applies stale data.
 */
internal object SessionHolder {
    @Volatile var pendingDesign: LoadedDesign? = null
    @Volatile var challengePostId: String? = null
    @Volatile var challengeTitle: String? = null
    @Volatile var challengeDescription: String? = null
}

object DesignSession {
    private var pending: LoadedDesign?
        get() = SessionHolder.pendingDesign
        set(value) { SessionHolder.pendingDesign = value }

    fun stage(dye: Map<GarmentSideId, DyeState>, buttons: Map<GarmentSideId, List<PlacedButton>>) {
        pending = LoadedDesign(dye, buttons)
    }

    fun consume(): LoadedDesign? {
        val p = pending
        pending = null
        return p
    }
}

object ChallengeSession {
    var postId: String?
        get() = SessionHolder.challengePostId
        set(value) { SessionHolder.challengePostId = value }
    var title: String?
        get() = SessionHolder.challengeTitle
        set(value) { SessionHolder.challengeTitle = value }
    var description: String?
        get() = SessionHolder.challengeDescription
        set(value) { SessionHolder.challengeDescription = value }

    fun stage(postId: String?, title: String, description: String) {
        SessionHolder.challengePostId = postId
        SessionHolder.challengeTitle = title
        SessionHolder.challengeDescription = description
    }

    fun stage(title: String, description: String) {
        stage(null, title, description)
    }

    fun consume(): Triple<String?, String?, String?> {
        val id = postId
        val t = title
        val d = description
        postId = null
        title = null
        description = null
        return Triple(id, t, d)
    }

    fun peek(): Triple<String?, String?, String?> = Triple(postId, title, description)
    fun peekPair(): Pair<String?, String?> = title to description
}
