package com.sethu.emergency.comm.coordinator

import com.sethu.emergency.comm.model.SosReport

/**
 * Deterministic emergency incident priority levels.
 * Rank 1 is highest priority.
 */
enum class IncidentPriority(val rank: Int, val label: String) {
    CRITICAL(1, "Critical"),
    HIGH(2, "High"),
    MEDIUM(3, "Medium"),
    UNKNOWN(4, "Unknown")
}

/**
 * Detailed outcome of deterministic priority evaluation.
 */
data class PriorityEvaluation(
    val priority: IncidentPriority,
    val reason: String,
    val explicitHazards: List<String> = emptyList()
)

/**
 * Deterministic, explainable emergency priority classifier.
 *
 * Evaluation rules:
 * - CRITICAL: Explicit immediate life-threatening conditions (e.g. unconscious, not breathing, cardiac arrest, severe blood loss, drowning, suffocating).
 * - HIGH: Explicitly reported trapped persons, serious injuries, children in immediate hazard, active fire emergencies, or urgent medical emergencies.
 * - MEDIUM: Reported emergency requiring assistance without evidence of immediate life-threatening danger (e.g. general flood, elderly evacuation assistance, other incidents).
 * - UNKNOWN: Insufficient or contradictory information to classify confidently.
 *
 * Conflict resolution:
 * When multiple hazards exist, the classifier preserves the most serious explicitly supported hazard (CRITICAL > HIGH > MEDIUM > UNKNOWN).
 *
 * Strict offline constraint:
 * Rule-based, deterministic, zero LLM, zero network calls.
 */
object PriorityClassifier {

    // Keywords that indicate explicit immediate life-threatening danger
    private val CRITICAL_KEYWORDS = listOf(
        "unconscious",
        "unresponsive",
        "not breathing",
        "cardiac arrest",
        "heart attack",
        "severe bleeding",
        "massive bleeding",
        "arterial bleeding",
        "suffocating",
        "asphyxiation",
        "drowning",
        "critical condition",
        "life-threatening",
        "fatal",
        "severe head trauma",
        "third-degree burn"
    )

    // Keywords that indicate trapped conditions
    private val TRAPPED_KEYWORDS = listOf(
        "trapped",
        "buried",
        "pinned",
        "under rubble",
        "under debris",
        "stuck",
        "stranded on roof"
    )

    // Keywords that indicate serious injury
    private val INJURY_KEYWORDS = listOf(
        "injury",
        "injured",
        "broken bone",
        "fracture",
        "burn",
        "bleeding",
        "wound",
        "concussion"
    )

    fun evaluate(report: SosReport): PriorityEvaluation {
        val notesLower = report.notes.lowercase()
        val categoryLower = report.category.lowercase().trim()
        val detectedHazards = mutableListOf<String>()

        // 1. Check for explicit CRITICAL conditions (immediate life-threatening)
        val matchedCriticalTerm = CRITICAL_KEYWORDS.firstOrNull { notesLower.contains(it) }
        if (matchedCriticalTerm != null) {
            detectedHazards.add("Immediate life-threatening condition ($matchedCriticalTerm)")
            return PriorityEvaluation(
                priority = IncidentPriority.CRITICAL,
                reason = "Explicit immediate life-threatening danger reported: $matchedCriticalTerm.",
                explicitHazards = detectedHazards
            )
        }

        // 2. Identify all explicit hazards present in the report
        val isTrapped = categoryLower == "trapped" || TRAPPED_KEYWORDS.any { notesLower.contains(it) }
        if (isTrapped) detectedHazards.add("Trapped individuals reported")

        val isSeriousInjury = categoryLower == "injury" || INJURY_KEYWORDS.any { notesLower.contains(it) }
        if (isSeriousInjury) detectedHazards.add("Reported injury requiring urgent medical care")

        val isFire = categoryLower == "fire" || notesLower.contains("fire") || notesLower.contains("explosion")
        if (isFire) detectedHazards.add("Active fire emergency")

        val isMedical = categoryLower == "medical" || report.medicalEmergency
        if (isMedical) detectedHazards.add("Urgent medical emergency")

        val hasChildrenInHazard = report.childrenInvolved && (
                isTrapped || isSeriousInjury || isFire || isMedical || categoryLower == "flood" || notesLower.isNotBlank()
                )
        if (hasChildrenInHazard) detectedHazards.add("Children involved in active emergency")

        // 3. Check for HIGH Priority
        if (isTrapped || isSeriousInjury || hasChildrenInHazard || isFire || isMedical) {
            val primaryReason = when {
                isTrapped -> "Explicitly reported trapped individuals."
                hasChildrenInHazard -> "Children in immediate emergency danger."
                isSeriousInjury -> "Explicitly reported injuries requiring urgent medical intervention."
                isFire -> "Active fire hazard reported."
                isMedical -> "Reported urgent medical emergency requiring intervention."
                else -> "High-severity emergency hazard reported."
            }

            val fullReason = if (detectedHazards.size > 1) {
                "$primaryReason Additional hazards: ${detectedHazards.joinToString("; ")}."
            } else {
                primaryReason
            }

            return PriorityEvaluation(
                priority = IncidentPriority.HIGH,
                reason = fullReason,
                explicitHazards = detectedHazards
            )
        }

        // 4. Check for MEDIUM Priority
        val isFlood = categoryLower == "flood" || notesLower.contains("flood") || notesLower.contains("water")
        if (isFlood) {
            detectedHazards.add("Flood situation")
            return PriorityEvaluation(
                priority = IncidentPriority.MEDIUM,
                reason = "Flood disaster requiring response and evacuation assistance.",
                explicitHazards = detectedHazards
            )
        }

        if (report.elderlyInvolved) {
            detectedHazards.add("Elderly assistance requested")
            return PriorityEvaluation(
                priority = IncidentPriority.MEDIUM,
                reason = "Elderly individuals requiring assistance in emergency area.",
                explicitHazards = detectedHazards
            )
        }

        val hasPeopleAffected = report.peopleCount.isNotBlank() && report.peopleCount != "0"
        val hasCategory = categoryLower.isNotBlank() && categoryLower != "unknown" && categoryLower != "none"
        val hasNotes = report.notes.isNotBlank()

        if (hasCategory && (hasPeopleAffected || hasNotes)) {
            val catDisplay = report.category.ifBlank { "General incident" }
            detectedHazards.add("$catDisplay assistance")
            return PriorityEvaluation(
                priority = IncidentPriority.MEDIUM,
                reason = "Reported emergency requiring response assistance ($catDisplay).",
                explicitHazards = detectedHazards
            )
        }

        // 5. UNKNOWN Priority: Insufficient or contradictory information
        return PriorityEvaluation(
            priority = IncidentPriority.UNKNOWN,
            reason = "Insufficient or unspecific incident information to classify priority safely.",
            explicitHazards = emptyList()
        )
    }
}
