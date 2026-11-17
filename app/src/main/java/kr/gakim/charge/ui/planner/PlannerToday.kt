package kr.gakim.charge.ui.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import java.time.LocalDateTime
import kr.gakim.charge.data.*
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.screens.*
import kr.gakim.charge.ui.theme.*

@Composable
internal fun PlannerToday(s:PlannerState,vm:PlannerActions,open:(String)->Unit) {
    val c=vm.controller
    val enabled=!s.busy && s.park!=ParkState.MOVING
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    val visit=s.plan.visits.firstOrNull { it.id==editing }
    LaunchedEffect(editing) { c.focusSpeechVisit(editing) }
    DisposableEffect(c) { onDispose { c.focusSpeechVisit(null) } }
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1.75f).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(visit==null) PlannerAssistant(s)
                if(visit==null && s.presentationIssue()?.retry==true) PlannerPill("다시 시도",vm::refresh,enabled=enabled)
                if(visit==null) SurfaceCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal=18.dp,vertical=12.dp)) {
                        Text(if(s.listening) "지금 듣고 있어요" else if(s.transcript.isNotBlank()) "이렇게 들었어요" else "마이크를 누르고 말해 보세요",style=GakimType.Caption,color=GakimColors.Voice)
                        Text(if(s.listening) s.partial.ifBlank { "편하게 말씀해 주세요" } else clockText(s.transcript).ifBlank { "오른쪽 예시를 눌러 시작할 수도 있어요." },style=GakimType.Body,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
                if(visit==null) {
                    s.plan.departure?.let { Pill("출발 ${at(it,s.now)}",color=GakimColors.Voice) }
                    // 2026-11-17 평가(3팀): 말하기 전부터 '장보기 20분'이 들어 있어 이미 정한 일정처럼 보였다. 기본 칸은 숨긴다.
                    s.plan.visits.filterNot { it.placeholder }.forEachIndexed { index,v ->
                        VisitChip(index,v,s,enabled) { editing=v.id }
                    }
                    Box(Modifier.fillMaxWidth().height(64.dp).drawBehind {
                        drawRoundRect(GakimColors.Outline,style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(),pathEffect=androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(),8.dp.toPx()))),cornerRadius=androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))
                    }.clickable(enabled=enabled,role=Role.Button) { open("add") },contentAlignment=Alignment.Center) {
                        Text("+ 할 일",style=GakimType.BodyBold,color=GakimColors.Voice)
                    }
                    if(s.plan.visits.size<=2) SurfaceCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("지금 차량 · 배터리 ${s.energy.socPercent?.value?.let { "$it%" } ?: "미확인"} · 주행 가능 ${s.energy.usableRange(s.now)?.value?.toInt()?.let { "${it}km" } ?: "미확인"}",style=GakimType.CaptionBold)
                            // 2026-11-18 심사 지적: 가치가 회색 안내문 한 줄뿐이었다.
                            Text("충전소에 따로 들르지 마세요.",style=GakimType.Title)
                            Text("장 보고 밥 먹는 동안 충전되는 곳을 찾아 드려요.",style=GakimType.BodyBold)
                        }
                    }
                } else {
                    VisitEditorPanel(visit,s.now,enabled,{ minutes -> vm.edit { c.setStay(visit.id,minutes) } },open)
                }
            }
            if(visit==null) Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                MicOrb(s.listening,s.level,{if(enabled) vm.toggleListening()},Modifier.height(238.dp).semantics { contentDescription="음성 입력 마이크" },orbSize=200.dp,dimmed=!enabled)
                Text(if(s.listening) "듣고 있어요 · 눌러서 마치기" else "눌러서 말하기",style=GakimType.BodyBold,color=GakimColors.Voice)
                s.processingLine()?.let { Text(it,style=GakimType.Caption,color=GakimColors.Voice) }
                plannerExamples(s.now,s.remotePlacesEnabled,s.nearbyRealMarts,s.exampleDestination).forEach { sentence ->
                    Box(Modifier.fillMaxWidth().heightIn(min=76.dp).clip(CardShape).background(GakimColors.Surface).clickable(enabled,role=Role.Button) {
                        vm.edit { c.onSpeech(SpeechEvent.Final(sentence),"예시 문장") }
                    }.padding(18.dp),contentAlignment=Alignment.CenterStart) {
                        Text("“$sentence”",style=GakimType.Caption.copy(fontSize=20.sp,color=GakimColors.Text))
                    }
                }
            } else Column(Modifier.weight(1f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    s.plan.visits.forEachIndexed { index,v ->
                        Text("${index+1} ${v.label}",style=GakimType.Title,color=if(v.id==visit.id) GakimColors.Text else GakimColors.TextDim,
                            modifier=Modifier.weight(1f).heightIn(min=60.dp).clickable(enabled=enabled) { editing=v.id }.wrapContentHeight())
                    }
                }
                Text(if(s.listening) s.partial.ifBlank { "지금 듣고 있어요" } else s.message ?: "시간을 돌려 고르세요 · 5분 단위",
                    style=GakimType.Caption,maxLines=2,overflow=TextOverflow.Ellipsis)
                PlannerPill("말로 수정",vm::toggleListening,Modifier.fillMaxWidth(),enabled)
                Text("“30분으로” · “오후 3시까지” · “이마트로”",style=GakimType.Caption,color=GakimColors.Voice)
                Box(Modifier.fillMaxWidth().height(88.dp).clip(CardShape).background(if(enabled) GakimColors.Accent else GakimColors.SurfaceHigh)
                    .clickable(enabled=enabled,role=Role.Button) { editing=null },contentAlignment=Alignment.Center) {
                    Text("편집 마치기",style=GakimType.BodyBold,color=if(enabled) GakimColors.Background else GakimColors.TextDim)
                }
                if(s.plan.visits.size>1) {
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        PlannerPill("순서 앞으로",{vm.edit { c.move(visit.id,-1) }},Modifier.weight(1f),enabled && visit!=s.plan.visits.first())
                        PlannerPill("순서 뒤로",{vm.edit { c.move(visit.id,1) }},Modifier.weight(1f),enabled && visit!=s.plan.visits.last())
                    }
                    PlannerPill("방문 삭제",{vm.edit { c.remove(visit.id) };editing=null},Modifier.fillMaxWidth(),enabled,color=GakimColors.Late)
                }
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            PlannerPill("더보기",{open("more")},enabled=enabled)
            Spacer(Modifier.weight(1f))
            // 편집 중에는 주 버튼을 하나만 둔다(편집 마치기).
            if(visit==null) PrimaryButton("일정에 맞는 곳 비교 →",{vm.stopListening();c.compare()},Modifier.width(380.dp),enabled=enabled && s.hasRealVisit() && !s.loading && s.inputBlocker==null && s.pendingTime==null && s.question==null)
        }
    }
}

