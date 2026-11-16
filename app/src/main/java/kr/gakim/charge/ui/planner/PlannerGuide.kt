package kr.gakim.charge.ui.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.gakim.charge.data.*
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.screens.*
import kr.gakim.charge.ui.state.withNeun
import kr.gakim.charge.ui.theme.*

@Composable
internal fun PlannerGuide(s:PlannerState,vm:PlannerViewModel,open:(String)->Unit) {
    val c=vm.controller
    val enabled=!s.busy && s.park!=ParkState.MOVING && !s.departureExpired
    val applied=s.phase==ApplyPhase.APPLIED
    val removed=if(applied) emptyList() else s.removedPlaces()
    val destination=(s.currentRoute as? NaviRead.Known)?.route?.destination
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        PlannerAssistant(s)
        RouteStrip(s)
        Text("이전: ${routeText(s.reviewedRoute ?: (s.currentRoute as? NaviRead.Known)?.route)}   →   ${if(applied) "반영" else "변경"}: ${s.selected?.visits?.joinToString(" → ") { it.place?.name ?: "장소 미정" } ?: "경로 미정"}",style=GakimType.Caption,maxLines=2,overflow=TextOverflow.Ellipsis)
        if(!s.busy && !applied && s.phase!=ApplyPhase.UNCONFIRMED && removed.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(CardShape).background(GakimColors.Tight.copy(alpha=.12f)).border(2.dp,GakimColors.Tight,CardShape).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                val removedDestination=destination?.takeIf { d -> removed.any { PlaceMatcher.samePlace(it,d) } }
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Glyph(Icon.WARN,GakimColors.Tight)
                    val names=removedDestination?.name ?: removed.joinToString(" · ") { it.name }
                    Text("기존 ${if(removedDestination!=null) "목적지" else "경유지"} ${withNeun(names)} 빠져요",style=GakimType.Title,color=GakimColors.Tight,modifier=Modifier.weight(1f),maxLines=2)
                }
                if(removedDestination!=null && removed.size>1) Text("함께 빠지는 경유지: ${removed.filterNot { PlaceMatcher.samePlace(it,removedDestination) }.joinToString(" · ") { it.name }}",style=GakimType.Caption,color=GakimColors.Tight)
                Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    if(s.canKeepDestination()) PlannerPill("그대로 두고 경유지로 추가",{vm.edit { c.keepExistingDestination() }},Modifier.weight(1.5f),enabled)
                    PlannerPill(if(s.acknowledged) "✓ 빼고 진행하기로 했어요" else "빼고 진행",{vm.stopListening();c.setAcknowledged(true)},Modifier.weight(1f),enabled,s.acknowledged,color=GakimColors.Tight)
                }
                if(!s.canKeepDestination() && removedDestination!=null) Text(if((s.selected?.visits?.size ?: 0)>=3) "세 곳이 이미 정해져 있어요. 기존 목적지를 남기려면 방문 하나를 줄여 주세요." else "기존 목적지의 위치를 다시 선택하면 마지막 방문으로 남길 수 있어요.",style=GakimType.Caption)
            }
        }
        if(s.phase!=ApplyPhase.IDLE && (!applied || s.requestSent)) RouteTracker(s)
        if(s.applyMessage.isNotBlank()) Text(s.applyMessage,style=GakimType.Body,color=if(applied) GakimColors.Accent else GakimColors.Tight,maxLines=3,overflow=TextOverflow.Ellipsis)
        if(s.actualRoute!=null && !applied) Text("현재 확인한 경로: ${routeText(s.actualRoute)}",style=GakimType.Caption,maxLines=2)
        if(s.phase==ApplyPhase.IDLE && removed.isEmpty()) s.selected?.let { option ->
            Row(Modifier.fillMaxWidth().clip(CardShape).background(GakimColors.Surface).padding(20.dp),horizontalArrangement=Arrangement.spacedBy(24.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(clock(if(option.critical?.visit?.deadline!=null) option.critical?.arrival else option.results.lastOrNull()?.finish),style=GakimType.Big)
                Column(Modifier.weight(1f)) {
                    Text(if(option.critical?.visit?.deadline!=null) "${option.critical?.visit?.place?.name ?: option.critical?.visit?.label} 도착 예상" else "볼일을 모두 마치는 시간",style=GakimType.Body)
                    Text(option.mode.driverLabel(),style=GakimType.Caption,color=GakimColors.Voice)
                }
                PlannerPill("자세히",{open("details:${option.id}")})
            }
        }
        Spacer(Modifier.weight(1f))
        if(s.park==ParkState.UNKNOWN && !applied) PlannerPill(if(s.parkedConfirmed) "✓ 주차 중이에요" else "주차 중이에요 · 눌러서 확인",{c.setParked(!s.parkedConfirmed)},Modifier.fillMaxWidth(),enabled,s.parkedConfirmed)
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            CompactMicWithHeard(s.listening,s.level,if(s.listening) s.partial else s.transcript,"말로 일정을 수정할 수 있어요",vm::toggleListening,Modifier.weight(1f),enabled && !applied)
            if(!applied) PlannerPill("← 다른 곳",vm::back,enabled=enabled)
            val recheck=s.phase==ApplyPhase.UNCONFIRMED
            PrimaryButton(when { applied -> "완료";s.busy -> "내비 확인 중…";recheck -> "다시 확인";else -> "이 경로로 안내" },{
                when { applied -> vm.edit { c.restart() };recheck -> vm.refresh();else -> { if(removed.isEmpty()) c.setAcknowledged(true);vm.confirm() } }
            },Modifier.width(380.dp),enabled=applied || enabled && (recheck || s.selected?.selectable==true && (removed.isEmpty() || s.acknowledged) && (s.park==ParkState.PARKED || s.parkedConfirmed)))
        }
    }
}

