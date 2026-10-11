package com.sethu.emergency.comm.coordinator

import com.sethu.emergency.comm.model.SosReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class IncidentPriorityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: CoordinatorReportStore

    @Before
    fun setup() {
        store = CoordinatorReportStore(tempFolder.root)
    }

    private fun createReport(
        id: String = UUID.randomUUID().toString(),
        category: String = "Other",
        notes: String = "",
        peopleCount: String = "1",
        childrenInvolved: Boolean = false,
        elderlyInvolved: Boolean = false,
        medicalEmergency: Boolean = false,
        latitude: Double? = null,
        longitude: Double? = null,
        timestamp: Long = System.currentTimeMillis()
    ): SosReport {
        return SosReport(
            reportId = id,
            timestamp = timestamp,
            category = category,
            notes = notes,
            peopleCount = peopleCount,
            childrenInvolved = childrenInvolved,
            elderlyInvolved = elderlyInvolved,
            medicalEmergency = medicalEmergency,
            latitude = latitude,
            longitude = longitude,
            senderDeviceName = "TestDevice"
        )
    }

    // 1. Explicit life-threatening conditions
    @Test
    fun testExplicitLifeThreateningConditionsClassifiedAsCritical() {
        val reportUnconscious = createReport(
            category = "Medical",
            notes = "Elderly person fell and is currently unconscious",
            medicalEmergency = true
        )
        val eval1 = PriorityClassifier.evaluate(reportUnconscious)
        assertEquals(IncidentPriority.CRITICAL, eval1.priority)
        assertTrue(eval1.reason.contains("unconscious", ignoreCase = true))

        val reportCardiac = createReport(
            category = "Medical",
            notes = "Patient experiencing sudden cardiac arrest, needs CPR",
            medicalEmergency = true
        )
        val eval2 = PriorityClassifier.evaluate(reportCardiac)
        assertEquals(IncidentPriority.CRITICAL, eval2.priority)
        assertTrue(eval2.reason.contains("cardiac arrest", ignoreCase = true))

        val reportDrowning = createReport(
            category = "Flood",
            notes = "Two people drowning in raging canal water"
        )
        val eval3 = PriorityClassifier.evaluate(reportDrowning)
        assertEquals(IncidentPriority.CRITICAL, eval3.priority)
        assertTrue(eval3.reason.contains("drowning", ignoreCase = true))
    }

    // 2. Trapped-person and serious-injury classification
    @Test
    fun testTrappedAndSeriousInjuryClassifiedAsHigh() {
        val reportTrapped = createReport(
            category = "Trapped",
            notes = "Two people trapped inside elevator on 3rd floor",
            peopleCount = "2"
        )
        val evalTrapped = PriorityClassifier.evaluate(reportTrapped)
        assertEquals(IncidentPriority.HIGH, evalTrapped.priority)
        assertTrue(evalTrapped.reason.contains("trapped", ignoreCase = true))

        val reportInjury = createReport(
            category = "Injury",
            notes = "Severe compound leg fracture from wall collapse",
            peopleCount = "1"
        )
        val evalInjury = PriorityClassifier.evaluate(reportInjury)
        assertEquals(IncidentPriority.HIGH, evalInjury.priority)
        assertTrue(evalInjury.reason.contains("injur", ignoreCase = true))

        val reportChildren = createReport(
            category = "Flood",
            notes = "Water entering house, 2 children trapped on upper level",
            childrenInvolved = true
        )
        val evalChildren = PriorityClassifier.evaluate(reportChildren)
        assertEquals(IncidentPriority.HIGH, evalChildren.priority)
        assertTrue(evalChildren.reason.contains("Children", ignoreCase = true))
    }

    // 3. Missing or unknown fields
    @Test
    fun testMissingOrUnknownFieldsClassifiedAsUnknown() {
        val blankReport = createReport(
            category = "Unknown",
            notes = "",
            peopleCount = "",
            childrenInvolved = false,
            elderlyInvolved = false,
            medicalEmergency = false
        )
        val eval = PriorityClassifier.evaluate(blankReport)
        assertEquals(IncidentPriority.UNKNOWN, eval.priority)
        assertTrue(eval.reason.contains("Insufficient", ignoreCase = true))
    }

    // 4. Multiple hazards in one report preserves most serious hazard
    @Test
    fun testMultipleHazardsPreservesMostSeriousHazard() {
        // Hazard 1: Flood (Medium) + Hazard 2: Trapped (High) -> High
        val floodAndTrapped = createReport(
            category = "Flood",
            notes = "Flood water rose 4 feet, 3 people are trapped inside basement",
            peopleCount = "3"
        )
        val evalHigh = PriorityClassifier.evaluate(floodAndTrapped)
        assertEquals(IncidentPriority.HIGH, evalHigh.priority)
        assertTrue(evalHigh.reason.contains("trapped", ignoreCase = true))

        // Hazard 1: Fire (High) + Hazard 2: Trapped (High) + Hazard 3: Unconscious (Critical) -> Critical
        val fireAndTrappedAndUnconscious = createReport(
            category = "Fire",
            notes = "Factory fire, 2 workers trapped and one is unconscious from smoke",
            peopleCount = "2"
        )
        val evalCritical = PriorityClassifier.evaluate(fireAndTrappedAndUnconscious)
        assertEquals(IncidentPriority.CRITICAL, evalCritical.priority)
        assertTrue(evalCritical.reason.contains("unconscious", ignoreCase = true))
    }

    // 5. Priority ordering: Priority first (Critical -> High -> Medium -> Unknown), then by recency
    @Test
    fun testPriorityOrderingFollowedByRecency() {
        val now = System.currentTimeMillis()

        val rMediumOld = createReport(id = "M1", category = "Flood", timestamp = now - 5000)
        val rMediumNew = createReport(id = "M2", category = "Flood", timestamp = now)
        val rCritical = createReport(id = "C1", category = "Medical", notes = "Person unconscious", timestamp = now - 10000)
        val rHigh = createReport(id = "H1", category = "Trapped", timestamp = now - 8000)
        val rUnknown = createReport(id = "U1", category = "Unknown", notes = "", peopleCount = "", timestamp = now + 1000)

        val unsortedList = listOf(rMediumOld, rUnknown, rHigh, rMediumNew, rCritical)

        val sortedList = unsortedList.sortedWith(
            compareBy<SosReport> { PriorityClassifier.evaluate(it).priority.rank }
                .thenByDescending { it.timestamp }
        )

        assertEquals("C1", sortedList[0].reportId) // Critical (rank 1)
        assertEquals("H1", sortedList[1].reportId) // High (rank 2)
        assertEquals("M2", sortedList[2].reportId) // Medium newer
        assertEquals("M1", sortedList[3].reportId) // Medium older
        assertEquals("U1", sortedList[4].reportId) // Unknown (rank 4)
    }

    // 6. Filtering by priority
    @Test
    fun testFilteringByPriority() {
        val rCritical = createReport(category = "Medical", notes = "unconscious victim")
        val rHigh = createReport(category = "Trapped", peopleCount = "2")
        val rMedium = createReport(category = "Flood", notes = "road flooded")
        val rUnknown = createReport(category = "Unknown", notes = "", peopleCount = "")

        val allReports = listOf(rCritical, rHigh, rMedium, rUnknown)

        val criticalList = allReports.filter { PriorityClassifier.evaluate(it).priority == IncidentPriority.CRITICAL }
        assertEquals(1, criticalList.size)
        assertEquals(rCritical.reportId, criticalList[0].reportId)

        val highList = allReports.filter { PriorityClassifier.evaluate(it).priority == IncidentPriority.HIGH }
        assertEquals(1, highList.size)
        assertEquals(rHigh.reportId, highList[0].reportId)

        val mediumList = allReports.filter { PriorityClassifier.evaluate(it).priority == IncidentPriority.MEDIUM }
        assertEquals(1, mediumList.size)
        assertEquals(rMedium.reportId, mediumList[0].reportId)

        val unknownList = allReports.filter { PriorityClassifier.evaluate(it).priority == IncidentPriority.UNKNOWN }
        assertEquals(1, unknownList.size)
        assertEquals(rUnknown.reportId, unknownList[0].reportId)
    }

    // 7. Duplicate report IDs
    @Test
    fun testDuplicateReportIdsDeduplicated() {
        val id = UUID.randomUUID().toString()
        val r1 = createReport(id = id, category = "Fire", notes = "Warehouse fire")
        val r2 = createReport(id = id, category = "Fire", notes = "Warehouse fire duplicate")

        val firstSaved = store.saveReport(r1)
        val secondSaved = store.saveReport(r2)

        assertTrue(firstSaved)
        assertFalse("Duplicate ID must be rejected", secondSaved)
        assertEquals(1, store.getAllReports().size)
    }

    // 8. Empty incident list
    @Test
    fun testEmptyIncidentList() {
        assertTrue(store.getAllReports().isEmpty())
        val emptyEvaluated = store.getAllReports().map { PriorityClassifier.evaluate(it) }
        assertTrue(emptyEvaluated.isEmpty())
    }

    // 9. Existing coordinator persistence and preservation
    @Test
    fun testPersistenceAndDataIntegrity() {
        val report = createReport(
            category = "Trapped",
            notes = "Structural collapse, 4 trapped",
            peopleCount = "4",
            childrenInvolved = true,
            latitude = 12.9716,
            longitude = 77.5946
        )

        store.saveReport(report)

        // Reload from disk to verify atomic persistence
        val reloadedStore = CoordinatorReportStore(tempFolder.root)
        val loaded = reloadedStore.getAllReports()

        assertEquals(1, loaded.size)
        assertEquals(report.reportId, loaded[0].reportId)
        assertEquals(report.category, loaded[0].category)
        assertEquals(report.notes, loaded[0].notes)
        assertEquals(report.childrenInvolved, loaded[0].childrenInvolved)
        assertEquals(report.latitude, loaded[0].latitude)
        assertEquals(report.longitude, loaded[0].longitude)

        // Verify priority is evaluated consistently after reload
        val eval = PriorityClassifier.evaluate(loaded[0])
        assertEquals(IncidentPriority.HIGH, eval.priority)
    }
}
