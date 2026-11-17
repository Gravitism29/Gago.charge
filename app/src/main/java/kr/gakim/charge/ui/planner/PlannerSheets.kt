package kr.gakim.charge.ui.planner

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import kr.gakim.charge.BuildConfig
import kr.gakim.charge.data.*
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.theme.*
import kr.gakim.charge.ui.screens.PrimaryButton
import kr.gakim.charge.ui.screens.CardShape

@Composable
internal fun PlannerSheets(s:PlannerState,vm:PlannerViewModel,sheet:String?,close:()->Unit,open:(String)->Unit={}) {
    val c=vm.controller
    val editable=!s.busy && s.park!=ParkState.MOVING
    val import=s.pendingImport
    val pending=s.pendingTime
    val question=s.question
    val dismiss:()->Unit=when { import!=null -> { {c.confirmImport(false)} };pending!=null -> { {vm.edit { c.resolvePastTime(null) }} };question!=null -> c::clearQuestion;else -> close }
    BackHandler(sheet!=null || import!=null || pending!=null || question!=null,onBack=dismiss)
    when {
        import!=null -> PlannerSheet("내비 일정 가져오기",dismiss) {
            Text("지금 작성한 일정",style=GakimType.Title)
            Text(s.plan.visits.joinToString("\n") { "${it.label} · ${it.place?.name ?: "장소 미정"} · ${it.stayMinutes}분" })
            Text("가져올 일정",style=GakimType.Title)
            Text(routeText(import))
            Text("가져오면 방문 순서와 장소가 바뀌어요. 같은 장소의 할 일과 시간은 이어서 써요.",color=GakimColors.Tight)
            PrimaryButton("이 일정 가져오기",{vm.edit { c.confirmImport(true) }},Modifier.fillMaxWidth(),editable)
        }
        pending!=null -> PlannerSheet("이미 지난 시각이에요 · ${at(pending.at,s.now)}",dismiss) {
            Text("오늘의 지난 일정인가요, 내일 일정인가요?")
            PlannerPill("오늘 일정",{vm.edit { c.resolvePastTime(false) }},Modifier.fillMaxWidth(),editable)
            PlannerPill("내일 일정",{vm.edit { c.resolvePastTime(true) }},Modifier.fillMaxWidth(),editable)
            PlannerPill("다시 입력",dismiss,Modifier.fillMaxWidth(),editable)
        }
        question!=null -> PlannerSheet(question.message,dismiss) {
            if(question.timeRole) {
                PlannerPill("출발 시각",{vm.edit { c.resolveTimeRole(true) }},Modifier.fillMaxWidth(),editable)
                PlannerPill("마지막 장소 도착 마감",{vm.edit { c.resolveTimeRole(false) }},Modifier.fillMaxWidth(),editable)
            }
            question.places.take(4).forEach { p -> PlannerPill(placeChoiceLabel(p),{vm.edit { question.visitId?.let { c.setPlace(it,p) } }},Modifier.fillMaxWidth(),editable) }
            question.timeChoices.forEach { time -> PlannerPill("${at(time,s.now)} ${if(question.visitId==null) "출발" else "도착"}",{vm.edit { c.resolveTimeChoice(question.visitId,time) }},Modifier.fillMaxWidth(),editable) }
            PlannerPill("화면에서 수정",dismiss,Modifier.fillMaxWidth())
        }
        sheet!=null -> key(sheet) {
            val id=sheet.substringAfter(':',"").toLongOrNull()
            val visit=s.plan.visits.firstOrNull { it.id==id }
            when {
                sheet=="more" -> PlannerSheet("더보기",close) {
                    Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        MoreTile("내비 일정 가져오기","내비에 있는 목적지로 시작해요",{vm.edit { c.importRoute() };close()},Modifier.weight(1f),editable)
                        MoreTile("문장으로 입력","말 대신 글자로 입력해요",{open("sentence")},Modifier.weight(1f),editable)
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        MoreTile("충전 방식 고르기","항상 충전 / 충전 안 함을 정해요",{open("charge")},Modifier.weight(1f),editable)
                        MoreTile("출발 시각","나중에 출발할 때 정해요",{open("departure")},Modifier.weight(1f),editable)
                    }
                    Text("현재 내비: ${routeText((s.currentRoute as? NaviRead.Known)?.route)}",style=GakimType.Caption)
                }
                sheet=="add" || sheet.startsWith("kind:") -> ActivitySheet(visit,close) { activity,label -> vm.edit { if(visit==null) c.add(activity,label) else c.setActivity(visit.id,activity,label) };close() }
                sheet.startsWith("place:") && visit!=null -> PlaceSheet(visit,c.allPlaces(visit),close,{vm.searchPlace(it);close()}) { vm.edit { c.setPlace(visit.id,it) };close() }
                sheet=="departure" || sheet.startsWith("time:") && visit!=null -> ClockSheet(if(visit==null) "언제 출발할까요?" else "${visit.place?.name ?: visit.label}에 몇 시까지 갈까요?",if(visit==null) s.plan.departure else visit.deadline,s.now,visit!=null,editable,close) { vm.edit { c.setTime(visit?.id,it) };close() }
                sheet=="sentence" -> SentenceSheet(close) { vm.edit { c.onSpeech(SpeechEvent.Final(it),"직접 입력 문장") };close() }
                sheet=="charge" -> ChargeSheet(s,close) { mode,reserve -> vm.edit { c.setCharge(mode);c.setReserve(reserve) };close() }
                sheet=="dev" && BuildConfig.DEBUG -> DevSheet(s,vm,close)
                sheet=="sources" || sheet.startsWith("details:") -> SourcesSheet(s,s.cardOptions.firstOrNull { it.id==sheet.removePrefix("details:") },vm,close)
            }
        }
    }
}

