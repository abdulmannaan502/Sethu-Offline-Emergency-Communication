package com.sethu.emergency.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaConfig
import org.codeshipping.llamakotlin.LlamaModel
import org.json.JSONObject
import java.io.File

/**
 * Structured emergency proposal extracted by the local LLM.
 * Null represents "unknown / not explicitly mentioned" so facts are never fabricated.
 */
data class ProposedExtraction(
    val category: String, // Flood, Medical, Fire, Trapped, Injury, Other
    val peopleCount: String?, // 1, 2, 3, 4+, or null if unknown
    val childrenInvolved: Boolean?, // null if unknown / not mentioned
    val elderlyInvolved: Boolean?,  // null if unknown / not mentioned
    val medicalEmergency: Boolean?, // null if unknown / not mentioned
    val rawModelResponse: String = "",
    val originalNotes: String = ""
)

sealed class AiExtractionState {
    object Idle : AiExtractionState()
    object ModelNotFound : AiExtractionState()
    object LoadingModel : AiExtractionState()
    data class Generating(val partialText: String = "") : AiExtractionState()
    data class Success(val proposal: ProposedExtraction) : AiExtractionState()
    data class Error(val message: String) : AiExtractionState()
}

/**
 * Fully offline LLM-powered emergency detail extractor.
 * Operates strictly locally using llama.cpp JNI bindings with Qwen2.5-0.5B-Instruct Q4_K_M.
 * Zero network, zero cloud, zero telemetry.
 */
