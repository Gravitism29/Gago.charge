package kr.gakim.charge.ui.planner

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.gakim.charge.BuildConfig
import kr.gakim.charge.data.*
import kr.gakim.charge.ui.screens.*
import kr.gakim.charge.ui.theme.*

@Composable
fun ItineraryApp(s: PlannerState, vm: PlannerViewModel) {
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(s.park) { if(s.park==ParkState.MOVING && sheet!="sources" && sheet?.startsWith("details:")!=true) sheet=null }
    Box(Modifier.fillMaxSize().background(GakimColors.Background)) {
        Column(Modifier.fillMaxSize().padding(horizontal=24.dp, vertical=12.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            PlannerChrome(s,vm) { sheet=it }
            if(s.rangeUnknown) Text(rangeNotice,style=GakimType.Caption,color=GakimColors.TextDim)
            if(s.park==ParkState.MOVING) {
                Row(Modifier.fillMaxWidth().height(76.dp).clip(CardShape).background(GakimColors.SurfaceHigh).padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Glyph(Icon.LOCK,GakimColors.Tight)
                    Text("주행 중에는 일정을 바꿀 수 없어요",style=GakimType.BodyBold)
                }
            }
            Box(Modifier.weight(1f)) {
                when(s.screen) {
                    0 -> PlannerToday(s,vm) { sheet=it }
                    1 -> PlannerCompare(s,vm) { sheet=it }
                    else -> PlannerGuide(s,vm) { sheet=it }
                }
            }
        }
        PlannerSheets(s,vm,sheet,{sheet=null})
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlannerChrome(s:PlannerState,vm:PlannerViewModel,open:(String)->Unit) {
    val hasDemo=s.demoClock || s.options.any { option -> option.evidence.any { it.demo } || option.visits.any { it.place?.demo==true } } ||
        s.plan.visits.any { it.place?.demo==true } || listOfNotNull(s.energy.socPercent?.evidence,s.energy.rangeKm?.evidence,s.energy.kmPerKwh?.evidence).any { it.demo }
    Row(Modifier.fillMaxWidth().height(84.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("가는 김에 충전",style=GakimType.Title.copy(fontSize=24.sp),modifier=Modifier.combinedClickable(onClick={},onLongClick={if(BuildConfig.DEBUG) open("dev")}).height(76.dp).wrapContentHeight())
        Row(Modifier.weight(1f),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
            listOf("일정","비교","확인").forEachIndexed { index,label ->
                Row(Modifier.height(76.dp).clip(PillShape).clickable(enabled=index<s.screen && !s.busy) { repeat(s.screen-index) { vm.back() } }.padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Dot(if(index==s.screen) GakimColors.Accent else GakimColors.Outline,10.dp)
                    Text(label,style=GakimType.CaptionBold,color=if(index==s.screen) GakimColors.Accent else GakimColors.TextDim)
                }
            }
        }
        Pill((s.energy.socPercent?.value?.let { "$it%" } ?: "배터리 ?") + if(s.demoEnergy) " 시연" else "",icon=Icon.BATTERY,color=if(s.demoEnergy) GakimColors.Demo else GakimColors.Text)
        Pill((if(s.rangeUnknown) "주행 거리 ?" else "${s.energy.rangeKm!!.value.toInt()}km") + if(s.demoEnergy) " 시연" else "",color=if(s.demoEnergy) GakimColors.Demo else GakimColors.Text)
        Pill(if(s.demoClock) "시연 13:00" else clock(s.now),color=if(s.demoClock) GakimColors.Demo else GakimColors.Text)
        Box(Modifier.height(76.dp).clip(PillShape).clickable(role=Role.Button) { open("sources") },contentAlignment=Alignment.Center) {
            Pill(if(hasDemo) "시연 데이터" else "실시간 데이터",color=if(hasDemo) GakimColors.Demo else GakimColors.Voice,outline=true)
        }
        Box(Modifier.size(76.dp).clip(PillShape).border(2.dp,GakimColors.Outline,PillShape).semantics { contentDescription=if(s.tts) "음성 안내 끄기" else "음성 안내 켜기" }.clickable(role=Role.Switch) { vm.controller.setTts(!s.tts) },contentAlignment=Alignment.Center) {
            Glyph(if(s.tts) Icon.SPEAKER else Icon.SPEAKER_OFF,GakimColors.Text,28.dp)
        }
    }
}

@Composable
internal fun PlannerPill(text:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,selected:Boolean=false,color:Color=GakimColors.Accent) {
    Box(modifier.heightIn(min=76.dp).clip(PillShape).background(if(selected) GakimColors.SurfaceHigh else GakimColors.Surface)
        .border(if(selected) 3.dp else 2.dp,if(selected) color else GakimColors.Outline,PillShape)
        .clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=16.dp,vertical=8.dp),contentAlignment=Alignment.Center) {
        Text(text,style=GakimType.BodyBold,color=if(!enabled) GakimColors.TextDim else if(selected) color else GakimColors.Text,textAlign=TextAlign.Center,maxLines=2)
    }
}

@Composable
internal fun PlannerAssistant(s:PlannerState,modifier:Modifier=Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(s.assistantLine(),style=GakimType.Assistant,maxLines=2,overflow=TextOverflow.Ellipsis)
        val message=(if(s.screen==1 && s.demoMartFallback) martFallbackLine else s.message)?.let { if(it.startsWith("음성 서비스 오류")) "마이크 연결을 확인해 주세요. 화면을 눌러 이어갈 수 있어요." else it }
            ?: s.inputBlocker ?: if(s.screen==2 && s.phase==ApplyPhase.IDLE) "확인한 뒤에만 차량 내비를 바꿔요." else null
        message?.let { Text(it,style=GakimType.Caption,maxLines=2,overflow=TextOverflow.Ellipsis) }
    }
}

/** Full-size in-app surface, deliberately in the app window rather than a platform dialog. */
@Composable
internal fun PlannerSheet(title:String,close:()->Unit,content:@Composable ColumnScope.()->Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.72f)).clickable(onClick=close),contentAlignment=Alignment.Center) {
        Column(Modifier.fillMaxWidth(.84f).fillMaxHeight(.94f).clip(CardShape).background(GakimColors.Surface).border(2.dp,GakimColors.Outline,CardShape).clickable {}.padding(28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(title,style=GakimType.Title,modifier=Modifier.weight(1f))
                PlannerPill("닫기",close,Modifier.width(112.dp))
            }
            CompositionLocalProvider(LocalTextStyle provides GakimType.Body) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
            }
        }
    }
}