@Composable
private fun ActivitySheet(visit:Visit?,close:()->Unit,choose:(Activity,String)->Unit) {
    var label by remember { mutableStateOf(visit?.customLabel ?: "") }
    PlannerSheet("어떤 일을 보실까요?",close) {
        Activity.entries.filter { it!=Activity.CUSTOM }.chunked(3).forEach { row ->
            Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) { row.forEach { a -> PlannerPill(a.label,{choose(a,"")},Modifier.weight(1f)) } }
        }
        PlannerField(label,{label=it},"직접 적기")
        PrimaryButton("이 할 일로 정하기",{choose(Activity.CUSTOM,label.trim())},Modifier.fillMaxWidth(),label.isNotBlank())
    }
}

@Composable
private fun ClockSheet(title:String,initial:LocalDateTime?,now:LocalDateTime,deadline:Boolean,enabled:Boolean,close:()->Unit,apply:(LocalDateTime?)->Unit) {
    PlannerSheet(title,close) {
        ClockEditor(initial,now,deadline,enabled,apply)
    }
}

@Composable
internal fun ClockEditor(initial:LocalDateTime?,now:LocalDateTime,deadline:Boolean,enabled:Boolean,apply:(LocalDateTime?)->Unit) {
    // Keep the date as the live vehicle clock crosses midnight while this editor is open.
    var selected by remember(initial) { mutableStateOf(initial ?: dialClockTime(snapDialValue(clockDialValue(now.plusHours(1),now).toFloat(),TimeDialMode.Clock),now)) }
    val value=clockDialValue(selected,now)
    var active by remember(initial) { mutableStateOf(initial!=null) }
    var message by remember { mutableStateOf<String?>(null) }
    val reject:()->Unit={message=if(deadline) "이미 지난 시각이에요. 현재 이후로 정해 주세요." else "출발 예정 시각은 현재 이후로 정해 주세요."}
    fun choose(next:Int) {
        // 오늘/내일을 누르면 '정해진 시각 없음'이 풀린다(에뮬레이터 확인: 풀 방법이 안 보였다).
        if(dialClockAllowed(next,now)) { selected=dialClockTime(next,now);message=null;active=true } else reject()
    }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            PlannerPill("오늘",{choose(Math.floorMod(value,MINUTES_PER_DAY))},Modifier.weight(1f),enabled,active && selected.toLocalDate()==now.toLocalDate())
            PlannerPill("내일",{choose(Math.floorMod(value,MINUTES_PER_DAY)+MINUTES_PER_DAY)},Modifier.weight(1f),enabled,active && selected.toLocalDate()==now.toLocalDate().plusDays(1))
            PlannerPill(if(deadline) "정해진 시각 없음" else "지금 출발",{
                if(!active && !dialClockAllowed(value,now)) {
                    selected=dialClockTime(minimumDialClockValue(now),now)
                }
                active=!active;message=null
            },Modifier.weight(1.5f),enabled,!active)
        }
        TimeDial(value,TimeDialMode.Clock,{choose(it)},Modifier.fillMaxWidth(),enabled,active,now,
            if(deadline) "도착 시각" else "출발 시각",reject)
        Text(message ?: "현재 ${at(now,now)} · 오늘은 10분 이후부터 정할 수 있어요",style=GakimType.Caption,
            color=if(message!=null) GakimColors.Tight else GakimColors.TextDim,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
        PrimaryButton(if(active) "이 시각으로 정하기" else if(deadline) "정해진 시각 없이 두기" else "지금 출발하기",{
            if(!active) apply(null) else if(dialClockAllowed(value,now)) apply(dialClockTime(value,now)) else reject()
        },Modifier.fillMaxWidth(),enabled)
    }
}

