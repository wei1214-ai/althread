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

    // Deferred deletion: back navigation must not delete the files the editor may
    // still reopen, so paths are queued here and only removed once a new design
    // loads successfully (or on explicit clear / logout). Guarded by synchronized
    // because the UI thread and IO workers both touch it; drain under the lock,
    // delete outside it so file IO never blocks a state update.
    private val pendingDeletionPaths = mutableListOf<String>()

    // Challenge fields live in StateFlow so rotation cannot lose them; the
    // ChallengeSession singleton is kept in lockstep for screens that still
    // read it directly.
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

    // Staged editor design that survives rotation (single write path; singleton is fallback).
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

    /** One-shot: ViewModel first, singleton fallback. Clears both so nothing is applied twice. */
    fun consumeStagedDesign(): com.example.myapplicationkoG.ui.editor.LoadedDesign? {
        val staged = _stagedDesign.getAndUpdate { null }
        val fallback = com.example.myapplicationkoG.ui.editor.DesignSession.consume()
        return staged ?: fallback
    }

    // ---- Editor rotation drafts: plain vars (no observation needed).
    // The editor writes its live dye/buttons/side here on every change;
    // a recreated editor (rotation) re-seeds from them when no staged design
    // is pending. Cleared by clearAll()/new staging. Undo stacks stay
    // session-local (rotation loses undo history — accepted, edits survive).
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

    /**
     * Cloud-cutout for an already-local file (Accept Challenge downloads).
     * Same cache + pipeline as photographed picks; throws on invalid photo
     * so callers can toast instead of opening a broken editor.
     */
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
        // Snapshot the paths being replaced so they can be deleted after the
        // new ones are committed.
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
        // The new design is live, so queued paths (from earlier deferred clears)
        // plus the paths just replaced are now safe to delete.
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
        // LRU: cap the cutout cache at 20 files so repeated sessions cannot grow
        // storage without bound; oldest by mtime go first.
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
        // Snapshot atomically: a plain assignment races onPickedImage's update{}
        // and can resurrect cleared state or drop a fresh cutout.
        val snap = _state.getAndUpdate { GarmentInputUiState() }
        val queued: List<String> = synchronized(pendingDeletionPaths) {
            if (deleteFiles) {
                // Explicit clear: delete immediately (current + queued).
                val all = pendingDeletionPaths.toList()
                pendingDeletionPaths.clear()
                all
            } else {
                // Defer deletion: queue current paths for deletion after next successful load.
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
        // Leaving the design orphans any in-flight try-on and its temp files.
        clearVton()
    }

    /** Back navigation: clear state but keep cache files until explicit clear or LRU. */
    fun clearForBack() {
        clearAll(deleteFiles = false)
    }

    /**
     * Account switch: wipe staged challenge/design/drafts and delete temp
     * files, so the next login on this device starts from a clean studio.
     */
    fun clearForLogout() {
        cancelVtonRun()
        clearChallenge()
        clearAll(deleteFiles = true)
    }

    /** Explicit user clear that should delete files now. */
    fun clearAllExplicit() {
        clearAll(deleteFiles = true)
    }

    // ---- VTON run state: lives here (not in the dialog) so rotation neither
    // kills a running try-on nor leaks its temp files. Dialog only observes. ----
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
        // Take ownership: dialog may die (rotation), the file must not leak.
        vtonPersonFile = person
        vtonJob = viewModelScope.launch {
            try {
                val out = vtonRepo.runTryOn(person, garment)
                runCatching { _vtonResultPath.value?.let { File(it).delete() } }
                _vtonResultPath.value = out.absolutePath
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // user cancel: silent, finally still resets state
            } catch (t: Throwable) {
                onError(t.message?.takeIf { it.isNotBlank() } ?: "Try-on failed")
            } finally {
                _vtonRunning.value = false
                vtonJob = null
            }
        }
    }

    /** User-pressed Cancel: aborts the Job and the socket - both are needed,
     *  cancelling only one leaves the other half of the run alive. */
    fun cancelVtonRun() {
        vtonJob?.cancel()
        vtonJob = null
        vtonRepo.cancelActive()
        _vtonRunning.value = false
    }

    /** Real dialog dismiss only (never on rotation): stop + delete temps. */
    fun clearVton() {
        cancelVtonRun()
        runCatching { vtonPersonFile?.delete() }
        runCatching { _vtonResultPath.value?.let { File(it).delete() } }
        vtonPersonFile = null
        _vtonResultPath.value = null
    }

    fun onPickedImage(side: GarmentSideId, uri: Uri) {
        // Per-side gate rather than a global isLoading: a FRONT inference must not
        // swallow a BACK pick, while a double-tap on the same side is still
        // ignored. The mutex serializes the two sides; the second waiter blocks
        // and then runs instead of being dropped.
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
                // Previous cutout for this side, deleted only once the new one is saved.
                val oldPath = when (side) {
                    GarmentSideId.FRONT -> _state.value.frontCutoutPath
                    GarmentSideId.BACK -> _state.value.backCutoutPath
                }
                val cutout = withContext(Dispatchers.IO) {
                    val imported = copyToCache(uri)
                        ?: error("Could not import picked image")
                    try {
                        // Same bytes always map to the same cutout, so the cache hit
                        // skips inference entirely. The key carries a version prefix:
                        // bump it when the pipeline changes so stale results from the
                        // old model are never reused.
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