class OfflineAiExtractor(
    private val scope: CoroutineScope
) {

    companion object {
        const val MODEL_FILENAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
        private val VALID_CATEGORIES = listOf("Flood", "Medical", "Fire", "Trapped", "Injury", "Other")
        private val VALID_COUNTS = listOf("1", "2", "3", "4+")
    }

    private val _state = MutableStateFlow<AiExtractionState>(AiExtractionState.Idle)
    val state: StateFlow<AiExtractionState> = _state.asStateFlow()

    private var llamaModel: LlamaModel? = null
    @Volatile private var isInitializing = false
    @Volatile private var isExtracting = false
    /** Bumped by [release]; a model that finishes loading for an older generation is closed instead of leaked. */
    @Volatile private var generation = 0

    /**
     * Resolves the offline model file location in app-private external or internal directories.
     */
    fun findModelFile(context: Context): File? {
        val candidates = listOf(
            File(context.getExternalFilesDir("models"), MODEL_FILENAME),
            File(context.filesDir, "models/$MODEL_FILENAME"),
            File(context.getExternalFilesDir(null), MODEL_FILENAME),
            File(context.filesDir, MODEL_FILENAME),
            File("/sdcard/Android/data/${context.packageName}/files/models/$MODEL_FILENAME"),
            File("/sdcard/Download/$MODEL_FILENAME")
        )
        return candidates.firstOrNull { it.exists() && it.length() > 1000 }
    }

    /**
     * Expected path description for user diagnostics.
     */
    fun getExpectedModelPath(context: Context): String {
        val target = File(context.getExternalFilesDir("models"), MODEL_FILENAME)
        return target.absolutePath
    }

    /**
     * Check if model file exists on disk.
     */
    fun isModelFilePresent(context: Context): Boolean {
        return findModelFile(context) != null
    }

    /**
     * Pre-loads the model in background coroutine if present.
     */
    fun ensureModelLoaded(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        if (llamaModel != null) {
            onComplete?.invoke(true)
            return
        }
        if (isInitializing) return

        val file = findModelFile(context)
        if (file == null) {
            _state.value = AiExtractionState.ModelNotFound
            onComplete?.invoke(false)
            return
        }

        isInitializing = true
        _state.value = AiExtractionState.LoadingModel
        val myGeneration = generation

        scope.launch(Dispatchers.IO) {
            try {
                val config = LlamaConfig().apply {
                    contextSize = 2048
                    threads = 4
                    temperature = 0.1f
                    topP = 0.9f
                    maxTokens = 256
                    stopSequences = listOf("<|im_end|>", "<|endoftext|>", "\n\n\n")
                    useMmap = true
                }
                val loaded = LlamaModel.load(file.absolutePath, config)
                if (generation != myGeneration) {
                    try { loaded.close() } catch (_: Exception) {}
                    return@launch
                }
                llamaModel = loaded
                _state.value = AiExtractionState.Idle
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(true)
                }
            } catch (e: Exception) {
                _state.value = AiExtractionState.Error("Failed to load model: ${e.localizedMessage ?: "Native error"}")
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false)
                }
            } finally {
                isInitializing = false
            }
        }
    }

    /**
     * Performs structured extraction on user emergency text.
     * Always executes on background thread.
     */
    fun extractEmergencyDetails(context: Context, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            _state.value = AiExtractionState.Error("Emergency description is empty. Please enter or record notes first.")
            return
        }

        val file = findModelFile(context)
        if (file == null) {
            _state.value = AiExtractionState.ModelNotFound
            return
        }
        // One extraction at a time: repeated taps must not load the native model twice or race on state.
        if (isExtracting || isInitializing) return
        isExtracting = true
        val myGeneration = generation

        scope.launch(Dispatchers.IO) {
            try {
                // Ensure model is loaded
                var model = llamaModel
                if (model == null) {
                    _state.value = AiExtractionState.LoadingModel
                    val config = LlamaConfig().apply {
                        contextSize = 2048
                        threads = 4
                        temperature = 0.1f
                        topP = 0.9f
                        maxTokens = 256
                        stopSequences = listOf("<|im_end|>", "<|endoftext|>", "\n\n\n")
                        useMmap = true
                    }
                    model = LlamaModel.load(file.absolutePath, config)
                    if (generation != myGeneration) {
                        try { model.close() } catch (_: Exception) {}
                        return@launch
                    }
                    llamaModel = model
                }

                _state.value = AiExtractionState.Generating("")

                val prompt = buildExtractionPrompt(trimmed)
                val generationConfig = LlamaConfig().apply {
                    contextSize = 2048
                    threads = 4
                    temperature = 0.1f
                    topP = 0.9f
                    maxTokens = 256
                    stopSequences = listOf("<|im_end|>", "<|endoftext|>")
                }

                val result = model.generate(prompt, generationConfig)
                val parsedProposal = parseStructuredJson(result, trimmed)

                if (parsedProposal != null) {
                    _state.value = AiExtractionState.Success(parsedProposal)
                } else {
                    // Fallback parse if JSON had formatting quirks
                    val fallback = parseFallback(result, trimmed)
                    _state.value = AiExtractionState.Success(fallback)
                }

            } catch (e: Exception) {
                _state.value = AiExtractionState.Error("Extraction failed: ${e.localizedMessage ?: "Unknown error"}")
            } finally {
                isExtracting = false
            }
        }
    }

    private fun buildExtractionPrompt(description: String): String {
        return """<|im_start|>system
You are a precise emergency triage AI assistant. Extract emergency details from the user's description into a strict JSON object.
Rules:
- Only extract facts directly mentioned in the description. Never guess, assume, or invent facts.
- "category": must be one of "Flood", "Medical", "Fire", "Trapped", "Injury", "Other".
- "peopleCount": must be one of "1", "2", "3", "4+", or null if unknown.
- "childrenInvolved": true if children/kids/babies explicitly mentioned, false if explicitly stated none, null if unknown/not mentioned.
- "elderlyInvolved": true if elderly/grandparents/seniors explicitly mentioned, false if explicitly stated none, null if unknown/not mentioned.
- "medicalEmergency": true ONLY if the text explicitly indicates a medical emergency, medical condition, injury requiring medical help, ambulance, hospital, doctor, or need for medical assistance. Do NOT infer a medical emergency merely because the situation is dangerous, someone is trapped, or there is a flood, fire, or disaster. If no injuries, medical conditions, or medical needs are explicitly mentioned, medicalEmergency MUST be null.
Output JSON ONLY in this exact schema:
{"category":"...","peopleCount":"...","childrenInvolved":...,"elderlyInvolved":...,"medicalEmergency":...}<|im_end|>
<|im_start|>user
$description<|im_end|>
<|im_start|>assistant
"""
    }

    /**
     * Checks whether the user's description explicitly indicates a medical condition,
     * injury, or need for medical assistance.
     */
    private fun hasExplicitMedicalMention(text: String): Boolean {
        val lower = text.lowercase()
        val medicalKeywords = listOf(
            "medic", "doctor", "ambulance", "hospital", "paramedic", "nurse", "first aid", "cpr",
            "injur", "wound", "bleed", "unconscious", "heart attack", "cardiac", "stroke",
            "broken bone", "fracture", "burn", "trauma", "choking", "seizure", "concussion",
            "passed out", "fainted", "critical condition", "dying", "severe pain", "hurt",
            "casualt", "patient", "blood", "head injury"
        )
        return medicalKeywords.any { lower.contains(it) }
    }

    private fun hasExplicitChildMention(text: String): Boolean {
        val lower = text.lowercase()
        val childKeywords = listOf("child", "children", "kid", "kids", "baby", "babies", "infant", "toddler", "minor", "son", "daughter", "boy", "girl")
        return childKeywords.any { lower.contains(it) }
    }

    private fun hasExplicitElderlyMention(text: String): Boolean {
        val lower = text.lowercase()
        val elderlyKeywords = listOf("elderly", "senior", "grandparent", "grandfather", "grandmother", "grandma", "grandpa", "old man", "old woman", "old person", "aged", "geriatric", "pensioner")
        return elderlyKeywords.any { lower.contains(it) }
    }

    /**
     * Robustly extracts and parses JSON from the model generation.
     */
    internal fun parseStructuredJson(rawOutput: String, originalNotes: String): ProposedExtraction? {
        try {
            val trimmed = rawOutput.trim()
            val startIdx = trimmed.indexOf('{')
            val endIdx = trimmed.lastIndexOf('}')
            if (startIdx == -1 || endIdx == -1 || endIdx <= startIdx) {
                return null
            }
            val jsonStr = trimmed.substring(startIdx, endIdx + 1)
            val json = JSONObject(jsonStr)

            // Category validation
            val rawCategory = json.optString("category", "Other")
            val matchedCategory = VALID_CATEGORIES.firstOrNull {
                it.equals(rawCategory, ignoreCase = true)
            } ?: "Other"

            // People count validation
            val rawCount = if (json.has("peopleCount") && !json.isNull("peopleCount")) {
                val str = json.optString("peopleCount")
                when {
                    str == "1" || str.contains("one", ignoreCase = true) -> "1"
                    str == "2" || str.contains("two", ignoreCase = true) -> "2"
                    str == "3" || str.contains("three", ignoreCase = true) -> "3"
                    str == "4+" || str.toIntOrNull()?.let { it >= 4 } == true -> "4+"
                    VALID_COUNTS.contains(str) -> str
                    else -> null
                }
            } else null

            // Nullable booleans with conservative validation to prevent false positives
            val rawChildren = if (json.has("childrenInvolved") && !json.isNull("childrenInvolved")) {
                json.optBoolean("childrenInvolved")
            } else null
            val children = if (rawChildren == true && !hasExplicitChildMention(originalNotes)) null else rawChildren

            val rawElderly = if (json.has("elderlyInvolved") && !json.isNull("elderlyInvolved")) {
                json.optBoolean("elderlyInvolved")
            } else null
            val elderly = if (rawElderly == true && !hasExplicitElderlyMention(originalNotes)) null else rawElderly

            val rawMedical = if (json.has("medicalEmergency") && !json.isNull("medicalEmergency")) {
                json.optBoolean("medicalEmergency")
            } else null
            // Never infer a medical emergency unless the text explicitly indicates medical help/condition/injury
            val medical = if (rawMedical == true && !hasExplicitMedicalMention(originalNotes)) null else rawMedical

            return ProposedExtraction(
                category = matchedCategory,
                peopleCount = rawCount,
                childrenInvolved = children,
                elderlyInvolved = elderly,
                medicalEmergency = medical,
                rawModelResponse = trimmed,
                originalNotes = originalNotes
            )
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Fallback parser if JSON object brackets were malformed.
     */
    private fun parseFallback(rawOutput: String, originalNotes: String): ProposedExtraction {
        val lower = rawOutput.lowercase()
        val cat = VALID_CATEGORIES.firstOrNull { lower.contains(it.lowercase()) } ?: "Other"
        val count = when {
            lower.contains("peoplecount\": \"1\"") || lower.contains("\"1\"") -> "1"
            lower.contains("peoplecount\": \"2\"") || lower.contains("\"2\"") -> "2"
            lower.contains("peoplecount\": \"3\"") || lower.contains("\"3\"") -> "3"
            lower.contains("peoplecount\": \"4+\"") || lower.contains("\"4+\"") -> "4+"
            else -> null
        }
        val rawChildren = if (lower.contains("childreninvolved\": true") || lower.contains("\"childreninvolved\":true")) true
                          else if (lower.contains("childreninvolved\": false") || lower.contains("\"childreninvolved\":false")) false
                          else null
        val children = if (rawChildren == true && !hasExplicitChildMention(originalNotes)) null else rawChildren

        val rawElderly = if (lower.contains("elderlyinvolved\": true") || lower.contains("\"elderlyinvolved\":true")) true
                         else if (lower.contains("elderlyinvolved\": false") || lower.contains("\"elderlyinvolved\":false")) false
                         else null
        val elderly = if (rawElderly == true && !hasExplicitElderlyMention(originalNotes)) null else rawElderly

        val rawMedical = if (lower.contains("medicalemergency\": true") || lower.contains("\"medicalemergency\":true")) true
                         else if (lower.contains("medicalemergency\": false") || lower.contains("\"medicalemergency\":false")) false
                         else null
        val medical = if (rawMedical == true && !hasExplicitMedicalMention(originalNotes)) null else rawMedical

        return ProposedExtraction(
            category = cat,
            peopleCount = count,
            childrenInvolved = children,
            elderlyInvolved = elderly,
            medicalEmergency = medical,
            rawModelResponse = rawOutput,
            originalNotes = originalNotes
        )
    }


    fun resetState() {
        _state.value = AiExtractionState.Idle
    }

    fun release() {
        generation++
        try {
            llamaModel?.close()
        } catch (e: Exception) {
            // Ignore close exceptions
        } finally {
            llamaModel = null
            _state.value = AiExtractionState.Idle
        }
    }
}