@Preview(widthDp=1280,heightDp=910,showBackground=true)
@Composable
private fun ClockEditorPreview() {
    val now=LocalDateTime.of(2026,11,16,13,0)
    GakimTheme { ClockSheet("학교에 몇 시까지 갈까요?",now.withHour(16).withMinute(10),now,true,true,{},{}) }
}

@Preview(widthDp=1280,heightDp=910,showBackground=true)
@Composable
private fun DepartureDialPreview() {
    val now=LocalDateTime.of(2026,11,16,13,0)
    GakimTheme { ClockSheet("언제 출발할까요?",now.plusMinutes(30),now,false,true,{},{}) }
}

@Composable
private fun PlaceSheet(visit:Visit,places:List<Place>,close:()->Unit,search:(String)->Unit,choose:(Place?)->Unit) {
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    val found=places.filter { query.isBlank() || it.name.contains(query,true) || it.address.contains(query,true) }
    val point=Place("manual:$lat,$lon",name.trim(),lat.toDoubleOrNull() ?: Double.NaN,lon.toDoubleOrNull() ?: Double.NaN,source="사용자 직접 지정")
    PlannerSheet("${visit.label} · 들를 장소",close) {
        if(manual) {
            PlannerField(name,{name=it},"장소 이름")
            Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                PlannerField(lat,{lat=it},"위도",Modifier.weight(1f),KeyboardType.Decimal)
                PlannerField(lon,{lon=it},"경도",Modifier.weight(1f),KeyboardType.Decimal)
            }
            Text("입력한 좌표를 그대로 사용해요. 내비에서 고른 뒤 일정을 가져올 수도 있어요.")
            PrimaryButton("이 장소로 정하기",{choose(point)},Modifier.fillMaxWidth(),name.isNotBlank() && point.validCoordinates)
        } else {
            PlannerField(query,{query=it;page=0},"저장된 장소에서 찾기")
            found.drop(page*4).take(4).forEach { p -> PlannerPill(p.name,{choose(p)},Modifier.fillMaxWidth()) }
            if(found.isEmpty()) Text("저장한 장소에 없어요. 내비에서 찾거나 직접 지정해 주세요.")
            if(found.size>4) Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                PlannerPill("이전",{page--},Modifier.weight(1f),page>0)
                Text("${page+1}/${(found.size+3)/4}",modifier=Modifier.padding(20.dp))
                PlannerPill("다음",{page++},Modifier.weight(1f),(page+1)*4<found.size)
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            PlannerPill(if(manual) "목록에서 선택" else "이름·좌표 직접 지정",{manual=!manual},Modifier.weight(1f))
            PlannerPill("차량 내비에서 찾기",{search(query.ifBlank { visit.place?.name ?: visit.label })},Modifier.weight(1f))
        }
        PlannerPill("장소 미정 · 후보 비교",{choose(null)},Modifier.fillMaxWidth())
    }
}

@Composable
private fun SentenceSheet(close:()->Unit,apply:(String)->Unit) {
    var text by remember { mutableStateOf("") }
    PlannerSheet("일정을 문장으로 적어 주세요",close) {
        Text("말할 때처럼 편하게 적어 주세요.")
        OutlinedTextField(text,{text=it},Modifier.fillMaxWidth().heightIn(min=160.dp),textStyle=GakimType.Body,label={Text("예: 30분 밥 먹고 갈래",style=GakimType.Caption)},minLines=3)
        PrimaryButton("일정에 반영",{apply(text)},Modifier.fillMaxWidth(),text.isNotBlank())
    }
}

