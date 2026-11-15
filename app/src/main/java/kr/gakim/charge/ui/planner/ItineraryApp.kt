package kr.gakim.charge.ui.planner

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kr.gakim.charge.BuildConfig
import kr.gakim.charge.data.*
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.screens.*
import kr.gakim.charge.ui.theme.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private fun at(time: LocalDateTime?, now: LocalDateTime): String = time?.let {
    (if(it.toLocalDate() == now.toLocalDate()) "" else if(it.toLocalDate() == now.toLocalDate().plusDays(1)) "내일 " else "${it.monthValue}/${it.dayOfMonth} ") + it.format(hm)
} ?: "미확인"
private fun routeText(route: NaviRoute?) = "현재 위치 → " + (route?.let { it.waypoints + listOfNotNull(it.destination) }?.joinToString(" → ") { it.name }?.ifBlank { "목적지 없음" } ?: "경로 미확인")
private fun durationText(minutes: Int): String = if(minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분" else "${minutes}분"
private fun visitTimeText(visit: Visit): String = if(visit.activity == Activity.CHARGE) "보충 목표 시간 ${durationText(visit.stayMinutes)}" else durationText(visit.stayMinutes)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItineraryApp(s: PlannerState, vm: PlannerViewModel) {
    val c = vm.controller
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val edit: (() -> Unit) -> Unit = { vm.edit(it) }
    Column(Modifier.fillMaxSize().background(GakimColors.Background).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("ϟ 가는 김에 충전", style = GakimType.BodyBold, modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { if(BuildConfig.DEBUG) dialog = "dev" }))
            Text(listOf("① 일정", "② 비교", "③ 경로 확인").mapIndexed { i, label -> if(i == s.screen) "● $label" else label }.joinToString("  →  "), style = GakimType.Caption, modifier = Modifier.weight(1f))
            if(s.screen>0) TextButton(onClick=vm::toggleListening,enabled=!s.busy && s.park!=ParkState.MOVING,modifier=Modifier.heightIn(min=64.dp)) { Text(if(s.listening) "음성 중지" else "음성 수정",fontSize=20.sp) }
            Text("${s.energy.socPercent?.value?.let { "$it%" } ?: "잔량 미확인"} · ${s.energy.rangeKm?.value?.let { "%.0fkm".format(it) } ?: "거리 미확인"}", style = GakimType.Caption)
            Text(if(s.demoClock) "시연 13:00" else "현재 ${at(s.now,s.now)}", style = GakimType.Caption, color = if(s.demoClock) GakimColors.Demo else GakimColors.Text)
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if(s.usingFakes) "차량·내비 모의" else "Pleos SDK", style = GakimType.Caption)
            Text(if(s.demoRoutes) "장소·구간·충전기: 시연 데이터" else "경로: SDK 조회 · 없는 구간은 미확인", style = GakimType.Caption, color = GakimColors.Demo, modifier = Modifier.weight(1f))
            if(s.demoEnergy) Text("주행 거리·전비도 시연", style = GakimType.Caption, color = GakimColors.Demo)
            TextButton(onClick = { dialog = "evidence" }) { Text("출처 보기", fontSize = 20.sp) }
        }
        if(s.park == ParkState.MOVING) {
            DriveLockCard("주행 중에는 일정 수정과 경로 적용을 멈춥니다. 주차 후 이어서 이용해 주세요.")
        }
        if(s.screen>0 && (s.transcript.isNotBlank() || s.listening)) {
            Text(if(s.listening) "중간 인식 · 아직 미반영: ${s.partial.ifBlank { "말씀해 주세요" }}" else "${s.speechSource}: ${s.transcript}\n반영: ${s.applied.joinToString(" · ")}",
                style=GakimType.Caption,color=GakimColors.Voice,maxLines=3,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(bottom=8.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when(s.screen) {
                0 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Text("어떤 일을 보고 가실까요?", style = GakimType.Title)
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton("+ 할 일", { dialog = "add" })
                            SecondaryButton("출발 ${s.plan.departure?.let { at(it,s.now) } ?: "지금"}", { dialog = "departure" })
                            TextButton(onClick = { edit { c.importRoute() } }) { Text("내비 일정 가져오기", fontSize = 20.sp) }
                            TextButton(onClick = vm::refresh) { Text("다시 읽기",fontSize=20.sp) }
                        }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(s.plan.visits, key = { it.id }) { visit ->
                                val visitDescription = "${s.plan.visits.indexOf(visit)+1}번째 ${visit.label}"
                                val timeLabel = if(visit.activity == Activity.CHARGE) "보충 목표 시간" else "체류 시간"
                                SurfaceCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text("${s.plan.visits.indexOf(visit)+1}", style = GakimType.Title, color = GakimColors.Accent)
                                            SecondaryButton(visit.label, { dialog = "kind:${visit.id}" }, Modifier.semantics { contentDescription = "${visitDescription} 방문의 할 일 변경" })
                                            SecondaryButton(visit.place?.name ?: "장소 미정 · 후보 찾기", { dialog = "place:${visit.id}" }, Modifier.weight(1f).semantics { contentDescription = "${visitDescription} 방문 장소 변경, ${visit.place?.name ?: "장소 미정"}" })
                                            if(s.plan.visits.size > 1) TextButton(onClick = { edit { c.remove(visit.id) } }, modifier = Modifier.semantics { contentDescription = "${visitDescription} 방문 삭제" }) { Text("삭제", fontSize = 20.sp) }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Glyph(Icon.CLOCK,GakimColors.Text,24.dp)
                                            SecondaryButton("−5", { edit { c.setStay(visit.id,(visit.stayMinutes-5).coerceAtLeast(0)) } }, Modifier.semantics { contentDescription = "${visitDescription} ${timeLabel} 5분 줄이기" })
                                            SecondaryButton(durationText(visit.stayMinutes), { dialog = "stay:${visit.id}" }, Modifier.width(150.dp).semantics { contentDescription = "${visitDescription} ${timeLabel} ${durationText(visit.stayMinutes)} 변경" })
                                            SecondaryButton("+5", { edit { c.setStay(visit.id,(visit.stayMinutes+5).coerceAtMost(720)) } }, Modifier.semantics { contentDescription = "${visitDescription} ${timeLabel} 5분 늘리기" })
                                            TextButton(onClick = { dialog = "time:${visit.id}" }, modifier = Modifier.weight(1f).semantics { contentDescription = "${visitDescription} 도착 마감 변경" }) {
                                                Text(visit.deadline?.let { "${at(it,s.now)}까지 도착" } ?: "시간 제약 없음", fontSize = 20.sp)
                                            }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text((if(visit.activity == Activity.CHARGE) "보충 목표 시간 · " else "") + (visit.place?.let { if(it.demo) "시연 장소 · 좌표 별도 지정" else it.source } ?: "장소를 지정하면 그곳을 유지하고, 미정이면 후보를 비교해요."), style = GakimType.Caption, modifier = Modifier.weight(1f))
                                            if(s.plan.visits.size > 1) {
                                                TextButton(onClick = { edit { c.move(visit.id,-1) } }, enabled = visit != s.plan.visits.first(), modifier = Modifier.semantics { contentDescription = "${visitDescription} 방문을 앞으로 이동" }) { Text("앞으로", fontSize = 20.sp) }
                                                TextButton(onClick = { edit { c.move(visit.id,1) } }, enabled = visit != s.plan.visits.last(), modifier = Modifier.semantics { contentDescription = "${visitDescription} 방문을 뒤로 이동" }) { Text("뒤로", fontSize = 20.sp) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Text("현재 내비: ${(s.currentRoute as? NaviRead.Known)?.route?.let(::routeText) ?: (s.currentRoute as? NaviRead.Unknown)?.reason ?: "읽는 중"}", style = GakimType.Caption, maxLines = 2, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    Column(Modifier.width(310.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        MicOrb(s.listening,s.level,vm::toggleListening,modifier=Modifier.semantics { contentDescription="음성 입력 마이크" },orbSize = 124.dp,dimmed = s.park == ParkState.MOVING)
                        Text(if(s.listening) "듣고 있어요 · 다시 누르면 중지" else "눌러서 말하기", style = GakimType.BodyBold,modifier=Modifier.heightIn(min=64.dp).clickable { vm.toggleListening() })
                        Text("“은평점에서 25분 장 보고\n4시 10분까지 학교 가야 해”", style = GakimType.Caption, modifier = Modifier.padding(vertical = 14.dp))
                        SpeechReceipt(s)
                        TextButton(onClick = { dialog = "sentence" }) { Text("문장으로 입력", fontSize = 20.sp) }
                    }
                }
                1 -> Compare(s,vm) { dialog = it }
                else -> Guide(s,vm)
            }
        }
        if(s.departureExpired) {
            Row(Modifier.fillMaxWidth().heightIn(min=64.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("예정 출발이 지났어요. 출발 시각을 다시 정해 주세요.", style=GakimType.Caption, color=GakimColors.Tight, modifier=Modifier.weight(1f))
                TextButton(onClick={ edit { c.setTime(null,null) } },enabled=!s.busy && s.park != ParkState.MOVING) { Text("지금 출발",fontSize=20.sp) }
                TextButton(onClick={ dialog="departure" },enabled=!s.busy && s.park != ParkState.MOVING) { Text("시각 변경",fontSize=20.sp) }
            }
        }
        (s.message ?: s.inputBlocker)?.let { Text(it, style = GakimType.Caption, color = GakimColors.Tight, maxLines = 3, modifier = Modifier.padding(top = 6.dp)) }
        if(s.screen == 1 && s.selected == null && !s.departureExpired && s.options.isNotEmpty())
            Text("장소와 충전 방식을 선택해 주세요.",style=GakimType.Caption,color=GakimColors.Tight)
        Row(Modifier.fillMaxWidth().height(92.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if(s.screen > 0) SecondaryButton("← 이전", vm::back, enabled = !s.busy)
            if(s.screen < 2) {
                TextButton(onClick = { dialog = "charge" }, modifier = Modifier.weight(1f)) { Text("ϟ ${s.plan.chargeMode.label} · 도착 후 여유 ${s.plan.reserveKm.toInt()}km", fontSize = 22.sp) }
                if(s.screen == 0) PrimaryButton(if(s.loading) "차량 확인 중…" else "일정에 맞는 곳 비교 →", { vm.stopListening(); c.compare() }, Modifier.width(350.dp), enabled = !s.loading && !s.departureExpired && s.park != ParkState.MOVING && s.pendingTime == null && s.question == null)
                else PrimaryButton(if(s.selected != null && !s.selectionExplicit) "추천 경로 확인 →" else "선택한 경로 확인 →", { vm.stopListening(); c.guide() }, Modifier.width(350.dp), enabled = !s.departureExpired && s.selected?.selectable == true && s.park != ParkState.MOVING)
            } else {
                Spacer(Modifier.weight(1f))
                if(s.phase == ApplyPhase.APPLIED) PrimaryButton("일정으로 돌아가기", { c.back(); c.back() }, Modifier.width(380.dp))
                else PrimaryButton(if(s.busy) "반영 확인 중…" else "확인한 경로를 차량 내비에 적용", vm::confirm, Modifier.width(520.dp), enabled = !s.busy && !s.departureExpired && s.selected?.selectable == true && s.phase != ApplyPhase.UNCONFIRMED && s.acknowledged && s.park != ParkState.MOVING && (s.park == ParkState.PARKED || s.parkedConfirmed))
            }
        }
    }
    s.pendingImport?.let { route ->
        AlertDialog(onDismissRequest = { c.confirmImport(false) }, title = { Text("내비 일정 가져오기") },
            text = { Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("현재 작성한 일정", style=GakimType.BodyBold)
                s.plan.visits.forEachIndexed { index, visit -> Text("${index+1}. ${visit.label} · ${visit.place?.name ?: "장소 미정"} · ${visitTimeText(visit)} · ${visit.deadline?.let { "${at(it,s.now)}까지 도착" } ?: "마감 없음"}",style=GakimType.Body) }
                Text("가져올 내비 경로",style=GakimType.BodyBold)
                Text(routeText(route),style=GakimType.Body)
                Text("가져오면 현재 방문의 순서와 장소가 바뀌며 기존 할 일·체류·마감이 교체될 수 있어요. 계속할까요?",style=GakimType.Body,color=GakimColors.Tight)
            } },
            confirmButton = { TextButton(onClick={ c.confirmImport(true) }) { Text("일정 가져오기",fontSize=22.sp) } },
            dismissButton = { TextButton(onClick={ c.confirmImport(false) }) { Text("취소",fontSize=22.sp) } })
    }
    s.pendingTime?.let { pending ->
        AlertDialog(onDismissRequest = { edit { c.resolvePastTime(null) } }, title = { Text("${at(pending.at,s.now)}는 이미 지났어요") },
            text = { Text("오늘의 지난 일정인가요, 내일 도착할 일정인가요? 장소와 다른 입력은 유지합니다.", style = GakimType.Body) },
            confirmButton = { TextButton(onClick = { edit { c.resolvePastTime(true) } }) { Text("내일",fontSize=22.sp) } },
            dismissButton = { Row { TextButton(onClick = { edit { c.resolvePastTime(false) } }) { Text("오늘",fontSize=22.sp) }; TextButton(onClick = { edit { c.resolvePastTime(null) } }) { Text("다시 입력",fontSize=22.sp) } } })
    }
    s.question?.let { q ->
        AlertDialog(onDismissRequest = c::clearQuestion, title = { Text(q.message) }, text = {
            Column { if(q.timeRole) {
                SecondaryButton("출발 시각", { edit { c.resolveTimeRole(true) } }, Modifier.fillMaxWidth())
                SecondaryButton("마지막 장소 도착 마감", { edit { c.resolveTimeRole(false) } }, Modifier.fillMaxWidth())
            }; q.places.forEach { p -> SecondaryButton(p.name + " · " + p.address, { edit { q.visitId?.let { c.setPlace(it,p) } } }, Modifier.fillMaxWidth()) }
                q.timeChoices.forEach { time -> SecondaryButton("${at(time,s.now)} ${if(q.visitId == null) "출발" else "도착"}", { edit { c.resolveTimeChoice(q.visitId,time) } },Modifier.fillMaxWidth()) }
            }
        }, confirmButton = { TextButton(onClick = c::clearQuestion) { Text("화면에서 수정") } })
    }
    val open = dialog
    if(open != null) key(open) {
        val close = { dialog = null }
        val id = open.substringAfter(':',"").toLongOrNull()
        val visit = s.plan.visits.firstOrNull { it.id == id }
        when {
            open == "add" || open.startsWith("kind:") -> ActivityDialog(close) { kind,label -> edit { if(visit == null) c.add(kind,label) else c.setActivity(visit.id,kind,label) }; close() }
            open.startsWith("stay:") && visit != null -> StayDialog(visit,close) { edit { c.setStay(visit.id,it) }; close() }
            open.startsWith("place:") && visit != null -> PlaceDialog(visit,c.allPlaces(visit),close,{vm.searchPlace(it);close()}) { edit { c.setPlace(visit.id,it) }; close() }
            open.startsWith("time:") && visit != null -> ClockDialog("${visit.place?.name ?: visit.label + " 장소"}에 몇 시까지 도착할까요?",visit.deadline,s.now,true,close) { edit { c.setTime(visit.id,it) }; close() }
            open == "departure" -> ClockDialog("언제 출발할까요?",s.plan.departure,s.now,false,close) { edit { c.setTime(null,it) }; close() }
            open == "charge" -> ChargeDialog(s,close) { mode,reserve -> edit { c.setCharge(mode); c.setReserve(reserve) }; close() }
            open == "sentence" -> SentenceDialog(close) { text -> edit { c.acceptTranscript(text,"직접 입력 문장") }; close() }
            open == "dev" -> DevDialog(s,vm,close)
            open == "evidence" -> EvidenceDialog(s,close)
            open.startsWith("details:") -> {
                val option = s.options.firstOrNull { it.id == open.removePrefix("details:") }
                if(option != null) EvidenceDialog(s,close,option)
                else AlertDialog(onDismissRequest=close,title={Text("선택지가 변경됐어요")},text={Text("비교 화면에서 조건을 다시 확인해 주세요.")},confirmButton={TextButton(onClick=close){Text("닫기")}})
            }
            else -> close()
        }
    }
}

@Composable private fun ActivityDialog(close: ()->Unit, choose: (Activity,String)->Unit) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=close,title={Text("할 일 선택")},text={
        Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            (listOf(Activity.CHARGE) + Activity.entries.filter { it != Activity.CUSTOM && it != Activity.CHARGE }).forEach { kind -> SecondaryButton(kind.label,{choose(kind,"")},Modifier.fillMaxWidth()) }
            OutlinedTextField(custom,{custom=it},label={Text("직접 입력할 볼일")},singleLine=true,textStyle=GakimType.Body)
        }
    },confirmButton={TextButton(onClick={choose(Activity.CUSTOM,custom.trim())},enabled=custom.isNotBlank()){Text("직접 입력으로 사용",fontSize=22.sp)}},dismissButton={TextButton(onClick=close){Text("닫기")}})
}

@Composable private fun StayDialog(visit: Visit, close: ()->Unit, apply: (Int)->Unit) {
    var draft by remember { mutableStateOf(visit.stayMinutes.toString()) }
    val value = draft.toIntOrNull()
    AlertDialog(onDismissRequest=close,title={Text(if(visit.activity == Activity.CHARGE) "${visit.label} · 보충 목표 시간" else "${visit.label} · 머무는 시간")},text={
        Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(value?.let(::durationText) ?: "분 단위로 입력",style=GakimType.Title)
            OutlinedTextField(draft,{draft=it.filter(Char::isDigit).take(3)},label={Text("분 · 0~720")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,textStyle=GakimType.Body,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(15,30,60,90).forEach { m-> ChoiceChip(durationText(m),value==m,{draft=m.toString()},Modifier.weight(1f)) } }
            Text(if(visit.activity == Activity.CHARGE) "‘볼일 중 보충’은 이 시간을 보충 목표로 삼아요. ‘필요한 양까지’는 필요 시간을 따로 계산하며, 목표 시간보다 오래 걸리면 조건에 표시합니다." else "화면의 −5 / +5로 조금씩, 여기서는 55분이나 90분을 바로 입력해요.",style=GakimType.Caption)
        }
    },confirmButton={TextButton(onClick={value?.let(apply)},enabled=value!=null && value in 0..720){Text("시간 적용",fontSize=22.sp)}},dismissButton={TextButton(onClick=close){Text("취소",fontSize=22.sp)}})
}

@Composable private fun ClockDialog(title: String, initial: LocalDateTime?, now: LocalDateTime, deadline: Boolean, close: ()->Unit, apply:(LocalDateTime?)->Unit) {
    val base = initial ?: now.plusHours(1)
    var hour by remember { mutableStateOf(base.hour.toString().padStart(2,'0')) }
    var minute by remember { mutableStateOf(base.minute.toString().padStart(2,'0')) }
    var tomorrow by remember { mutableStateOf(base.toLocalDate().isAfter(now.toLocalDate())) }
    val h = hour.toIntOrNull(); val m = minute.toIntOrNull()
    AlertDialog(onDismissRequest=close,title={Text(title)},text={
        Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("현재 ${at(now,now)} · 24시간 표기",style=GakimType.Body)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ChoiceChip("오늘",!tomorrow,{tomorrow=false},Modifier.weight(1f));ChoiceChip("내일",tomorrow,{tomorrow=true},Modifier.weight(1f))
            }
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(hour,{hour=it.filter(Char::isDigit).take(2)},label={Text("시 · 00~23")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,textStyle=GakimType.Title,modifier=Modifier.weight(1f))
                Text(":",style=GakimType.Title)
                OutlinedTextField(minute,{minute=it.filter(Char::isDigit).take(2)},label={Text("분 · 00~59")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,textStyle=GakimType.Title,modifier=Modifier.weight(1f))
            }
            Text("시·분을 직접 입력합니다. 이미 지난 오늘 시각은 날짜를 한 번 더 확인해요.",style=GakimType.Caption)
            SecondaryButton(if(deadline) "시간 제약 없음" else "지금 출발",{apply(null)},Modifier.fillMaxWidth())
        }
    },confirmButton={TextButton(onClick={if(h!=null && m!=null) apply(now.toLocalDate().plusDays(if(tomorrow)1 else 0).atTime(h,m))},enabled=h!=null && h in 0..23 && m!=null && m in 0..59){Text("시각 적용",fontSize=22.sp)}},dismissButton={TextButton(onClick=close){Text("취소",fontSize=22.sp)}})
}

