package kr.gakim.charge

import kr.gakim.charge.ui.planner.goLabel
import kr.gakim.charge.ui.state.withGa
import kr.gakim.charge.ui.state.withIeyo
import kr.gakim.charge.ui.state.withNeun
import kr.gakim.charge.ui.state.withReul
import kr.gakim.charge.ui.state.withRo
import org.junit.Assert.*
import org.junit.Test

/** 화면 문구의 조사는 이름의 받침으로 고른다(ㄹ 받침은 '로'). */
class PlannerParticleTest {
    @Test fun rieulFinalTakesRo() {
        assertEquals("은평점으로",withRo("은평점"))
        assertEquals("서울로",withRo("서울"))
        assertEquals("학교로",withRo("학교"))
        assertEquals("상암점으로",withRo("상암점"))
    }
    @Test fun topicAndSubjectParticles() {
        assertEquals("병원은",withNeun("병원"))
        assertEquals("학교는",withNeun("학교"))
        assertEquals("은평점은",withNeun("은평점"))
        assertEquals("병원이",withGa("병원"))
        assertEquals("학교가",withGa("학교"))
        assertEquals("은평점을",withReul("은평점"))
        assertEquals("학교를",withReul("학교"))
        assertEquals("병원이에요",withIeyo("병원"))
        assertEquals("학교예요",withIeyo("학교"))
    }
    @Test fun compareCtaUsesTheHelper() {
        assertEquals("은평점으로 가기 →",goLabel("은평점"))
        assertEquals("학교로 가기 →",goLabel("학교"))
        assertEquals("서울로 가기 →",goLabel("서울"))
        assertEquals("병원으로 가기 →",goLabel("병원"))
    }
}
