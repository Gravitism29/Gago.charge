package kr.gakim.charge.ui.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.sp
import kr.gakim.charge.data.*
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.screens.*
import kr.gakim.charge.ui.state.withNeun
import kr.gakim.charge.ui.state.withRo
import kr.gakim.charge.ui.state.withGa
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
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("모두 끝나는 시각",style=GakimType.Body,modifier=Modifier.weight(1f))
                    Text("자세히 ›",style=GakimType.CaptionBold,color=GakimColors.Voice,
                        modifier=Modifier.heightIn(min=56.dp).clickable { s.selected?.let { open("details:${it.id}") } }.wrapContentHeight())
                }
                FitLine(clock(s.selected?.results?.lastOrNull()?.finish),GakimType.Big)
                s.selected?.let { option ->
                    Text(option.mode.driverLabel(),style=GakimType.Title,color=GakimColors.Accent)
                    val place=option.visits.firstOrNull { it.id==option.chargeVisitId }?.place
                    Text(benefitLine(option,s.energy,s.now,place?.id?.let(s.chargingSites::get)),style=GakimType.Body)
                    Text(option.deadlineLabel(),style=GakimType.BodyBold,color=if(option.late) GakimColors.Tight else GakimColors.Accent)
                    option.critical?.visit?.deadline?.let { Text("약속 ${at(it,s.now)}까지",style=GakimType.Caption) }
                }
            }
        }
        if(!s.busy && !applied && s.phase!=ApplyPhase.UNCONFIRMED && removed.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(CardShape).background(GakimColors.Tight.copy(alpha=.12f)).border(2.dp,GakimColors.Tight,CardShape).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                val removedDestination=destination?.takeIf { d -> removed.any { PlaceMatcher.samePlace(it,d) } }
                val old=removedDestination?.name ?: removed.joinToString(" · ") { it.name }
                val next=s.selected?.visits?.lastOrNull()?.place?.name ?: "새 목적지"
                Text("${old}에는 안 가셔도 되나요?",style=GakimType.Title,color=GakimColors.Tight)
                Text("${withRo(next)} 바꾸면 ${withGa(removed.joinToString(" · ") { it.name })} 경로에서 빠져요.",style=GakimType.Caption,color=GakimColors.Text)
                Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    RouteChoiceButton("${old}도 들르기","경유지로 추가해요",{vm.edit { c.keepExistingDestination() }},Modifier.weight(1f),enabled && s.canKeepDestination(),false)
                    RouteChoiceButton("${next}만 가기","기존 경로를 지워요",{vm.stopListening();c.setAcknowledged(true)},Modifier.weight(1f),enabled,s.acknowledged)
                }
                if(!s.canKeepDestination() && removedDestination!=null) Text(if((s.selected?.visits?.size ?: 0)>=3) "세 곳이 이미 정해져 있어요. 기존 목적지를 남기려면 방문 하나를 줄여 주세요." else "기존 목적지의 위치를 다시 선택하면 마지막 방문으로 남길 수 있어요.",style=GakimType.Caption)
            }
        }
        if(!applied && removed.isEmpty()) s.keptDestinationName?.let { name ->
            RouteChoiceButton("${name}도 들르기","경유지로 추가해요",{},Modifier.fillMaxWidth(),false,true)
        }
        if(s.phase!=ApplyPhase.IDLE) RouteTracker(s)
        if(s.applyMessage.isNotBlank() && s.phase in listOf(ApplyPhase.IDLE,ApplyPhase.FAILED,ApplyPhase.UNCONFIRMED)) Text(s.applyMessage,style=GakimType.Body,color=GakimColors.Tight,maxLines=3,overflow=TextOverflow.Ellipsis)
        if(s.actualRoute!=null && !applied) Text("현재 확인한 경로: ${routeText(s.actualRoute)}",style=GakimType.Caption,maxLines=2)
        if(s.park==ParkState.UNKNOWN && !applied) PlannerPill(if(s.parkedConfirmed) "✓ 주차 중이에요" else "주차 중이에요 · 눌러서 확인",{c.setParked(!s.parkedConfirmed)},Modifier.fillMaxWidth(),enabled,s.parkedConfirmed)
        if(!applied) {
            val name=s.selected?.visits?.lastOrNull()?.place?.name
            if(name!=null) Text("차량 내비 목적지를 ${withRo(name)} 설정합니다",style=GakimType.Caption.copy(fontSize=20.sp),color=GakimColors.Text)
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            if(applied) Spacer(Modifier.weight(1f)) else PlannerStepMic(s,if(removed.isNotEmpty() && !s.acknowledged) "“들를게” · “거기만”" else "“안내 시작” · “다른 곳으로”",vm::toggleListening,Modifier.weight(1f),enabled)
            if(!applied) PlannerPill("← 다른 곳",vm::back,enabled=enabled)
            val recheck=s.phase==ApplyPhase.UNCONFIRMED
            PrimaryButton(when { applied -> "완료";s.busy -> "내비 확인 중…";recheck -> "다시 확인";removed.isNotEmpty() && !s.acknowledged -> "위에서 하나를 골라 주세요";else -> "이 경로로 안내" },{
                when { applied -> vm.edit { c.restart() };recheck -> vm.refresh();else -> vm.startGuidance() }
            },Modifier.width(380.dp),enabled=applied || enabled && (recheck || s.canStartGuidance()))
        }
    }
}

@Composable
internal fun RouteTimeline(s:PlannerState,modifier:Modifier=Modifier,compact:Boolean=false) {
    val option=s.selected
    Column(modifier.clip(CardShape).background(GakimColors.Surface).padding(if(compact) 8.dp else 20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
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
            val activity=if(visit.activity==Activity.ARRIVE && visit.stayMinutes==0) null else if(visit.activity==Activity.CHARGE) null else "${visit.activityName()} ${visit.stayMinutes}분"
            val details=listOfNotNull(activity,if(charging) option.chargeDetail(result.visit) else null).joinToString(" · ")
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
    val step=when(s.phase) { ApplyPhase.APPLIED -> 2;ApplyPhase.VERIFYING -> 1;else -> 0 }
    Row(Modifier.fillMaxWidth().clip(CardShape).background(GakimColors.Surface).padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        repeat(3) { index -> Dot(if(index<=step) GakimColors.Accent else GakimColors.Outline,10.dp) }
        Text(when(s.phase) {
            ApplyPhase.APPLIED -> "차량 내비에 경로가 설정됐어요"
            ApplyPhase.VERIFYING -> "내비가 경로를 계산하는 중…"
            ApplyPhase.FAILED -> "안내를 시작하지 못했어요"
            ApplyPhase.UNCONFIRMED -> "아직 경로 반영을 확인하지 못했어요"
            else -> "내비에 보내는 중…"
        },style=GakimType.BodyBold)
    }
}

@Composable
private fun RouteChoiceButton(title:String,subtitle:String,click:()->Unit,modifier:Modifier,enabled:Boolean,selected:Boolean) {
    Column(modifier.heightIn(min=96.dp).clip(CardShape).background(if(selected) GakimColors.Accent else GakimColors.Surface)
        .border(2.dp,if(selected) GakimColors.Accent else GakimColors.Outline,CardShape).clickable(enabled=enabled,onClick=click).padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        val color=if(selected) GakimColors.Background else if(enabled) GakimColors.Text else GakimColors.TextDim
        Text(title,style=GakimType.BodyBold,color=color)
        Text(subtitle,style=GakimType.Caption,color=color)
    }
}