@Composable private fun PlaceDialog(visit: Visit, places: List<Place>, close: ()->Unit, search:(String)->Unit, choose:(Place?)->Unit) {
    val listState=rememberLazyListState()
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") };var lat by remember { mutableStateOf("") };var lon by remember { mutableStateOf("") }
    val point = Place("manual:$lat,$lon",name.trim(),lat.toDoubleOrNull() ?: Double.NaN,lon.toDoubleOrNull() ?: Double.NaN,source="사용자 직접 지정")
    AlertDialog(onDismissRequest=close,title={Text("${visit.label} 장소")},text={
        Column(Modifier.heightIn(max=550.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(visit.activity == Activity.CHARGE) Text("충전 장소를 내비에서 찾거나 직접 지정해 주세요. 실제 충전기 상태는 별도 확인이 필요해요.",style=GakimType.Caption)
            if(manual) {
                OutlinedTextField(name,{name=it},label={Text("장소 이름")},singleLine=true,textStyle=GakimType.Body)
                OutlinedTextField(lat,{lat=it},label={Text("위도 · -90~90")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,textStyle=GakimType.Body)
                OutlinedTextField(lon,{lon=it},label={Text("경도 · -180~180")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,textStyle=GakimType.Body)
                Text("입력한 좌표를 그대로 사용합니다. 위치가 불명확하면 내비에서 장소를 선택한 뒤 ‘내비 일정 가져오기’를 이용해 주세요.",style=GakimType.Caption)
            } else {
                OutlinedTextField(query,{query=it},label={Text("내비 저장 장소·시연 목록에서 찾기")},singleLine=true,textStyle=GakimType.Body,modifier=Modifier.fillMaxWidth())
                val found = places.filter { query.isBlank() || it.name.contains(query,true) || it.address.contains(query,true) }
                if(found.isEmpty()) Text("저장 장소와 시연 목록에 결과가 없어요. 차량 내비에서 검색하거나 직접 지정해 주세요.",style=GakimType.Caption)
                LazyColumn(Modifier.heightIn(max=320.dp),state=listState,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(found,key={it.id}) { p ->
                        Column(Modifier.fillMaxWidth().heightIn(min=76.dp).clickable { choose(p) }.padding(10.dp)) {
                            Text(p.name,style=GakimType.BodyBold)
                            Text(if(p.demo) "시연 장소 · ${p.latitude}, ${p.longitude}" else "${p.source} · ${p.address}",style=GakimType.Caption)
                        }
                    }
                }
                SecondaryButton("차량 내비에서 장소 검색",{search(query)},Modifier.fillMaxWidth())
                Text("차량 내비로 이동합니다. 돌아와 다시 읽은 장소를 선택해 주세요. 전체 일정을 가져오면 기존 일정이 바뀔 수 있어 확인합니다.",style=GakimType.Caption)
                SecondaryButton("이름·좌표로 직접 지정",{manual=true},Modifier.fillMaxWidth())
                SecondaryButton("장소 미정 · 활동 후보 비교",{choose(null)},Modifier.fillMaxWidth())
            }
        }
    },confirmButton={if(manual) TextButton(onClick={choose(point)},enabled=name.isNotBlank() && point.validCoordinates){Text("이 장소 사용",fontSize=22.sp)}},dismissButton={TextButton(onClick=close){Text("닫기",fontSize=22.sp)}})
}

@Composable private fun ChargeDialog(s: PlannerState, close:()->Unit, apply:(ChargeMode,Double)->Unit) {
    var mode by remember { mutableStateOf(s.plan.chargeMode) };var reserve by remember { mutableStateOf(s.plan.reserveKm.toInt().toString()) }
    AlertDialog(onDismissRequest=close,title={Text("충전과 도착 후 여유")},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            ChargeMode.entries.forEach { choice-> ChoiceChip(choice.label,mode==choice,{mode=choice},Modifier.fillMaxWidth()) }
            OutlinedTextField(reserve,{reserve=it.filter(Char::isDigit).take(3)},label={Text("도착 후 남겨 둘 주행 거리 · km")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,textStyle=GakimType.Body)
            Text("배터리 %를 거리로 환산하지 않습니다. 거리 또는 충전 시간의 근거가 없으면 미확인으로 표시해요.",style=GakimType.Caption)
        }
    },confirmButton={TextButton(onClick={reserve.toDoubleOrNull()?.let { apply(mode,it) }},enabled=reserve.toDoubleOrNull()?.let { it in 0.0..200.0 }==true){Text("적용",fontSize=22.sp)}},dismissButton={TextButton(onClick=close){Text("닫기")}})
}

@Composable private fun SentenceDialog(close:()->Unit, apply:(String)->Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=close,title={Text("일정을 문장으로 입력")},text={OutlinedTextField(text,{text=it},label={Text("예: 은평점에서 25분 장 보고 4시 10분까지 학교 가야 해")},textStyle=GakimType.Body,minLines=3)},confirmButton={TextButton(onClick={apply(text)},enabled=text.isNotBlank()){Text("일정에 반영",fontSize=22.sp)}},dismissButton={TextButton(onClick=close){Text("취소")}})
}

@Composable private fun EvidenceDialog(s: PlannerState, close:()->Unit, option: Option? = null) {
    val evidence = if(option != null) option.evidence.distinct() else
        (s.options.flatMap { it.evidence } + listOfNotNull(s.energy.rangeKm?.evidence,s.energy.kmPerKwh?.evidence,s.energy.socPercent?.evidence)).distinct()
    val chargePlace = option?.chargeVisitId?.let { id -> option.visits.firstOrNull { it.id == id }?.place?.name }
    AlertDialog(onDismissRequest=close,title={Text(if(option == null) "전체 계산 근거와 갱신 시각" else "선택지의 조건과 출처")},text={
        Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(option != null) {
                Text("방문: ${option.visits.joinToString(" → ") { it.place?.name ?: it.label }}",style=GakimType.BodyBold)
                Text("${option.mode.label}${chargePlace?.let { " · 충전 위치 $it" } ?: ""}",style=GakimType.Body)
                if(option.conditions.isEmpty()) Text("표시할 추가 조건이 없어요.",style=GakimType.Body)
                option.conditions.forEach { Text(it,style=GakimType.Body) }
            } else Text("전체 선택지의 출처입니다. 시연은 실제 조회 결과와 구분합니다.",style=GakimType.Body)
            if(evidence.isEmpty()) Text("계산에 사용할 근거를 아직 받지 못했어요.",style=GakimType.Body)
            evidence.forEach { e-> Text("${if(e.demo) "[시연] " else "[조회] "}${e.source}\n확인 ${at(e.observedAt,s.now)}${if(!e.fresh(s.now,600)) " · 10분 기준 오래됨" else ""}",style=GakimType.Body) }
            if(option == null) Text("내비 경로 조회 ${s.routeReadAt?.let { at(it,s.now) } ?: "미수신"}",style=GakimType.Body)
        }
    },confirmButton={TextButton(onClick=close){Text("닫기",fontSize=22.sp)}})
}

