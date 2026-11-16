package kr.gakim.charge

import java.time.LocalDateTime
import kr.gakim.charge.ui.planner.*
import org.junit.Assert.*
import org.junit.Test

class TimeDialMathTest {
    private val duration = TimeDialMode.Duration
    private val clock = TimeDialMode.Clock
    private val now = LocalDateTime.of(2026, 11, 16, 10, 0)

    @Test fun clockwiseSeamIsASmallPositiveDelta() {
        assertEquals(12f, dialAngleDelta(354f, 6f), .001f)
    }

    @Test fun counterclockwiseSeamIsASmallNegativeDelta() {
        assertEquals(-12f, dialAngleDelta(6f, 354f), .001f)
    }

    @Test fun durationCrossesSixtyWithoutLosingItsLap() {
        assertEquals(65, dialDragValue(55, dialAngleDelta(330f, 30f), duration))
        assertEquals(55, dialDragValue(65, dialAngleDelta(30f, 330f), duration))
    }

    @Test fun durationCanAccumulateMoreThanOneTurn() {
        assertEquals(145, dialDragValue(25, 720f, duration))
        assertEquals(25, dialDragValue(145, -720f, duration))
    }

    @Test fun draggingStartsRelativeToTheGrabbedAngle() {
        // The thumb is at 150 degrees, but the driver grabs the ring at 270.
        assertEquals(30, dialDragValue(25, dialAngleDelta(270f, 300f), duration))
        assertEquals(25, dialDragValue(25, 0f, duration))
    }

    @Test fun durationClampsAtFive() {
        assertEquals(5, dialDragValue(10, -360f, duration))
        assertEquals(5, snapDialValue(0f, duration))
    }

    @Test fun durationClampsAtOneHundredEighty() {
        assertEquals(180, dialDragValue(175, 360f, duration))
        assertEquals(180, snapDialValue(999f, duration))
    }

    @Test fun tappingAcrossSeamUsesTheNearestLap() {
        assertEquals(65, dialAngleToValue(30f, 55, duration))
        assertEquals(55, dialAngleToValue(330f, 65, duration))
        assertEquals(125, dialAngleToValue(30f, 115, duration))
    }

    @Test fun snapsToFiveMinuteDetents() {
        assertEquals(25, snapDialValue(27.4f, duration))
        assertEquals(30, snapDialValue(27.5f, duration))
        assertEquals(715, snapDialValue(717.4f, clock))
        assertEquals(720, snapDialValue(717.5f, clock))
    }

    @Test fun angleAndValueRoundTripEveryDurationDetent() {
        for (minutes in 5..180 step 5) {
            assertEquals(minutes, dialAngleToValue(normalizeDialAngle(valueToDialAngle(minutes.toFloat())), minutes, duration))
        }
    }

    @Test fun lapsRetainExactHourBoundaries() {
        assertEquals(DialLaps(0, 55f), dialLaps(55f))
        assertEquals(DialLaps(1, 0f), dialLaps(60f))
        assertEquals(DialLaps(1, 5f), dialLaps(65f))
        assertEquals(DialLaps(2, 5f), dialLaps(125f))
        assertEquals(DialLaps(3, 0f), dialLaps(180f))
    }

    @Test fun noonRollsElevenFiftyFiveToTwelveToTwelveOhFive() {
        val noon = dialDragValue(11 * 60 + 55, dialAngleDelta(330f, 0f), clock)
        val after = dialDragValue(noon, dialAngleDelta(0f, 30f), clock)
        assertEquals(12 * 60, noon)
        assertEquals(12 * 60 + 5, after)
        assertEquals("오전 11:55", dialClockLabel(715))
        assertEquals("오후 12:00", dialClockLabel(noon))
        assertEquals("오후 12:05", dialClockLabel(after))
    }

    @Test fun reverseNoonFlipsBackToMorning() {
        assertEquals(715, dialDragValue(720, dialAngleDelta(0f, 330f), clock))
        assertEquals("오전 11:55", dialClockLabel(715))
    }

    @Test fun midnightRollsIntoTomorrowAndFlipsToMorning() {
        val midnight = dialDragValue(1435, 30f, clock)
        assertEquals(1440, midnight)
        assertEquals(now.toLocalDate().plusDays(1).atStartOfDay(), dialClockTime(midnight, now))
        assertEquals("오전 12:00", dialClockLabel(midnight))
        assertEquals(1445, dialDragValue(midnight, 30f, clock))
        assertEquals(1435, dialDragValue(midnight, -30f, clock))
    }

    @Test fun meridiemTogglePreservesMinutesAndSelectedDay() {
        assertEquals(16 * 60 + 10, dialMeridiem(4 * 60 + 10, true))
        assertEquals(4 * 60 + 10, dialMeridiem(16 * 60 + 10, false))
        assertEquals(1440 + 16 * 60 + 10, dialMeridiem(1440 + 4 * 60 + 10, true))
        assertEquals(1440 + 10, dialMeridiem(1440 + 720 + 10, false))
    }

    @Test fun clockBoundsRetainTodayAndTomorrow() {
        assertEquals(0, dialDragValue(0, -30f, clock))
        assertEquals(2875, dialDragValue(2875, 30f, clock))
    }

    @Test fun todayRejectsPastAndLessThanTenMinutesAhead() {
        assertFalse(dialClockAllowed(595, now))
        assertFalse(dialClockAllowed(605, now))
        assertTrue(dialClockAllowed(610, now))
        assertFalse(dialClockAllowed(610, now.plusSeconds(1)))
        assertTrue(dialClockAllowed(615, now.plusSeconds(1)))
    }

    @Test fun tomorrowAllowsEarlyHoursButStillObservesTenMinuteBoundaryAtMidnight() {
        assertTrue(dialClockAllowed(1440, now))
        val late = now.withHour(23).withMinute(55)
        assertFalse(dialClockAllowed(1440, late))
        assertTrue(dialClockAllowed(1445, late))
    }

    @Test fun minimumRoundsUpIncludingSecondsAndMidnight() {
        assertEquals(610, minimumDialClockValue(now))
        assertEquals(615, minimumDialClockValue(now.plusSeconds(1)))
        assertEquals(1445, minimumDialClockValue(now.withHour(23).withMinute(55)))
        assertEquals(1450, minimumDialClockValue(now.withHour(23).withMinute(55).plusSeconds(1)))
    }

    @Test fun everyClockDetentRetainsItsHourAndDay() {
        for (minutes in 0..2875 step 5) {
            assertEquals(minutes, dialAngleToValue(normalizeDialAngle(valueToDialAngle(minutes.toFloat())), minutes, clock))
        }
    }

    @Test fun quickPicksAreTheNextFourHalfHoursIncludingTomorrow() {
        assertEquals(listOf(630, 660, 690, 720), nextDialHalfHours(now))
        assertEquals(listOf(1440, 1470, 1500, 1530), nextDialHalfHours(now.withHour(23).withMinute(59)))
    }

    @Test fun clockValueRetainsDateAndRoundTrips() {
        val selected = now.plusDays(1).withHour(16).withMinute(10)
        val value = clockDialValue(selected, now)
        assertEquals(1440 + 970, value)
        assertEquals(selected, dialClockTime(value, now))
        // UX pass keeps accessibility and visible clocks identical.
        assertEquals("오후 4:10", dialClockLabel(value, spoken = true))
    }
}
