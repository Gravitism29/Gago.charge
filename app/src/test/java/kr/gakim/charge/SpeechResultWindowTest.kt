package kr.gakim.charge

import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kr.gakim.charge.data.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechResultWindowTest {
    @Test fun actualOrderEndThenFinalIsAccepted()=runTest {
        val events=mutableListOf<SpeechEvent>();var closes=0
        val window=SpeechResultWindow(this,{events+=it},{closes++})
        window.result("은평점에서 25분",false);window.ended();advanceTimeBy(10)
        window.result("은평점에서 25분 장 보고 4시 10분까지 학교 가야 해",true);advanceUntilIdle()
        assertTrue(events.last() is SpeechEvent.Final);assertEquals(1,closes)
    }
    @Test fun endDoesNotCloseChannelPrematurely()=runTest {
        val events=mutableListOf<SpeechEvent>();var closes=0
        val window=SpeechResultWindow(this,{events+=it},{closes++})
        window.ended();advanceTimeBy(2499);assertEquals(0,closes);assertTrue(events.isEmpty());window.cancel()
    }
    @Test fun missingFinalTimesOutAsErrorRatherThanApplyingPartial()=runTest {
        val events=mutableListOf<SpeechEvent>();val window=SpeechResultWindow(this,{events+=it},{})
        window.result("4시",false);window.ended();advanceUntilIdle()
        assertTrue(events.last() is SpeechEvent.Error);assertFalse(events.any { it is SpeechEvent.Final })
    }
    @Test fun finalIsAppliedOnlyOnce()=runTest {
        val events=mutableListOf<SpeechEvent>();var closes=0;val window=SpeechResultWindow(this,{events+=it},{closes++})
        window.result("아니 4시 반까지",true);window.ended();window.result("이전 문장",true);advanceUntilIdle()
        assertEquals(1,events.size);assertEquals(1,closes)
    }
    @Test fun cancelledSessionIgnoresLateFinal()=runTest {
        val events=mutableListOf<SpeechEvent>();val window=SpeechResultWindow(this,{events+=it},{})
        window.ended();window.cancel();window.result("이전 문장",true);advanceUntilIdle();assertTrue(events.isEmpty())
    }
    @Test fun finalAfterExpiryCannotOverwriteNewInput()=runTest {
        val events=mutableListOf<SpeechEvent>();val window=SpeechResultWindow(this,{events+=it},{})
        window.ended();advanceUntilIdle();window.result("늦은 문장",true);assertEquals(1,events.size);assertTrue(events[0] is SpeechEvent.Error)
    }
}