@Composable private fun DevDialog(s:PlannerState,vm:PlannerViewModel,close:()->Unit) {
    val c=vm.controller
    AlertDialog(onDismissRequest=close,title={Text("개발 확인 · 시연 표시 유지")},text={
        Column(Modifier.heightIn(max=550.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) { Text("실제 SDK",style=GakimType.Body,modifier=Modifier.weight(1f));Switch(!s.usingFakes,{vm.useFakes(!it)}) }
            Row(verticalAlignment=Alignment.CenterVertically) { Text("시연 구간·충전기 값",style=GakimType.Body,modifier=Modifier.weight(1f));Switch(s.demoRoutes,{vm.edit { c.setDemoRoutes(it) }}) }
            Row(verticalAlignment=Alignment.CenterVertically) { Text("시연 거리·전비",style=GakimType.Body,modifier=Modifier.weight(1f));Switch(s.demoEnergy,{vm.edit { c.setDemoEnergy(it) }}) }
            Row(verticalAlignment=Alignment.CenterVertically) { Text("시연 현재 시각 13:00",style=GakimType.Body,modifier=Modifier.weight(1f));Switch(s.demoClock,{vm.edit { c.setDemoClock(it) }}) }
            SecondaryButton("차량·내비 다시 읽기",vm::refresh,Modifier.fillMaxWidth())
            SecondaryButton("데이터 미수신 주입",{vm.debugAction("missing")},Modifier.fillMaxWidth())
            SecondaryButton("경로 전송 실패 주입",{vm.debugAction("fail")},Modifier.fillMaxWidth())
            SecondaryButton("주입 해제 · 다시 읽기",{vm.debugAction("recover")},Modifier.fillMaxWidth())
        }
    },confirmButton={TextButton(onClick=close){Text("닫기",fontSize=22.sp)}})
}

