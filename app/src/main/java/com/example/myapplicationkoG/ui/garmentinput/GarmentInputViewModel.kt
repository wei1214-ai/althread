package com.example.myapplicationkoG.ui.garmentinput

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplicationkoG.di.ServiceLocator
import com.example.myapplicationkoG.domain.model.GarmentSideId
import com.example.myapplicationkoG.inference.ClothingInferencePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * State holder for [GarmentInputScreen].
 *
 * Flow per side (FRONT then BACK):
 *   1. Copy the picked Uri into app private storage (file:// is stable).
 *   2. Run on-device cloth U2NET → cutout bitmap.
 *   3. Save the cutout as a PNG so the next screen can pick it up.
 *
 * UI just shows the final cutout preview for each side.
 */
class GarmentInputViewModel(app: Application) : AndroidViewModel(app) {

    private val inference: ClothingInferencePipeline =
        ServiceLocator.inferencePipeline(app)
    private val cacheDir: File = File(app.filesDir, "garment_assets").apply { mkdirs() }

    private val _state = MutableStateFlow(GarmentInputUiState())
    val state: StateFlow<GarmentInputUiState> = _state.asStateFlow()

    private val runId: String = UUID.randomUUID().toString()
    private val pickerMutex = Mutex()

    var openDesignId: String? = null
    var openDesignName: String = ""
    var openDesignChallengePostId: String? = null

    // Deferred deletion: keep cache files until new design successfully loaded.
    // OnBack / navigation should keep files (LRU or explicit clear will clean up).
    private val pendingDeletionPaths = mutableListOf<String>()

    // Editor state that survives rotation via ViewModel (alternative to rememberSaveable).
    // Types are defined in ui.editor but referenced here for StateFlow survival.
    // Safe Kotlin: no !!, use ?.let and runCatching.

    // ChallengeSession fields moved to ViewModel for rotation survival.
    private val _challengePostId = MutableStateFlow<String?>(null)
    val challengePostIdFlow: StateFlow<String?> = _challengePostId.asStateFlow()
    var challengePostId: String?
        get() = _challengePostId.value
        set(value) { _challengePostId.value = value }
    private val _challengeTitle = MutableStateFlow<String?>(null)
    val challengeTitleFlow: StateFlow<String?> = _challengeTitle.asStateFlow()
    var challengeTitle: String?
        get() = _challengeTitle.value
        set(value) { _challengeTitle.value = value }
    private val _challengeDescription = MutableStateFlow<String?>(null)
    val challengeDescriptionFlow: StateFlow<String?> = _challengeDescription.asStateFlow()
    var challengeDescription: String?
        get() = _challengeDescription.value
        set(value) { _challengeDescription.value = value }

    fun stageChallenge(postId: String, title: String, description: String) {
        _challengePostId.value = postId
        _challengeTitle.value = title
        _challengeDescription.value = description
        // Also keep singleton in sync for screens that still read ChallengeSession directly
        com.example.myapplicationkoG.ui.editor.ChallengeSession.postId = postId
        com.example.myapplicationkoG.ui.editor.ChallengeSession.title = title
        com.example.myapplicationkoG.ui.editor.ChallengeSession.description = description
    }

    fun clearChallenge() {
        _challengePostId.value = null
        _challengeTitle.value = null
        _challengeDescription.value = null
        com.example.myapplicationkoG.ui.editor.ChallengeSession.postId = null
        com.example.myapplicationkoG.ui.editor.ChallengeSession.title = null
        com.example.myapplicationkoG.ui.editor.ChallengeSession.description = null
    }

    fun peekChallengePair(): Pair<String?, String?> = _challengeTitle.value to _challengeDescription.value

    fun stageDesignTyped(
        dye: Map<GarmentSideId, com.example.myapplicationkoG.ui.editor.DyeState>,
        buttons: Map<GarmentSideId, List<com.example.myapplicationkoG.ui.editor.PlacedButton>>
    ) {
        com.example.myapplicationkoG.ui.editor.DesignSession.stage(dye, buttons)
    }

    fun openSavedDesign(
        id: String,
        name: String,
        front: File,
        back: File,
        challengePostId: String? = null
    ) {
        openDesignId = id
        openDesignName = name
        openDesignChallengePostId = challengePostId
        loadDesignPaths(front, back)
    }

    fun loadDesignPaths(front: File, back: File) {
        // Capture old paths before update for deferred deletion check
        val oldFront = _state.value.frontCutoutPath
        val oldBack = _state.value.backCutoutPath
        _state.update {
            it.copy(
                frontCutoutPath = front.absolutePath,
                backCutoutPath = back.absolutePath,
                frontError = null,
                backError = null,
                isLoading = false,
                loadingSide = null
            )
        }
        // New design successfully loaded -> now safe to delete old files
        // Includes any paths queued by prior clearAll(keepFiles=true)
        val toDelete = mutableListOf<String>()
        // queued pending deletions
        toDelete.addAll(pendingDeletionPaths)
        pendingDeletionPaths.clear()
        oldFront?.let { if (it != front.absolutePath) toDelete.add(it) }
        oldBack?.let { if (it != back.absolutePath) toDelete.add(it) }
        toDelete.forEach { path ->
            if (path != front.absolutePath && path != back.absolutePath) {
                runCatching { File(path).delete() }
            }
        }
        // LRU: keep cacheDir bounded - delete oldest files if >20 files
        runCatching {
            val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
            if (files.size > 20) {
                files.take(files.size - 20).forEach { f -> runCatching { f.delete() } }
            }
        }
    }

    /**
     * Clears UI state. By default keeps cache files (deferred deletion) so onBack
     * does not immediately delete files. Files are deleted when a new design is
     * successfully loaded or when [deleteFiles] is true (explicit clear / LRU).
     */
    fun clearAll(deleteFiles: Boolean = false) {
        if (deleteFiles) {
            // Explicit clear: delete immediately
            _state.value.frontCutoutPath?.let { runCatching { File(it).delete() } }
            _state.value.backCutoutPath?.let { runCatching { File(it).delete() } }
            pendingDeletionPaths.clear()
            // also clear any queued pending files
            runCatching {
                pendingDeletionPaths.forEach { p -> runCatching { File(p).delete() } }
            }
        } else {
            // Defer deletion: queue current paths for deletion after next successful load
            _state.value.frontCutoutPath?.let { pendingDeletionPaths.add(it) }
            _state.value.backCutoutPath?.let { pendingDeletionPaths.add(it) }
        }
        // Keep cacheDir but clear state
        _state.value = GarmentInputUiState()
        openDesignId = null
        openDesignName = ""
        openDesignChallengePostId = null
    }

    /** Back navigation: clear state but keep cache files until explicit clear or LRU. */
    fun clearForBack() {
        clearAll(deleteFiles = false)
    }

    /** Explicit user clear that should delete files now. */
    fun clearAllExplicit() {
        clearAll(deleteFiles = true)
    }

    fun onPickedImage(side: GarmentSideId, uri: Uri) {
        if (_state.value.isLoading) return
        viewModelScope.launch {
            pickerMutex.withLock {
            _state.update {
                when (side) {
                    GarmentSideId.FRONT -> it.copy(isLoading = true, loadingSide = GarmentSideId.FRONT, frontError = null)
                    GarmentSideId.BACK -> it.copy(isLoading = true, loadingSide = GarmentSideId.BACK, backError = null)
                }
            }
            try {
                // Remember old path to delete after new one succeeds (avoid Coil cache showing old image)
                val oldPath = when (side) {
                    GarmentSideId.FRONT -> _state.value.frontCutoutPath
                    GarmentSideId.BACK -> _state.value.backCutoutPath
                }
                val cutout = withContext(Dispatchers.IO) {
                    val imported = copyToCache(uri)
                        ?: error("Could not import picked image")
                    try {
                        // Same bytes = same cutout, reuse cache and skip inference.
                        // v2 key: pipeline fixes must re-run instead of reusing v1 results.
                        val out = File(cacheDir, "cutout2_${sha256(imported)}.png")
                        if (!(out.exists() && out.length() > 0L)) {
                            val result = inference.run(imported)
                            try {
                                savePng(result.cutout, out)
                            } finally {
                                // pipeline returns a fresh bitmap, recycle after saving to avoid OOM
                                runCatching { if (!result.cutout.isRecycled) result.cutout.recycle() }
                            }
                        }
                        // Delete old cutout file for this side
                        if (oldPath != null && oldPath != out.absolutePath) {
                            runCatching { File(oldPath).delete() }
                        }
                        out
                    } finally {
                        // clean up imported temp file
                        runCatching { imported.delete() }
                    }
                }
                _state.update { current ->
                    when (side) {
                        GarmentSideId.FRONT -> current.copy(frontCutoutPath = cutout.absolutePath, frontError = null, isLoading = false, loadingSide = null)
                        GarmentSideId.BACK -> current.copy(backCutoutPath = cutout.absolutePath, backError = null, isLoading = false, loadingSide = null)
                    }
                }
            } catch (t: Throwable) {
                val msg = t.message ?: "Failed to process image"
                _state.update {
                    when (side) {
                        GarmentSideId.FRONT -> it.copy(isLoading = false, loadingSide = null, frontError = msg)
                        GarmentSideId.BACK -> it.copy(isLoading = false, loadingSide = null, backError = msg)
                    }
                }
            }
            }
        }
    }

    private fun copyToCache(uri: Uri): File? {
        val target = File(cacheDir, "in_${runId}_${System.currentTimeMillis()}.jpg")
        return try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            if (target.length() == 0L) return null
            if (target.length() > 12 * 1024 * 1024) {
                runCatching { target.delete() }
                error("Image too large (max 12MB)")
            }
            if (target.length() > 0L) target else null
        } catch (t: Throwable) {
            runCatching { target.delete() }
            if (t.message?.contains("too large") == true) throw t
            null
        }
    }

    private fun savePng(bitmap: Bitmap, file: File) {
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class GarmentInputUiState(
    val frontCutoutPath: String? = null,
    val backCutoutPath: String? = null,
    val frontError: String? = null,
    val backError: String? = null,
    val isLoading: Boolean = false,
    val loadingSide: GarmentSideId? = null,
)