package kr.gakim.charge.ui.planner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import kr.gakim.charge.data.GatewaySet
import kr.gakim.charge.data.fake.*
import kr.gakim.charge.demo.ItineraryRepository
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.theme.GakimTheme

private class PreviewActions(screen: Int) : PlannerActions {
    override val controller=ItineraryController(GatewaySet(FakeVehicleGateway(),FakeNaviGateway(null),FakeSpeechGateway(),true),clock={LocalDateTime.of(2026,11,16,10,0)})
    init {
        controller.setDemoEnergy(true)
        if(screen>0) {
            controller.compare()
            controller.select(controller.state.value.options.first { it.mode==ChargeMode.TOP_UP && it.selectable }.id)
            if(screen==2) controller.guide()
        }
    }
    override fun edit(action: () -> Unit)=action()
    override fun toggleListening()=Unit
    override fun stopListening()=Unit
    override fun refresh()=Unit
    override fun back()=controller.back()
    override fun startGuidance()=Unit
}

@Composable
private fun PlannerPreview(screen:Int) {
    val actions=remember { PreviewActions(screen) }
    GakimTheme {
        Box(Modifier.fillMaxSize().padding(24.dp)) {
            val s=actions.controller.state.value
            when(screen) {
                0 -> PlannerToday(s,actions,{})
                1 -> PlannerCompare(s,actions,{})
                else -> PlannerGuide(s,actions,{})
            }
        }
    }
}

@Preview(widthDp=1280,heightDp=910,showBackground=true)
@Composable private fun PlannerTodayPreview()=PlannerPreview(0)
@Preview(widthDp=1280,heightDp=910,showBackground=true)
@Composable private fun PlannerComparePreview()=PlannerPreview(1)
@Preview(widthDp=1280,heightDp=910,showBackground=true)
@Composable private fun PlannerGuidePreview()=PlannerPreview(2)
