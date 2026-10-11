package com.sethu.emergency.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException

sealed class SpeechState {
    object Uninitialized : SpeechState()
    object LoadingModel : SpeechState()
    object Ready : SpeechState()
    object Listening : SpeechState()
    data class Error(val message: String) : SpeechState()
}

/**
 * Robust, fully offline Speech-to-Text manager powered by Vosk.
 * Operates with 100% offline local model execution (zero cloud, zero internet).
 */
class OfflineSpeechManager(
    private val scope: CoroutineScope
) : RecognitionListener {

    companion object {
        private const val MODEL_ASSET_DIR = "model-en-in"
        private const val SAMPLE_RATE = 16000.0f
    }

    private val _state = MutableStateFlow<SpeechState>(SpeechState.Uninitialized)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _finalText = MutableStateFlow("")
    val finalText: StateFlow<String> = _finalText.asStateFlow()

    private var model: Model? = null
    /** Bumped by [release]; a model that finishes loading for an older generation is closed instead of leaked. */
    @Volatile private var generation = 0
    private var speechService: SpeechService? = null

    /**
     * Initialize model in background without blocking UI thread.
     */
    fun initModel(context: Context) {
        if (_state.value is SpeechState.Ready || _state.value is SpeechState.Listening || _state.value is SpeechState.LoadingModel) {
            return
        }

        _state.value = SpeechState.LoadingModel
        val myGeneration = generation

        scope.launch(Dispatchers.IO) {
            try {
                // Check if external model exists or unpack from bundled assets
                val targetDir = File(context.filesDir, MODEL_ASSET_DIR)
                val externalDir = context.getExternalFilesDir(null)?.resolve(MODEL_ASSET_DIR)

                val modelDir = when {
                    externalDir != null && externalDir.exists() && File(externalDir, "am").exists() -> externalDir
                    targetDir.exists() && File(targetDir, "am").exists() -> targetDir
                    else -> {
                        // Unpack from assets
                        copyAssetFolder(context.assets, MODEL_ASSET_DIR, targetDir)
                        if (targetDir.exists() && File(targetDir, "am").exists()) {
                            targetDir
                        } else {
                            null
                        }
                    }
                }

                if (modelDir == null) {
                    _state.value = SpeechState.Error("Offline speech model files not found. Manual SOS entry is active.")
                    return@launch
                }

                val loadedModel = Model(modelDir.absolutePath)
                if (generation != myGeneration) {
                    try { loadedModel.close() } catch (_: Exception) {}
                    return@launch
                }
                model = loadedModel
                _state.value = SpeechState.Ready
            } catch (e: Exception) {
                _state.value = SpeechState.Error("Failed to initialize speech engine: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    fun hasRecordPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start listening for speech input.
     */
    fun startListening(context: Context): Boolean {
        if (!hasRecordPermission(context)) {
            _state.value = SpeechState.Error("Microphone permission denied.")
            return false
        }

        val loadedModel = model
        if (loadedModel == null) {
            _state.value = SpeechState.Error("Speech model is not ready.")
            return false
        }

        try {
            stopListening()

            val recognizer = Recognizer(loadedModel, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            speechService = service
            service.startListening(this)

            _partialText.value = ""
            _state.value = SpeechState.Listening
            return true
        } catch (e: IOException) {
            _state.value = SpeechState.Error("Microphone initialization error: ${e.localizedMessage}")
            return false
        } catch (e: Exception) {
            _state.value = SpeechState.Error("Recognition startup failed: ${e.localizedMessage}")
            return false
        }
    }

    /**
     * Explicitly stop recording and finalize transcription.
     */
    fun stopListening() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (_: Exception) {}
        speechService = null

        if (_state.value is SpeechState.Listening) {
            _state.value = SpeechState.Ready
        }
    }

    fun cancel() {
        try {
            speechService?.cancel()
            speechService?.shutdown()
        } catch (_: Exception) {}
        speechService = null
        _partialText.value = ""
        if (_state.value is SpeechState.Listening) {
            _state.value = SpeechState.Ready
        }
    }

    fun clearTranscriptions() {
        _partialText.value = ""
        _finalText.value = ""
    }

    fun setFinalText(text: String) {
        _finalText.value = text
    }

    fun release() {
        generation++
        cancel()
        try {
            model?.close()
        } catch (_: Exception) {}
        model = null
        _state.value = SpeechState.Uninitialized
    }

    // RecognitionListener Callbacks
    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        try {
            val json = JSONObject(hypothesis)
            val partial = json.optString("partial", "").trim()
            if (partial.isNotBlank()) {
                _partialText.value = partial
            }
        } catch (_: Exception) {}
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        try {
            val json = JSONObject(hypothesis)
            val text = json.optString("text", "").trim()
            if (text.isNotBlank()) {
                val current = _finalText.value
                _finalText.value = if (current.isBlank()) text else "$current $text"
                _partialText.value = ""
            }
        } catch (_: Exception) {}
    }

    override fun onFinalResult(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        try {
            val json = JSONObject(hypothesis)
            val text = json.optString("text", "").trim()
            if (text.isNotBlank()) {
                val current = _finalText.value
                _finalText.value = if (current.isBlank()) text else "$current $text"
                _partialText.value = ""
            }
        } catch (_: Exception) {}
        _state.value = SpeechState.Ready
    }

    override fun onError(exception: java.lang.Exception?) {
        _state.value = SpeechState.Error("Audio recognition error: ${exception?.localizedMessage ?: "Unknown"}")
        stopListening()
    }

    override fun onTimeout() {
        stopListening()
    }

    private fun copyAssetFolder(assetManager: AssetManager, fromAssetPath: String, toFile: File) {
        val files = assetManager.list(fromAssetPath) ?: return
        if (!toFile.exists()) toFile.mkdirs()
        for (file in files) {
            val subAssetPath = if (fromAssetPath.isEmpty()) file else "$fromAssetPath/$file"
            val subFiles = assetManager.list(subAssetPath)
            if (subFiles != null && subFiles.isNotEmpty()) {
                copyAssetFolder(assetManager, subAssetPath, File(toFile, file))
            } else {
                val destFile = File(toFile, file)
                if (!destFile.exists() || destFile.length() == 0L) {
                    assetManager.open(subAssetPath).use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }
}