@Composable
private fun ChargeSheet(s:PlannerState,close:()->Unit,apply:(ChargeMode,Double)->Unit) {
    var mode by remember { mutableStateOf(s.plan.chargeMode) }
    var reserve by remember { mutableStateOf(s.plan.reserveKm.toInt().toString()) }
    PlannerSheet("충전은 어떻게 할까요?",close) {
        ChargeMode.entries.forEach { choice -> PlannerPill(choice.driverLabel(),{mode=choice},Modifier.fillMaxWidth(),selected=mode==choice) }
        PlannerField(reserve,{reserve=it.filter(Char::isDigit).take(3)},"도착 후 남겨 둘 주행 거리 · km",keyboard=KeyboardType.Number)
        if(s.rangeUnknown) Text("주행 거리는 아직 몰라요. 원하시면 충전 방식을 직접 고를 수 있어요.")
        PrimaryButton("이 방식으로 비교",{reserve.toDoubleOrNull()?.let { apply(mode,it) }},Modifier.fillMaxWidth(),reserve.toDoubleOrNull()?.let { it in 0.0..200.0 }==true)
    }
}

@Composable
private fun SourcesSheet(s:PlannerState,option:Option?,vm:PlannerViewModel,close:()->Unit) {
    val evidence=(if(option!=null) option.evidence else s.options.flatMap { it.evidence })+
        listOfNotNull(s.energy.rangeKm?.evidence,s.energy.socPercent?.evidence,s.energy.kmPerKwh?.evidence)
    var expanded by remember { mutableStateOf(false) }
    val places=(option?.visits ?: s.options.flatMap { it.visits }).mapNotNull { it.place }.distinctBy { it.id }
    PlannerSheet(if(option==null) "어디서 확인한 정보인가요?" else "가기 전에 확인해 주세요",close) {
        if(option!=null) {
            Text(option.detailsConclusion(),style=GakimType.Assistant)
            val baseline=s.cardOptions.firstOrNull { it.mode==ChargeMode.NONE && it.visits.map { v->v.place?.id }==option.visits.map { v->v.place?.id } }
            chargingFallback(option,baseline)?.let { Text(it) }
            Text("방문과 시간",style=GakimType.Title)
            DetailRow("장소",option.visits.joinToString(" → ") { it.place?.name ?: it.label })
            DetailRow("끝나는 시각",clock(option.results.lastOrNull()?.finish))
            DetailRow("충전",option.chargingRow() ?: option.mode.driverLabel())
            val warnings=option.reviewWarnings()
            if(warnings.isNotEmpty() || option.closingNotices.isNotEmpty()) {
                Text("확인하고 갈 내용",style=GakimType.Title,color=GakimColors.Tight)
                option.closingNotices.forEach { Text("⚠ ${it.label} → 문을 연 다른 장소를 골라 주세요",color=GakimColors.Tight) }
                warnings.map { warningFallback(it,option,baseline) }.distinct().forEach { Text("⚠ $it",color=GakimColors.Tight) }
            }
            // 고른 것(충전)과 반대 행동이므로 주 버튼이 아니라 보조 버튼이다.
            if(option.mode!=ChargeMode.NONE) PlannerPill("충전 빼고 ${clock(baseline?.results?.lastOrNull()?.finish)}에 끝내기",{baseline?.let { vm.stopListening();vm.controller.selectMode(it.id);close() }},Modifier.fillMaxWidth(),
                baseline?.chargeControlEnabled()==true && !s.busy && s.park!=ParkState.MOVING)
            val related=s.cardOptions.filter { it.visits.map { v->v.place?.id }==option.visits.map { v->v.place?.id } && it.mode==option.mode }
            if(related.map { it.chargeVisitId }.distinct().size>1) {
                Text("어디에서 충전할까요?",style=GakimType.Title)
                related.forEach { target -> target.chargeVisitId?.let { id -> PlannerPill(target.visits.first { it.id==id }.place?.name ?: "충전 장소",{vm.controller.selectMode(target.id);close()},Modifier.fillMaxWidth(),target.selectable && !s.busy && s.park!=ParkState.MOVING,target.id==s.selectedId) } }
            }
        } else {
            Text(if(s.usingFakes) "차량·내비도 시연 값이에요." else "차량 잔량과 내비 경로는 차량에서 읽었어요.",style=GakimType.Title)
            s.energy.rangeKm?.let { Text("차량 주행 가능 거리: ${it.value}km${if(s.rangeUnknown) " · 미확인으로 처리" else ""}") }
        }
        PlannerPill(if(expanded) "데이터 출처 ▴" else "데이터 출처 ▾",{expanded=!expanded},Modifier.fillMaxWidth(),color=GakimColors.Voice)
        if(expanded) {
            s.sourceStatuses.forEach { Text(sourceLabel("${it.source} · ${it.at?.let { time -> at(time,s.now) } ?: "미조회"} · ${it.status}"),style=GakimType.Caption) }
            Text("차량 내비 · ${s.routeReadAt?.let { at(it,s.now) } ?: "미조회"}",style=GakimType.Caption)
            places.forEach { Text(sourceLabel("${it.name} · ${if(it.demo) "시연 값" else it.source}"),style=GakimType.Caption) }
            evidence.distinct().forEach { e -> Text(sourceLabel("${e.source} · ${if(e.demo) "시연 값 · " else ""}${at(e.observedAt,s.now)}${if(!e.fresh(s.now,600)) " · 오래된 정보" else ""}"),style=GakimType.Caption) }
            option?.closingNotices?.forEach { Text(sourceLabel(it.source),style=GakimType.Caption) }
            option?.chargingNote?.takeIf { it.isNotBlank() }?.let { Text(sourceLabel(it),style=GakimType.Caption) }
        }
    }
}

