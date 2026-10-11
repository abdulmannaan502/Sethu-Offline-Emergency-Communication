package com.sethu.emergency.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAiExtractorTest {

    private val extractor = OfflineAiExtractor(CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun testFalsePositiveMedicalEmergencyIsPrevented() {
        val input = "there's a flood near the bridge. three people are trapped, including one child"
        // Simulate model returning false positive medicalEmergency = true
        val modelOutput = """
            {
              "category": "Flood",
              "peopleCount": "3",
              "childrenInvolved": true,
              "elderlyInvolved": null,
              "medicalEmergency": true
            }
        """.trimIndent()

        val result = extractor.parseStructuredJson(modelOutput, input)

        requireNotNull(result)
        assertEquals("Flood", result.category)
        assertEquals("3", result.peopleCount)
        assertEquals(true, result.childrenInvolved)
        assertNull("elderlyInvolved must be null", result.elderlyInvolved)
        // CRITICAL CHECK: medicalEmergency MUST be corrected to null (not true)
        assertNull("medicalEmergency must be null when input has no medical mentions", result.medicalEmergency)
    }

    @Test
    fun testExplicitMedicalEmergencyIsPreserved() {
        val input = "car accident on highway, two injured victims bleeding heavily"
        val modelOutput = """
            {
              "category": "Medical",
              "peopleCount": "2",
              "childrenInvolved": null,
              "elderlyInvolved": null,
              "medicalEmergency": true
            }
        """.trimIndent()

        val result = extractor.parseStructuredJson(modelOutput, input)

        requireNotNull(result)
        assertEquals("Medical", result.category)
        assertEquals(true, result.medicalEmergency)
    }

    @Test
    fun testExplicitNoMedicalEmergencyPreserved() {
        val input = "building locked from outside, no injuries or medical issues"
        val modelOutput = """
            {
              "category": "Trapped",
              "peopleCount": "1",
              "childrenInvolved": null,
              "elderlyInvolved": null,
              "medicalEmergency": false
            }
        """.trimIndent()

        val result = extractor.parseStructuredJson(modelOutput, input)

        requireNotNull(result)
        assertEquals("Trapped", result.category)
        assertEquals(false, result.medicalEmergency)
    }
}