@Composable
private fun RouteStrip(s:PlannerState) {
    val route=s.reviewedRoute ?: (s.currentRoute as? NaviRead.Known)?.route
    val previous=route?.let { it.waypoints+listOfNotNull(it.destination) }.orEmpty()
    val visits=s.selected?.visits.orEmpty()
    val applied=s.phase==ApplyPhase.APPLIED
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=18.dp),verticalAlignment=Alignment.Top) {
            val names=listOf("현재 위치")+visits.map { it.place?.name ?: "장소 미정" }
            names.forEachIndexed { index,name ->
                val visit=visits.getOrNull(index-1)
                val changed=visit!=null && ((previous.getOrNull(index-1)?.let { old -> visit.place?.let { !PlaceMatcher.samePlace(old,it.toNavi()) } } ?: true) ||
                    (index==names.lastIndex)!=(index-1==previous.lastIndex))
                Column(Modifier.width(200.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(56.dp).clip(CircleShape).then(if(changed || applied && visit!=null) Modifier.background(GakimColors.brandH()) else Modifier.background(GakimColors.SurfaceHigh).border(2.dp,GakimColors.Outline,CircleShape)),contentAlignment=Alignment.Center) {
                        Glyph(if(applied && visit!=null) Icon.CHECK else if(index==0) Icon.CAR else if(index==names.lastIndex) Icon.FLAG else Icon.PIN,if(changed || applied && visit!=null) GakimColors.OnAccent else GakimColors.Text,30.dp)
                    }
                    Text(name,style=GakimType.BodyBold,maxLines=2,textAlign=TextAlign.Center,overflow=TextOverflow.Ellipsis)
                    Text(if(visit==null) "출발" else (if(changed && !applied) "새 경로 · " else "")+if(index==names.lastIndex) "목적지" else "${visit.label} ${visit.stayMinutes}분",style=GakimType.Caption,color=if(changed) GakimColors.Accent else GakimColors.TextDim,maxLines=2,textAlign=TextAlign.Center)
                }
                if(index<names.lastIndex) Box(Modifier.weight(1f).padding(top=25.dp).height(5.dp).clip(PillShape).background(GakimColors.brandH()))
            }
        }
    }
}

@Composable
private fun RouteTracker(s:PlannerState) {
    // SENDING includes preflight checks: it must never claim a request has already been sent.
    val sent=s.requestSent
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        listOf("요청 보냄","내비가 경로 계산 중","반영 확인").forEachIndexed { index,title ->
            val done=s.phase==ApplyPhase.APPLIED || sent && index==0
            val active=s.phase==ApplyPhase.SENDING && index==0 || s.phase==ApplyPhase.VERIFYING && index==1
            val warn=s.phase in listOf(ApplyPhase.FAILED,ApplyPhase.UNCONFIRMED) && index==2
            val color=when { done -> GakimColors.Accent;active -> GakimColors.Voice;warn -> GakimColors.Tight;else -> GakimColors.TextDim }
            Row(Modifier.weight(1f).height(106.dp).clip(CardShape).background(GakimColors.Surface).border(2.dp,color,CardShape).padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Glyph(if(done) Icon.CHECK else if(warn) Icon.WARN else Icon.CLOCK,color,32.dp)
                Column {
                    Text(title,style=GakimType.BodyBold,maxLines=2)
                    Text(when { done -> "확인했어요";active && index==0 -> "보내기 전 확인 중";active -> "반영을 기다려요";warn -> "다시 확인해 주세요";else -> "기다리는 중" },style=GakimType.Caption,color=color,maxLines=2)
                }
            }
        }
    }
}