@Composable
private fun VisitChip(index:Int,visit:Visit,s:PlannerState,enabled:Boolean,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().height(76.dp).clip(CardShape).background(GakimColors.Surface).border(2.dp,GakimColors.Outline,CardShape)
        .clickable(enabled,role=Role.Button,onClick=onClick).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(GakimColors.SurfaceHigh),contentAlignment=Alignment.Center) { Text("${index+1}",style=GakimType.BodyBold,color=GakimColors.Accent) }
        Column(Modifier.weight(1f)) {
            Text("${visit.label} · ${visit.placeLabel()}",style=GakimType.BodyBold,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(visit.timeDetail(s.now),style=GakimType.Caption,maxLines=1)
        }
        Text("수정 ›",style=GakimType.CaptionBold,color=GakimColors.Voice)
    }
}

@Composable
internal fun VisitEditorPanel(v:Visit,now:LocalDateTime,enabled:Boolean,setStay:(Int)->Unit,open:(String)->Unit) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                PlannerPill(v.label,{open("kind:${v.id}")},Modifier.weight(1f),enabled)
                PlannerPill(v.place?.name ?: "장소 미정",{open("place:${v.id}")},Modifier.weight(1.4f),enabled)
                PlannerPill(v.deadline?.let { "${at(it,now)}까지 도착" } ?: "도착 시각 · 정하지 않음",{open("time:${v.id}")},Modifier.weight(1.5f),enabled)
            }
            TimeDial(v.stayMinutes,TimeDialMode.Duration,setStay,Modifier.fillMaxWidth(),enabled=enabled,dialSize=384.dp)
        }
    }
}

@Preview(widthDp=1280,heightDp=910,showBackground=true)
@Composable
private fun DurationEditorPreview() {
    val now=LocalDateTime.of(2026,11,16,13,0)
    var minutes by remember { mutableIntStateOf(85) }
    GakimTheme {
        Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("1번째 방문 · 머무는 시간",style=GakimType.Title)
            Row(horizontalArrangement=Arrangement.spacedBy(28.dp)) {
                Box(Modifier.weight(1.75f)) {
                    VisitEditorPanel(Visit(1,Activity.SHOP,stayMinutes=minutes,deadline=now.plusHours(3)),now,true,{minutes=it},{})
                }
                Column(Modifier.weight(1f)) {
                    PrimaryButton("편집 마치기",{},Modifier.fillMaxWidth())
                }
            }
        }
    }
}
