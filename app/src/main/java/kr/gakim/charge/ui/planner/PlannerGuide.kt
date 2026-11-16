package kr.gakim.charge.ui.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
internal fun PlannerGuide(s:PlannerState,vm:PlannerActions,open:(String)->Unit) {
    val c=vm.controller
    val enabled=!s.busy && s.park!=ParkState.MOVING && !s.departureExpired
    val applied=s.phase==ApplyPhase.APPLIED
    val removed=if(applied) emptyList() else s.removedPlaces()
    val destination=(s.currentRoute as? NaviRead.Known)?.route?.destination
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text(if(applied) "내비에 반영됐어요" else if(s.selected?.visits?.size==1) "이 경로로 갈까요?" else "들를 곳과 순서를 확인해 주세요",style=GakimType.Assistant)
        Row(Modifier.weight(1f).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
            RouteTimeline(s,Modifier.weight(1.25f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight().clip(CardShape).background(GakimColors.Surface).padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("모두 끝나는 시각",style=GakimType.Body)
                FitLine(clock(s.selected?.results?.lastOrNull()?.finish),GakimType.Big)
                s.selected?.let { option ->
                    Text(option.mode.driverLabel(),style=GakimType.Title,color=GakimColors.Accent)
                    val place=option.visits.firstOrNull { it.id==option.chargeVisitId }?.place
                    Text(benefitLine(option,s.energy,s.now,place?.id?.let(s.chargingSites::get)),style=GakimType.Body)
                    Text(option.deadlineLabel(),style=GakimType.BodyBold,color=if(option.late) GakimColors.Tight else GakimColors.Accent)
                    option.critical?.visit?.deadline?.let { Text("약속 ${at(it,s.now)}까지",style=GakimType.Caption) }
                    PlannerPill("자세히",{open("details:${option.id}")},Modifier.fillMaxWidth(),color=GakimColors.Voice)
                }
            }
        }
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
        if(s.park==ParkState.UNKNOWN && !applied) PlannerPill(if(s.parkedConfirmed) "✓ 주차 중이에요" else "주차 중이에요 · 눌러서 확인",{c.setParked(!s.parkedConfirmed)},Modifier.fillMaxWidth(),enabled,s.parkedConfirmed)
        if(!applied) {
            val name=s.selected?.visits?.lastOrNull()?.place?.name
            if(name!=null) Text(if(destination!=null) "지금 목적지 ${kr.gakim.charge.ui.state.withReul(destination.name)} 바꿉니다. 차량 내비 목적지를 ${kr.gakim.charge.ui.state.withRo(name)} 설정합니다" else "차량 내비 목적지를 ${kr.gakim.charge.ui.state.withRo(name)} 설정합니다",style=GakimType.Caption)
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            PlannerStepMic(s,"“안내 시작” · “다른 곳으로”",vm::toggleListening,Modifier.weight(1f),enabled && !applied)
            if(!applied) PlannerPill("← 다른 곳",vm::back,enabled=enabled)
            val recheck=s.phase==ApplyPhase.UNCONFIRMED
            PrimaryButton(when { applied -> "완료";s.busy -> "내비 확인 중…";recheck -> "다시 확인";else -> "이 경로로 안내" },{
                when { applied -> vm.edit { c.restart() };recheck -> vm.refresh();else -> vm.startGuidance() }
            },Modifier.width(380.dp),enabled=applied || enabled && (recheck || s.canStartGuidance()))
        }
    }
}

@Composable
internal fun RouteTimeline(s:PlannerState,modifier:Modifier=Modifier) {
    val option=s.selected
    Column(modifier.clip(CardShape).background(GakimColors.Surface).padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        TimelineStop("현재 위치 출발",clock(s.plan.departure ?: s.now),Icon.CAR)
        var previous=s.plan.departure ?: s.now
        option?.results?.forEach { result ->
            val travel=result.arrival?.let { java.time.Duration.between(previous,it).toMinutes().coerceAtLeast(0) }
            Row(Modifier.padding(start=24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Box(Modifier.width(3.dp).height(28.dp).background(GakimColors.Voice))
                Text("이동 ${travel?.let { "${it}분" } ?: "시간 미확인"}",style=GakimType.Caption,color=GakimColors.Voice)
            }
            val visit=result.visit
            val charging=visit.id==option.chargeVisitId && option.mode!=ChargeMode.NONE
            val activity=if(visit.activity==Activity.ARRIVE && visit.stayMinutes==0) null else if(visit.activity==Activity.CHARGE) null else "볼일 ${visit.stayMinutes}분"
            val details=listOfNotNull(activity,if(charging) "충전 ${option.chargeMinutes?.let { "${it}분" } ?: "시간 미확인"}" else null).joinToString(" · ")
            TimelineStop("${visit.place?.name ?: visit.label} 도착",clock(result.arrival),Icon.PIN,details)
            result.finish?.let { previous=it }
        }
        Box(Modifier.padding(start=24.dp).width(3.dp).height(20.dp).background(GakimColors.Accent))
        TimelineStop("모두 끝",clock(option?.results?.lastOrNull()?.finish),Icon.FLAG)
    }
}

@Composable
private fun TimelineStop(title:String,time:String,icon:Icon,detail:String="") {
    Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(50.dp).clip(CircleShape).background(GakimColors.SurfaceHigh),contentAlignment=Alignment.Center) { Glyph(icon,GakimColors.Accent,28.dp) }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=GakimType.BodyBold,maxLines=2)
            Text(time,style=GakimType.Title)
            if(detail.isNotBlank()) Text(detail,style=GakimType.Caption)
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
