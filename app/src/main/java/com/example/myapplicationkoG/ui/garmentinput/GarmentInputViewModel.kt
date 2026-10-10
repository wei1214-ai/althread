package com.example.myapplicationkoG.ui.garmentinput

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplicationkoG.di.ServiceLocator
import com.example.myapplicationkoG.domain.model.GarmentSideId
import com.example.myapplicationkoG.inference.ClothingInferencePipeline
import com.example.myapplicationkoG.VtonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Manages garment imports, cutouts, and editor state. */
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

    // Retain assets across back navigation; delete them after replacement or an explicit clear.
    private val pendingDeletionPaths = mutableListOf<String>()

    // State flows preserve challenge metadata across configuration changes.
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

    // Staged designs survive configuration changes.
    private val _stagedDesign =
        kotlinx.coroutines.flow.MutableStateFlow<com.example.myapplicationkoG.ui.editor.LoadedDesign?>(null)

    fun stageDesignTyped(
        dye: Map<GarmentSideId, com.example.myapplicationkoG.ui.editor.DyeState>,
        buttons: Map<GarmentSideId, List<com.example.myapplicationkoG.ui.editor.PlacedButton>>
    ) {
        val loaded = com.example.myapplicationkoG.ui.editor.LoadedDesign(dye, buttons)
        _stagedDesign.value = loaded
        com.example.myapplicationkoG.ui.editor.DesignSession.stage(dye, buttons)
    }

    /** Consumes the staged design exactly once. */
    fun consumeStagedDesign(): com.example.myapplicationkoG.ui.editor.LoadedDesign? {
        val staged = _stagedDesign.getAndUpdate { null }
        val fallback = com.example.myapplicationkoG.ui.editor.DesignSession.consume()
        return staged ?: fallback
    }

    // Draft editor state is restored after configuration changes.
    var draftDye: Map<GarmentSideId, com.example.myapplicationkoG.ui.editor.DyeState>? = null
    var draftButtons: Map<GarmentSideId, List<com.example.myapplicationkoG.ui.editor.PlacedButton>>? = null
    var draftSide: GarmentSideId = GarmentSideId.FRONT

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

    /** Creates or retrieves the cached cutout for a local image. */
    suspend fun cutoutLocalFile(file: File): File = withContext(Dispatchers.IO) {
        val out = File(cacheDir, "cutout2_${sha256(file)}.png")
        if (out.exists() && out.length() > 0L) return@withContext out
        val result = inference.run(file)
        try {
            savePng(result.cutout, out)
        } finally {
            runCatching { if (!result.cutout.isRecycled) result.cutout.recycle() }
        }
        out
    }

    fun loadDesignPaths(front: File, back: File) {
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
        val toDelete = mutableListOf<String>()
        synchronized(pendingDeletionPaths) {
            toDelete.addAll(pendingDeletionPaths)
            pendingDeletionPaths.clear()
        }
        oldFront?.let { if (it != front.absolutePath) toDelete.add(it) }
        oldBack?.let { if (it != back.absolutePath) toDelete.add(it) }
        toDelete.forEach { path ->
            if (path != front.absolutePath && path != back.absolutePath) {
                runCatching { File(path).delete() }
            }
        }
        // Bound cached cutouts to avoid unbounded storage growth.
        runCatching {
            val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
            if (files.size > 20) {
                files.take(files.size - 20).forEach { f -> runCatching { f.delete() } }
            }
        }
    }

    /** Clears editor state and optionally deletes associated cached assets. */
    fun clearAll(deleteFiles: Boolean = false) {
        // Atomically replace state to avoid racing an in-flight image import.
        val snap = _state.getAndUpdate { GarmentInputUiState() }
        val queued: List<String> = synchronized(pendingDeletionPaths) {
            if (deleteFiles) {
                val all = pendingDeletionPaths.toList()
                pendingDeletionPaths.clear()
                all
            } else {
                snap.frontCutoutPath?.let { pendingDeletionPaths.add(it) }
                snap.backCutoutPath?.let { pendingDeletionPaths.add(it) }
                emptyList()
            }
        }
        if (deleteFiles) {
            snap.frontCutoutPath?.let { runCatching { File(it).delete() } }
            snap.backCutoutPath?.let { runCatching { File(it).delete() } }
            queued.forEach { p -> runCatching { File(p).delete() } }
        }
        openDesignId = null
        openDesignName = ""
        openDesignChallengePostId = null
        _stagedDesign.value = null
        draftDye = null
        draftButtons = null
        draftSide = GarmentSideId.FRONT
        com.example.myapplicationkoG.ui.editor.DesignSession.clear()
        clearVton()
    }

    fun clearForBack() {
        clearAll(deleteFiles = false)
    }

    /** Clears user-specific editor state during logout. */
    fun clearForLogout() {
        cancelVtonRun()
        clearChallenge()
        clearAll(deleteFiles = true)
    }

    fun clearAllExplicit() {
        clearAll(deleteFiles = true)
    }

    // The ViewModel owns try-on work so it survives configuration changes.
    val vtonRepo = VtonRepository()
    private val _vtonRunning = MutableStateFlow(false)
    val vtonRunning: StateFlow<Boolean> = _vtonRunning.asStateFlow()
    private val _vtonResultPath = MutableStateFlow<String?>(null)
    val vtonResultPath: StateFlow<String?> = _vtonResultPath.asStateFlow()
    private var vtonJob: Job? = null
    private var vtonPersonFile: File? = null

    fun startVtonRun(person: File, garment: File, onError: (String) -> Unit) {
        if (_vtonRunning.value) return
        _vtonRunning.value = true
        vtonPersonFile = person
        vtonJob = viewModelScope.launch {
            try {
                val out = vtonRepo.runTryOn(person, garment)
                runCatching { _vtonResultPath.value?.let { File(it).delete() } }
                _vtonResultPath.value = out.absolutePath
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                onError(t.message?.takeIf { it.isNotBlank() } ?: "Try-on failed")
            } finally {
                _vtonRunning.value = false
                vtonJob = null
            }
        }
    }

    /** Cancels the active try-on request and releases its network resources. */
    fun cancelVtonRun() {
        vtonJob?.cancel()
        vtonJob = null
        vtonRepo.cancelActive()
        _vtonRunning.value = false
    }

    /** Cancels the active try-on and deletes its temporary files. */
    fun clearVton() {
        cancelVtonRun()
        runCatching { vtonPersonFile?.delete() }
        runCatching { _vtonResultPath.value?.let { File(it).delete() } }
        vtonPersonFile = null
        _vtonResultPath.value = null
    }

    fun onPickedImage(side: GarmentSideId, uri: Uri) {
        // Ignore duplicate requests for the same side while serializing file operations.
        if (_state.value.loadingSide == side) return
        viewModelScope.launch {
            pickerMutex.withLock {
            _state.update {
                when (side) {
                    GarmentSideId.FRONT -> it.copy(isLoading = true, loadingSide = GarmentSideId.FRONT, frontError = null)
                    GarmentSideId.BACK -> it.copy(isLoading = true, loadingSide = GarmentSideId.BACK, backError = null)
                }
            }
            try {
                val oldPath = when (side) {
                    GarmentSideId.FRONT -> _state.value.frontCutoutPath
                    GarmentSideId.BACK -> _state.value.backCutoutPath
                }
                val cutout = withContext(Dispatchers.IO) {
                    val imported = copyToCache(uri)
                        ?: error("Could not import picked image")
                    try {
                        // A content-addressed file reuses prior cutouts safely.
                        val out = File(cacheDir, "cutout2_${sha256(imported)}.png")
                        if (!(out.exists() && out.length() > 0L)) {
                            val result = inference.run(imported)
                            try {
                                savePng(result.cutout, out)
                            } finally {
                                runCatching { if (!result.cutout.isRecycled) result.cutout.recycle() }
                            }
                        }
                        if (oldPath != null && oldPath != out.absolutePath) {
                            runCatching { File(oldPath).delete() }
                        }
                        out
                    } finally {
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
