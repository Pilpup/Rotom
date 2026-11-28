package com.college.feature.attendance

import com.college.feature.attendance.network.StudentHeatmapCourseDayItem
import com.college.feature.attendance.network.StudentHeatmapCourseItem
import com.college.feature.attendance.network.StudentHeatmapCourseView
import com.college.feature.attendance.network.StudentHeatmapDayItem
import com.college.feature.attendance.network.StudentHeatmapResponse
import com.college.feature.attendance.network.StudentHeatmapSessionItem
import com.college.feature.attendance.network.StudentHeatmapSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentHeatmapMathTest {

    @Test
    fun buildHeatmapCalendarColumns_alignsMondayToSundayAndPadsEdges() {
        // 2026-11-18 is Wednesday; 2026-11-27 is Friday.
        val columns = buildHeatmapCalendarColumns("2026-11-18", "2026-11-27")
        assertEquals(2, columns.size)

        // First column: Mon/Tue null, Wed..Sun populated
        val week0 = columns[0]
        assertNull(week0.days[0])
        assertNull(week0.days[1])
        assertEquals("2026-11-18", week0.days[2])
        assertEquals("2026-11-22", week0.days[6])

        // Second column: Mon..Fri populated, Sat/Sun null
        val week1 = columns[1]
        assertEquals("2026-11-23", week1.days[0])
        assertEquals("2026-11-27", week1.days[4])
        assertNull(week1.days[5])
        assertNull(week1.days[6])
    }

    @Test
    fun buildHeatmapMonthBlocks_separatesMonthsIntoDistinctColumns() {
        // Oct 31 (Saturday) ends October's last column; Nov 1 (Sunday) must start a NEW column in November.
        val blocks = buildHeatmapMonthBlocks("2026-10-26", "2026-11-08")
        assertEquals(listOf("Oct", "Nov"), blocks.map { it.monthLabel })

        val octLastCol = blocks[0].columns.last()
        assertEquals("2026-10-31", octLastCol.days[5]) // Saturday
        assertNull(octLastCol.days[6]) // Sunday belongs to November, NOT merged into October

        val novFirstCol = blocks[1].columns.first()
        for (dow in 0 until 6) {
            assertNull(novFirstCol.days[dow])
        }
        assertEquals("2026-11-01", novFirstCol.days[6]) // Sunday starts fresh in Nov column 0
        assertEquals("2026-11-02", blocks[1].columns[1].days[0]) // Monday in Nov column 1
    }

    @Test
    fun buildHeatmapCalendarColumns_placesNonOverlappingMonthLabels() {
        // Spanning Sep 15 -> Nov 28 splits cleanly into 3 month blocks
        val blocks = buildHeatmapMonthBlocks("2026-09-15", "2026-11-28")
        assertEquals(listOf("Sep", "Oct", "Nov"), blocks.map { it.monthLabel })

        val columns = buildHeatmapCalendarColumns("2026-09-15", "2026-11-28")
        val labelsWithWeeks = columns.mapNotNull { col ->
            col.monthLabel?.let { col.weekIndex to it }
        }
        assertEquals(listOf("Sep", "Oct", "Nov"), labelsWithWeeks.map { it.second })
        assertTrue(labelsWithWeeks[1].first - labelsWithWeeks[0].first >= 3)
        assertTrue(labelsWithWeeks[2].first - labelsWithWeeks[1].first >= 4)
    }

    @Test
    fun buildHeatmapCalendarColumns_handlesInvalidOrReversedRange() {
        assertTrue(buildHeatmapCalendarColumns("2026-11-10", "2026-11-05").isEmpty())
        assertTrue(buildHeatmapCalendarColumns("bad-date", "2026-11-05").isEmpty())
    }

    @Test
    fun resolveHeatmapSelection_switchesBetweenAllAndPerCourseViews() {
        val sess1 = StudentHeatmapSessionItem(
            sessionId = "s1",
            courseCode = "CS-101",
            courseName = "Algorithms",
            subjectName = "Algorithms",
            startedAt = "2026-11-25T10:00:00Z",
            status = "PRESENT"
        )
        val sess2 = StudentHeatmapSessionItem(
            sessionId = "s2",
            courseCode = "CS-102",
            courseName = "OS Lab",
            subjectName = "OS Lab",
            startedAt = "2026-11-25T12:00:00Z",
            status = "ABSENT"
        )
        val response = StudentHeatmapResponse(
            startDate = "2026-11-01",
            endDate = "2026-11-28",
            timezone = "Asia/Kolkata",
            summary = StudentHeatmapSummary(
                totalSessions = 2,
                presentCount = 1,
                absentCount = 1,
                leaveCount = 0,
                attendancePercentage = 50.0,
                currentStreakDays = 0,
                longestStreakDays = 0
            ),
            courses = listOf(
                StudentHeatmapCourseItem("CS-101", "Algorithms"),
                StudentHeatmapCourseItem("CS-102", "OS Lab")
            ),
            days = listOf(
                StudentHeatmapDayItem(
                    date = "2026-11-25",
                    presentCount = 1,
                    absentCount = 1,
                    leaveCount = 0,
                    totalSessionsCount = 2,
                    level = 2,
                    sessions = listOf(sess1, sess2)
                )
            ),
            courseViews = mapOf(
                "CS-101" to StudentHeatmapCourseView(
                    summary = StudentHeatmapSummary(
                        totalSessions = 1,
                        presentCount = 1,
                        absentCount = 0,
                        leaveCount = 0,
                        attendancePercentage = 100.0,
                        currentStreakDays = 1,
                        longestStreakDays = 1
                    ),
                    days = listOf(
                        StudentHeatmapCourseDayItem(
                            date = "2026-11-25",
                            presentCount = 1,
                            absentCount = 0,
                            leaveCount = 0,
                            totalSessionsCount = 1,
                            level = 3
                        )
                    )
                )
            )
        )

        val allSel = resolveHeatmapSelection(response, null)
        assertEquals(2, allSel.sessionsByDate["2026-11-25"]?.size)
        assertEquals(2, allSel.daySnapshotsByDate["2026-11-25"]?.level)
        assertEquals(50.0, allSel.summary.attendancePercentage ?: -1.0, 0.01)

        val cs101Sel = resolveHeatmapSelection(response, "CS-101")
        assertEquals(1, cs101Sel.sessionsByDate["2026-11-25"]?.size)
        assertEquals("s1", cs101Sel.sessionsByDate["2026-11-25"]?.first()?.sessionId)
        assertEquals(3, cs101Sel.daySnapshotsByDate["2026-11-25"]?.level)
        assertEquals(100.0, cs101Sel.summary.attendancePercentage ?: -1.0, 0.01)
        assertEquals(1, cs101Sel.summary.currentStreakDays)
    }
}