@Composable private fun SpeechReceipt(s: PlannerState) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if(s.listening) "중간 인식 · 아직 일정에 반영 안 함" else "${s.speechSource.ifBlank { "인식한 문장" }}", style = GakimType.Caption, color = GakimColors.Voice)
            Text(if(s.listening) s.partial.ifBlank { "말씀해 주세요" } else s.transcript.ifBlank { "말씀하신 문장과 반영 항목을 여기에 보여드려요." }, style = GakimType.Body)
            if(!s.listening && s.applied.isNotEmpty()) Text("반영: ${s.applied.joinToString(" · ")}", style = GakimType.Caption, color = GakimColors.Accent)
        }
    }
}

@Composable private fun Compare(s: PlannerState, vm: PlannerViewModel, open: (String)->Unit) {
    val c = vm.controller
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("일정과 충전 비교", style = GakimType.Title)
        Text("출발 ${at(s.plan.departure ?: s.now,s.now)} · ${s.plan.visits.joinToString(" → ") { it.place?.name ?: it.label + " 장소 찾기" }}", style = GakimType.Body)
        if(s.options.isEmpty()) Text(s.inputBlocker ?: "비교할 장소가 없어요. 이전 화면에서 장소를 지정해 주세요.", style = GakimType.Body, modifier = Modifier.padding(24.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(s.options.groupBy { it.visits.map { v -> v.place?.id } }.values.toList()) { options ->
                val groupKey = options.first().visits.joinToString("|") { "${it.id}:${it.place?.id}" }
                var previewId by rememberSaveable(groupKey) { mutableStateOf<String?>(null) }
                val selectedOption = options.firstOrNull { it.id == s.selectedId }
                val chosen = selectedOption ?: options.firstOrNull { it.id == previewId } ?: options.firstOrNull { it.mode == ChargeMode.NONE } ?: options.first()
                val selected = selectedOption != null
                val chargeOptions = options.filter { it.mode != ChargeMode.NONE && it.chargeVisitId != null }
                val locations = chosen.visits.filter { visit -> chargeOptions.any { it.chargeVisitId == visit.id } }
                val focusId = chosen.chargeVisitId ?: chargeOptions.firstOrNull()?.chargeVisitId
                val place = chosen.visits.firstOrNull { v -> s.plan.visits.firstOrNull { it.id == v.id }?.place == null }?.place ?: chosen.visits.first().place
                fun inspect(option: Option) {
                    vm.stopListening()
                    previewId = option.id
                    if(option.selectable && !s.departureExpired && s.park != ParkState.MOVING) c.select(option.id) else c.clearSelection()
                }
                SurfaceCard(Modifier.fillMaxWidth().then(if(selected) Modifier.border(2.dp,GakimColors.Accent,RoundedCornerShape(28.dp)) else Modifier)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Glyph(Icon.PIN,GakimColors.Accent,28.dp)
                            Text(place?.name ?: "장소 미확인", style = GakimType.Title, modifier = Modifier.weight(1f))
                            if(selected) Text(if(s.selectionExplicit) "✓ 선택됨" else "추천", style = GakimType.BodyBold, color = GakimColors.Accent)
                            TextButton(onClick = { open("details:${chosen.id}") }) { Text("조건·출처",fontSize = 20.sp) }
                        }
                        val chargePlace = chosen.chargeVisitId?.let { id -> chosen.visits.firstOrNull { it.id == id }?.place?.name }
                        Text("${chosen.mode.label}${chargePlace?.let { " · 충전 위치 $it" } ?: ""}${if(!selected) " · 비교 중" else ""}",style=GakimType.BodyBold)
                        val critical = chosen.critical
                        val line = if(s.departureExpired) "출발 시각 경과 · 도착 예상 미확인"
                            else if(critical?.visit?.deadline != null) "${critical.visit.place?.name ?: critical.visit.label} ${at(critical.arrival,s.now)} 도착 · 마감 ${at(critical.visit.deadline,s.now)} · ${critical.slackMinutes?.let { if(it < 0) "${-it}분 늦음" else "${it}분 여유" } ?: "여유 미확인"}"
                            else "${at(chosen.results.lastOrNull()?.finish,s.now)} 볼일 마침 · 전체 ${chosen.totalMinutes?.let { "${it}분" } ?: "시간 미확인"}"
                        Text(line, style = GakimType.Title, color = if(chosen.late && !s.departureExpired) GakimColors.Late else GakimColors.Text)
                        if(chosen.results.any { it.visit.deadline != null && it.slackMinutes == null }) Text("일부 방문의 마감 여유는 확인되지 않았어요.",style=GakimType.Caption,color=GakimColors.Tight)
                        val status = if(chosen.mode == ChargeMode.NONE) "충전 안 함 · 충전 시설 상태는 별도 확인" else when(chosen.chargingStatus) {
                            Availability.AVAILABLE -> "사용 가능으로 표시됨 · 현장 상태는 달라질 수 있어요"
                            Availability.UNAVAILABLE -> "사용 가능한 충전기 없음"
                            Availability.INCOMPATIBLE -> "차량과 충전기 호환 불가"
                            Availability.UNKNOWN, null -> "충전기 상태 미확인 · 현장 확인 필요"
                        }
                        Text("ϟ $status",style=GakimType.Body,color=GakimColors.Voice)
                        if(locations.size > 1) {
                            Text("충전 위치 · 비교할 곳",style=GakimType.CaptionBold)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                locations.forEach { visit ->
                                    val target = chargeOptions.firstOrNull { it.chargeVisitId == visit.id && it.mode == chosen.mode }
                                        ?: chargeOptions.first { it.chargeVisitId == visit.id }
                                    ChoiceChip(visit.place?.name ?: visit.label, focusId == visit.id,
                                        { inspect(target) },Modifier.weight(1f),enabled=!s.busy && !s.departureExpired && s.park != ParkState.MOVING)
                                }
                            }
                        }
                        val modeOptions = options.filter { it.mode == ChargeMode.NONE || it.chargeVisitId == focusId }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            modeOptions.forEach { option -> ChoiceChip(option.mode.label + if(!option.selectable) " · 불가" else "", s.selectionExplicit && s.selectedId == option.id,
                                { inspect(option) }, Modifier.weight(1f), enabled = option.selectable && !s.busy && !s.departureExpired && s.park != ParkState.MOVING) }
                        }
                        modeOptions.filterNot { it.selectable }.forEach { option ->
                            Text("${option.mode.label} 선택 불가 · ${option.conditions.joinToString(" · ").ifBlank { "조건을 확인해 주세요" }}",style=GakimType.Caption,color=GakimColors.Tight)
                        }
                        if(!chosen.selectable) Text("이 방식은 선택할 수 없어요. 조건을 확인하거나 다른 방식을 골라 주세요.",style=GakimType.Caption,color=GakimColors.Tight)
                        val visitTimes = if(chosen.visits.any { it.activity == Activity.CHARGE }) chosen.visits.joinToString(" · ") { if(it.activity == Activity.CHARGE) visitTimeText(it) else "체류 ${durationText(it.stayMinutes)}" } else "체류 ${chosen.visits.joinToString(" + ") { "${it.stayMinutes}분" }}"
                        Text("$visitTimes · 이동 ${chosen.totalDistanceKm?.let { "%.1fkm".format(it) } ?: "거리 미확인"}${if(chosen.mode != ChargeMode.NONE) " · 충전 ${chosen.chargeMinutes?.let { "${it}분" } ?: "시간 미확인"} · 추가 ${chosen.extraMinutes?.let { "${it}분" } ?: "미확인"}" else ""}",style=GakimType.Body)
                        Text(chosen.energy.note,style=GakimType.Caption,color=if(chosen.energy.status == EnergyStatus.OPTIONAL) GakimColors.Accent else GakimColors.Tight)
                        chosen.conditions.forEach { Text(it,style=GakimType.Caption,color=GakimColors.Tight) }
                        if(chosen.evidence.any { it.demo }) Text("시연 계산 · 실제 교통·시설 정보와 다를 수 있어요",style=GakimType.Caption,color=GakimColors.Demo)
                    }
                }
            }
        }
    }
}