@Composable
private fun DetailRow(label:String,value:String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
        Text(label,style=GakimType.Caption,modifier=Modifier.width(140.dp))
        Text(value,style=GakimType.BodyBold,modifier=Modifier.weight(1f))
    }
}

@Composable
private fun MoreTile(title:String,description:String,click:()->Unit,modifier:Modifier,enabled:Boolean) {
    Column(modifier.heightIn(min=132.dp).clip(CardShape).background(GakimColors.SurfaceHigh).border(2.dp,GakimColors.Outline,CardShape)
        .clickable(enabled=enabled,onClick=click).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=GakimType.BodyBold)
        Text(description,style=GakimType.Caption)
    }
}

@Composable
private fun DevSheet(s:PlannerState,vm:PlannerViewModel,close:()->Unit) {
    val c=vm.controller
    PlannerSheet("개발 확인",close) {
        if(s.demoEnergy) Text("실제 차량 값 대신 시연 값을 쓰는 중",color=GakimColors.Demo)
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            PlannerPill("시연 모드",{vm.debugAction("demo");close()},Modifier.weight(1f),selected=s.demoClock && s.demoEnergy && s.demoRoutes)
            PlannerPill("시연 끄기",{vm.debugAction("demo_off");close()},Modifier.weight(1f))
        }
        PlannerPill(if(s.usingFakes) "차량·내비 모의 · 실제 연결로 전환" else "차량·내비 연결됨 · 모의로 전환",{vm.useFakes(!s.usingFakes)},Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            PlannerPill("시연 구간 ${if(s.demoRoutes) "켜짐" else "꺼짐"}",{vm.edit { c.setDemoRoutes(!s.demoRoutes) }},Modifier.weight(1f))
            PlannerPill("시연 거리 ${if(s.demoEnergy) "켜짐" else "꺼짐"}",{vm.edit { c.setDemoEnergy(!s.demoEnergy) }},Modifier.weight(1f))
        }
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            PlannerPill("시연 시계 ${if(s.demoClock) "켜짐" else "꺼짐"}",{vm.edit { c.setDemoClock(!s.demoClock) }},Modifier.weight(1f))
            PlannerPill("차량·내비 다시 읽기",vm::refresh,Modifier.weight(1f))
        }
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            PlannerPill("데이터 미수신 주입",{vm.debugAction("missing")},Modifier.weight(1f))
            PlannerPill("전송 실패 주입",{vm.debugAction("fail")},Modifier.weight(1f))
        }
        PlannerPill("주입 해제 · 다시 읽기",{vm.debugAction("recover")},Modifier.fillMaxWidth())
    }
}

@Composable
private fun PlannerField(value:String,change:(String)->Unit,label:String,modifier:Modifier=Modifier,keyboard:KeyboardType=KeyboardType.Text) =
    OutlinedTextField(value,change,modifier.fillMaxWidth().heightIn(min=76.dp),label={Text(label,style=GakimType.Caption)},singleLine=true,textStyle=GakimType.Body,keyboardOptions=KeyboardOptions(keyboardType=keyboard))
