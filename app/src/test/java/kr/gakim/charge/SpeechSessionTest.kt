package kr.gakim.charge

import kr.gakim.charge.data.*
import kr.gakim.charge.data.fake.FakeSpeechOutputGateway
import kr.gakim.charge.data.pleos.parkStateForGear
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import org.junit.Assert.*
import org.junit.Test

class SpeechSessionTest {
    @Test fun cancellationBeforeInitPreventsRequestAndCallbacks() {
        val session = SpeechSession(); var calls = 0
        session.close { calls++ }; session.initialize { calls++ }; session.active { calls++ }
        assertEquals(1, calls)
    }
    @Test fun timeoutPreventsLateInitAndOnlyEmitsOnce() {
        val session = SpeechSession(); var errors = 0; var requests = 0
        session.timeout { errors++ }; session.timeout { errors++ }; session.initialize { requests++ }
        assertEquals(1, errors); assertEquals(0, requests)
    }
    @Test fun successfulInitSuppressesTimeoutAndDuplicateInit() {
        val session = SpeechSession(); var requests = 0; var errors = 0
        session.initialize { requests++ }; session.initialize { requests++ }; session.timeout { errors++ }
        assertEquals(1, requests); assertEquals(0, errors)
    }
    @Test fun completionClosesCallbacksImmediately() {
        val session = SpeechSession(); var events = 0
        session.active { events++; session.close { } }; session.active { events++ }
        assertEquals(1, events)
    }
    @Test fun rmsIsFiniteAndBounded() {
        assertEquals(0f, normalizeSpeechRms(Double.NaN)); assertEquals(0f, normalizeSpeechRms(-4.0))
        assertEquals(0.5f, normalizeSpeechRms(5.0)); assertEquals(1f, normalizeSpeechRms(100.0))
        assertEquals(0f, normalizeSpeechRms(Double.POSITIVE_INFINITY))
    }
    @Test fun fakeRecordsAndStopsAfterRelease() {
        val output = FakeSpeechOutputGateway(); output.speak("안녕하세요"); output.stop(); output.release(); output.speak("무시")
        assertEquals(listOf("안녕하세요"), output.spoken); assertEquals(2, output.stopCount)
    }
    @Test fun onlyParkGearAuthorizesNavigation() {
        VehicleGear.entries.forEach { gear ->
            val expected = when (gear) {
                VehicleGear.GEAR_PARK -> ParkState.PARKED
                VehicleGear.GEAR_UNKNOWN -> ParkState.UNKNOWN
                else -> ParkState.MOVING
            }
            assertEquals(expected, parkStateForGear(gear))
        }
        assertEquals(ParkState.UNKNOWN, parkStateForGear(null))
    }
}