@Composable private fun Guide(s: PlannerState, vm: PlannerViewModel) {
    val c = vm.controller
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text(if(s.phase == ApplyPhase.APPLIED) "차량 내비에 반영됐어요" else "이 방문 순서로 바꿀까요?",style=GakimType.Title)
        Text("현재 내비",style=GakimType.Caption)
        Text(routeText((s.currentRoute as? NaviRead.Known)?.route),style=GakimType.Body)
        SurfaceCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text("적용할 경로",style=GakimType.Caption,color=GakimColors.Accent)
                Text("현재 위치 → " + s.selected?.visits?.joinToString(" → ") { it.place?.name ?: "장소 미정" },style=GakimType.Title)
                s.selected?.let { option -> option.visits.forEachIndexed { index, v -> Text("${index+1}. ${if(index == option.visits.lastIndex) "목적지" else "경유"} ${v.place?.name ?: "장소 미정"} · ${visitTimeText(v)}${v.deadline?.let { " · ${at(it,s.now)}까지 도착" } ?: " · 시간 제약 없음"}",style=GakimType.Body) } }
                val chargeName = s.selected?.let { option -> option.chargeVisitId?.let { id -> option.visits.firstOrNull { it.id == id }?.place?.name } }
                Text((s.selected?.mode?.label ?: "") + (chargeName?.let { " · 충전 위치 $it" } ?: ""),style=GakimType.BodyBold,color=GakimColors.Voice)
                s.selected?.conditions?.forEach { Text(it,style=GakimType.Body,color=GakimColors.Tight) }
                if(s.selected?.late == true) Text("도착 마감에 늦는 경로입니다. 시간 또는 방문 순서를 다시 확인해 주세요.",style=GakimType.Body,color=GakimColors.Late)
            }
        }
        val oldPlaces = (s.currentRoute as? NaviRead.Known)?.route?.let { it.waypoints + listOfNotNull(it.destination) }.orEmpty()
        val plannedPlaces = s.selected?.visits?.mapNotNull { it.place?.toNavi() }.orEmpty()
        val uncertain = oldPlaces.filter { it.poiId.isBlank() && it.latitude == 0.0 && it.longitude == 0.0 }
        val removed = if(s.selected == null) emptyList() else oldPlaces.filterNot { old -> old in uncertain || plannedPlaces.any { PlaceMatcher.samePlace(old,it) } }
        if(removed.isNotEmpty()) Text("새 경로에서 빠지는 기존 장소: ${removed.joinToString(" · ") { it.name + if(it.address.isNotBlank()) " (${it.address})" else "" }}",style=GakimType.Body,color=GakimColors.Tight)
        if(uncertain.isNotEmpty() && s.phase != ApplyPhase.APPLIED) Text("기존 장소의 좌표가 없어 포함 여부 확인 필요: ${uncertain.joinToString(" · ") { it.name }}",style=GakimType.Body,color=GakimColors.Tight)
        Text("적용하면 차량 내비가 열릴 수 있어요. 돌아오면 반영된 경로를 다시 확인합니다.",style=GakimType.Body)
        Row(Modifier.fillMaxWidth().heightIn(min=72.dp).clickable(enabled=!s.busy) { c.setAcknowledged(!s.acknowledged) },verticalAlignment=Alignment.CenterVertically) {
            Checkbox(s.acknowledged,{ c.setAcknowledged(it) },enabled=!s.busy)
            Text("방문 순서와 예상의 조건을 확인했고, 이 변경을 적용합니다.",style=GakimType.Body)
        }
        if(s.park == ParkState.UNKNOWN) Row(Modifier.heightIn(min=72.dp).clickable(enabled=!s.busy) { c.setParked(!s.parkedConfirmed) },verticalAlignment=Alignment.CenterVertically) {
            Checkbox(s.parkedConfirmed,c::setParked,enabled=!s.busy); Text("기어 상태 미수신 · 현재 주차 중임을 확인합니다.",style=GakimType.Body)
        }
        if(s.applyMessage.isNotBlank()) Text("${when(s.phase) { ApplyPhase.SENDING->"전송 준비";ApplyPhase.VERIFYING->"반영 확인 중";ApplyPhase.APPLIED->"반영 완료";ApplyPhase.FAILED->"실패";ApplyPhase.UNCONFIRMED->"반영 미확인";else->"다시 확인" }} · ${s.applyMessage}",style=GakimType.BodyBold,color=if(s.phase==ApplyPhase.APPLIED) GakimColors.Accent else GakimColors.Tight)
        s.actualRoute?.let { Text("다시 읽은 경로: ${routeText(it)}",style=GakimType.Body) }
        if(s.phase in listOf(ApplyPhase.UNCONFIRMED,ApplyPhase.FAILED)) SecondaryButton("현재 차량 경로 다시 읽기",vm::refresh,Modifier.fillMaxWidth(),enabled=!s.busy)
    }
}
