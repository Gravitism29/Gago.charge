package kr.gakim.charge.ui.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import java.time.LocalDateTime
import kr.gakim.charge.data.*
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.screens.*
import kr.gakim.charge.ui.theme.*

@Composable
internal fun PlannerToday(s:PlannerState,vm:PlannerViewModel,open:(String)->Unit) {
    val c=vm.controller
    val enabled=!s.busy && s.park!=ParkState.MOVING
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    val visit=s.plan.visits.firstOrNull { it.id==editing }
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1.75f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(visit==null) PlannerAssistant(s)
                if(visit==null) SurfaceCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal=18.dp,vertical=12.dp)) {
                        Text(if(s.listening) "지금 듣고 있어요" else "이렇게 들었어요",style=GakimType.Caption,color=GakimColors.Voice)
                        Text(if(s.listening) s.partial.ifBlank { "편하게 말씀해 주세요" } else s.transcript.ifBlank { "말씀하시거나 오른쪽 예시를 눌러 주세요." },style=GakimType.Body,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
                if(visit==null) {
                    s.plan.visits.forEachIndexed { index,v ->
                        VisitChip(index,v,s,enabled) { editing=v.id }
                    }
                } else {
                    VisitEditorPanel(visit,s.now,enabled,{ minutes -> vm.edit { c.setStay(visit.id,minutes) } },open)
                }
            }
            if(visit==null) Column(Modifier.weight(1f).fillMaxHeight(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                MicOrb(s.listening,s.level,{if(enabled) vm.toggleListening()},Modifier.height(238.dp).semantics { contentDescription="음성 입력 마이크" },orbSize=200.dp,dimmed=!enabled)
                Text(if(s.listening) "듣고 있어요 · 눌러서 마치기" else "눌러서 말하기",style=GakimType.BodyBold,color=GakimColors.Voice)
                plannerExamples(s.now).forEach { sentence ->
                    Box(Modifier.fillMaxWidth().height(76.dp).clip(CardShape).border(2.dp,GakimColors.Outline,CardShape).clickable(enabled,role=Role.Button) {
                        vm.edit { c.onSpeech(SpeechEvent.Final(sentence),"예시 문장") }
                    }.padding(horizontal=18.dp),contentAlignment=Alignment.CenterStart) {
                        Text("“$sentence”",style=GakimType.Caption.copy(color=GakimColors.Text),maxLines=2)
                    }
                }
            } else Column(Modifier.weight(1f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    s.plan.visits.forEachIndexed { index,v ->
                        PlannerPill("${index+1} ${v.label}",{editing=v.id},Modifier.weight(1f),enabled,v.id==visit.id)
                    }
                }
                Text(if(s.listening) s.partial.ifBlank { "지금 듣고 있어요" } else s.message ?: "시간을 돌려 고르세요 · 5분 단위",
                    style=GakimType.Caption,maxLines=2,overflow=TextOverflow.Ellipsis)
                PlannerPill("말로 수정",vm::toggleListening,Modifier.fillMaxWidth(),enabled)
                PlannerPill("편집 마치기",{editing=null},Modifier.fillMaxWidth(),enabled,true)
                if(s.plan.visits.size>1) {
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        PlannerPill("앞으로",{vm.edit { c.move(visit.id,-1) }},Modifier.weight(1f),enabled && visit!=s.plan.visits.first())
                        PlannerPill("뒤로",{vm.edit { c.move(visit.id,1) }},Modifier.weight(1f),enabled && visit!=s.plan.visits.last())
                    }
                    PlannerPill("방문 삭제",{vm.edit { c.remove(visit.id) };editing=null},Modifier.fillMaxWidth(),enabled,color=GakimColors.Late)
                }
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            PlannerPill("+ 할 일",{open("add")},Modifier.weight(1f),enabled)
            PlannerPill("출발 ${s.plan.departure?.let { at(it,s.now) } ?: "지금"}",{open("departure")},Modifier.weight(1f),enabled)
            PlannerPill("내비 일정 가져오기",{vm.edit { c.importRoute() }},Modifier.weight(1.25f),enabled)
            PlannerPill("다시 읽기",vm::refresh,Modifier.weight(.8f),enabled)
        }
        Text("현재 내비: ${routeText((s.currentRoute as? NaviRead.Known)?.route)}",style=GakimType.Caption,maxLines=1,overflow=TextOverflow.Ellipsis)
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            PlannerPill("문장으로 입력",{open("sentence")},enabled=enabled)
            PlannerPill(s.plan.chargeMode.driverLabel(),{open("charge")},Modifier.weight(1f),enabled)
            PrimaryButton("일정에 맞는 곳 비교 →",{vm.stopListening();c.compare()},Modifier.width(380.dp),enabled=enabled && !s.loading && s.inputBlocker==null && s.pendingTime==null && s.question==null)
        }
    }
}

@Composable
private fun VisitChip(index:Int,visit:Visit,s:PlannerState,enabled:Boolean,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().height(76.dp).clip(CardShape).background(GakimColors.Surface).border(2.dp,GakimColors.Outline,CardShape)
        .clickable(enabled,role=Role.Button,onClick=onClick).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(GakimColors.SurfaceHigh),contentAlignment=Alignment.Center) { Text("${index+1}",style=GakimType.BodyBold,color=GakimColors.Accent) }
        Column(Modifier.weight(1f)) {
            Text("${visit.label} · ${visit.place?.name ?: "장소 미정"}",style=GakimType.BodyBold,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("${visit.stayMinutes}분 · ${visit.deadline?.let { "${at(it,s.now)}까지" } ?: "시간 제약 없음"}",style=GakimType.Caption,maxLines=1)
        }
        Text("수정",style=GakimType.Caption,color=GakimColors.Voice)
    }
}

@Composable
internal fun VisitEditorPanel(v:Visit,now:LocalDateTime,enabled:Boolean,setStay:(Int)->Unit,open:(String)->Unit) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                PlannerPill(v.label,{open("kind:${v.id}")},Modifier.weight(1f),enabled)
                PlannerPill(v.place?.name ?: "장소 미정",{open("place:${v.id}")},Modifier.weight(1.4f),enabled)
                PlannerPill(v.deadline?.let { "${at(it,now)}까지 도착" } ?: "도착 시간 · 제약 없음",{open("time:${v.id}")},Modifier.weight(1.5f),enabled)
            }
            TimeDial(v.stayMinutes,TimeDialMode.Duration,setStay,Modifier.fillMaxWidth(),enabled=enabled)
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
                    PlannerPill("편집 마치기",{},Modifier.fillMaxWidth(),selected=true)
                }
            }
        }
    }
}
