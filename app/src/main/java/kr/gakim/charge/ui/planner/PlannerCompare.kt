package kr.gakim.charge.ui.planner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import kr.gakim.charge.data.ParkState
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.screens.*
import kr.gakim.charge.ui.theme.*

@Composable
internal fun PlannerCompare(s:PlannerState,vm:PlannerViewModel,open:(String)->Unit) {
    val c=vm.controller
    val groups=s.candidateGroups()
    val pages=groups.chunked(2)
    val pager=rememberPagerState(pageCount={pages.size.coerceAtLeast(1)})
    val scope=rememberCoroutineScope()
    LaunchedEffect(s.screen, s.recommendedForDisplay()?.id) {
        if(s.selectedId==null && s.allowRecommendation && s.rangeUnknown && s.park!=ParkState.MOVING)
            s.recommendedForDisplay()?.let { c.select(it.id) }
    }
    LaunchedEffect(s.selectedId) {
        val selectedGroup=groups.indexOfFirst { group -> group.any { it.id==s.selectedId } }
        if(selectedGroup>=0 && selectedGroup/2!=pager.currentPage) pager.scrollToPage(selectedGroup/2)
    }
    val enabled=!s.busy && !s.departureExpired && s.park!=ParkState.MOVING
    val depart=s.plan.departure ?: s.now
    val axisEnd=(s.options.flatMap { o -> listOfNotNull(o.critical?.visit?.deadline,o.critical?.arrival,o.results.lastOrNull()?.finish) }.maxOrNull() ?: depart.plusHours(1)).plusMinutes(8)
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("어디로 들렀다 갈까요?",style=GakimType.Assistant,maxLines=1)
                Text((if(s.demoMartFallback) martFallbackLine else s.message) ?: "${at(depart,s.now)} 출발 · ${s.plan.visits.joinToString(" → ") { it.place?.name ?: it.label }}",style=GakimType.Caption,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            if(pages.size>1) {
                PlannerPill("←",{scope.launch { pager.animateScrollToPage((pager.currentPage-1).coerceAtLeast(0)) }},Modifier.width(76.dp),pager.currentPage>0)
                Text("${pager.currentPage+1}/${pages.size}",style=GakimType.BodyBold)
                PlannerPill("→",{scope.launch { pager.animateScrollToPage((pager.currentPage+1).coerceAtMost(pages.lastIndex)) }},Modifier.width(76.dp),pager.currentPage<pages.lastIndex)
            }
        }
        if(groups.isEmpty()) Box(Modifier.weight(1f),contentAlignment=Alignment.Center) { Text(if(s.searchingPlaces) "주변 마트를 찾는 중이에요…" else s.inputBlocker ?: "이전 화면에서 들를 장소를 골라 주세요.",style=GakimType.Body) }
        else HorizontalPager(pager,Modifier.weight(1f).fillMaxWidth(),pageSpacing=24.dp) { page ->
            Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                pages[page].forEach { options ->
                    CandidateCard(s,options,vm,depart,axisEnd,enabled,groups.size==1,{open("details:$it")},Modifier.weight(1f).fillMaxHeight())
                }
                if(pages[page].size==1 && groups.size>1) Spacer(Modifier.weight(1f))
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            CompactMicWithHeard(s.listening,s.level,if(s.listening) s.partial else s.transcript,"말로 시간을 고칠 수도 있어요",vm::toggleListening,Modifier.weight(1f),enabled)
            PlannerPill("← 일정 수정",vm::back,enabled=enabled)
            PrimaryButton(s.selected?.let { goLabel(it.placeName(s)) } ?: "갈 곳을 골라 주세요",{vm.stopListening();c.guide()},Modifier.width(370.dp),enabled=enabled && s.selected?.selectable==true)
        }
    }
}

@Composable
private fun CandidateCard(s:PlannerState,options:List<Option>,vm:PlannerViewModel,depart:LocalDateTime,axisEnd:LocalDateTime,enabled:Boolean,spacious:Boolean,details:(String)->Unit,modifier:Modifier) {
    val key=options.first().visits.map { it.place?.id }.toString()
    var preview by remember(key) { mutableStateOf<String?>(null) }
    val recommended=s.recommendedForDisplay()
    val selected=options.firstOrNull { it.id==s.selectedId }
    val chosen=selected ?: options.firstOrNull { it.id==preview } ?: options.firstOrNull { it.id==recommended?.id } ?: options.firstOrNull { it.mode==ChargeMode.NONE } ?: options.first()
    fun choose(option:Option) {
        vm.stopListening();preview=option.id
        if(option.selectable) vm.controller.select(option.id) else vm.controller.clearSelection()
    }
    val critical=chosen.critical
    val deadline=critical?.visit?.deadline
    val slack=critical?.slackMinutes
    val tint=when { slack==null -> GakimColors.Unknown;slack<0 -> GakimColors.Late;slack<5 -> GakimColors.Tight;else -> GakimColors.Accent }
    val arrival=if(deadline!=null) critical.arrival else chosen.results.lastOrNull()?.finish
    Column(modifier.clip(CardShape).background(if(selected!=null) GakimColors.SurfaceHigh else GakimColors.Surface)
        .then(if(selected!=null) Modifier.border(3.dp,GakimColors.brandH(),CardShape) else Modifier.border(2.dp,GakimColors.Outline,CardShape))
        .clickable(enabled=enabled && chosen.selectable,role=Role.RadioButton) { choose(chosen) }.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(chosen.placeName(s),style=GakimType.Title,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
            if(options.any { it.id==recommended?.id }) Pill("★ 추천",color=GakimColors.Voice,outline=true)
            if(selected!=null) Glyph(Icon.CHECK,GakimColors.Accent,28.dp)
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            Text(clock(arrival),style=GakimType.Big)
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(if(deadline!=null) "${critical.visit.place?.name ?: critical.visit.label} 도착" else "볼일 마침",style=GakimType.Caption,maxLines=1)
                Pill(when { deadline==null -> "시간 제약 없음";slack==null -> "시간 미확인";slack<0 -> "${-slack}분 늦음";slack<5 -> "${slack}분 여유 · 촉박";else -> "${slack}분 여유" },
                    color=if(deadline==null) GakimColors.Accent else tint,icon=if(slack!=null && slack<5) Icon.WARN else Icon.CHECK)
            }
        }
        chosen.closingNotices.forEach { Text(it.label, style=GakimType.CaptionBold, color=GakimColors.Late) }
        (chosen.visits.firstOrNull { v -> s.plan.visits.firstOrNull { it.id == v.id }?.place == null } ?: chosen.visits.firstOrNull())?.place?.let {
            Text(if(it.demo) "시연 값" else it.source, style=GakimType.Caption, maxLines=1)
        }
        PlannerTimeline(chosen,depart,axisEnd,deadline)
        val chargeOptions=options.filter { it.mode!=ChargeMode.NONE }
        val locations=chargeOptions.mapNotNull { it.chargeVisitId }.distinct()
        val focus=chosen.chargeVisitId ?: chargeOptions.firstOrNull { it.selectable }?.chargeVisitId ?: locations.firstOrNull()
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(ChargeMode.NONE,ChargeMode.TOP_UP,ChargeMode.REQUIRED).forEach { mode ->
                val target=options.firstOrNull { it.mode==mode && (mode==ChargeMode.NONE || it.chargeVisitId==focus) }
                val label=when(mode) { ChargeMode.NONE -> "충전 안 함";ChargeMode.TOP_UP -> "들른 김에\n충전";else -> "충분히\n충전" }
                Box(Modifier.weight(1f).height(76.dp).clip(PillShape).background(GakimColors.Surface)
                    .border(if(chosen.mode==mode) 3.dp else 2.dp,if(chosen.mode==mode) GakimColors.Accent else GakimColors.Outline,PillShape)
                    .clickable(enabled=enabled,role=Role.RadioButton) {
                        if(target!=null) choose(target) else {
                            vm.edit { vm.controller.setCharge(mode) }
                            vm.controller.state.value.options.firstOrNull { it.visits.map { v -> v.place?.id }==chosen.visits.map { v -> v.place?.id } && it.mode==mode && it.selectable }?.let(::choose)
                        }
                    },contentAlignment=Alignment.Center) {
                    Text(label,style=GakimType.CaptionBold,color=if(target!=null && !target.selectable) GakimColors.TextDim else if(chosen.mode==mode) GakimColors.Accent else GakimColors.Text,textAlign=TextAlign.Center)
                }
            }
        }
        Text("이동 ${chosen.totalDistanceKm?.let { "%.1fkm".format(it) } ?: "거리 미확인"} · 머무는 시간 ${chosen.visits.sumOf { if(it.activity==Activity.CHARGE) 0 else it.stayMinutes }}분",style=GakimType.Caption,maxLines=1)
        chosen.evidence.filter { it.source.startsWith("카카오모빌리티") || it.source.startsWith("시연 구간") || it.source.startsWith("한국환경공단") }
            .distinctBy { it.source }.forEach { Text(if(it.demo) "시연 값" else if(it.source.startsWith("한국환경공단")) it.source else "${it.source} · ${clock(it.observedAt)}",style=GakimType.Caption,maxLines=1) }
        if(chosen.chargingNote.isNotBlank()) Text(chosen.chargingNote,style=GakimType.Caption,maxLines=2,overflow=TextOverflow.Ellipsis)
        // 한 곳만 비교할 때는 비어 있는 아래쪽을 방문 일정으로 채운다.
        if(spacious) SpaciousSchedule(chosen)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            val warnings=chosen.conditions.filterNot { s.rangeUnknown && it==chosen.energy.note }.distinct()
            Text(if(!chosen.selectable) "이 방식으로 갈 수 없어요" else if(warnings.isNotEmpty()) "${warnings.size}가지 확인할 내용이 있어요" else if(chosen.mode!=ChargeMode.NONE) "충전 ${chosen.chargeMinutes?.let { "${it}분" } ?: "시간 미확인"} · ${chosen.chargeVisitId?.let { id -> chosen.visits.first { it.id==id }.place?.name } ?: "장소 미확인"}" else "예상 시간과 확인한 정보를 살펴보세요",style=GakimType.Caption,color=if(warnings.isNotEmpty() || !chosen.selectable) GakimColors.Tight else GakimColors.TextDim,modifier=Modifier.weight(1f),maxLines=2)
            PlannerPill("자세히",{details(chosen.id)},Modifier.width(104.dp))
        }
    }
}

/** 지원 한 곳만 있을 때 남는 아래쪽을 방문 일정으로 채운다(계산된 값만, 한 방문 = 한 줄). */
@Composable
private fun SpaciousSchedule(option:Option) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        option.scheduleRows().forEach { row ->
            Row(Modifier.fillMaxWidth().clip(PillShape).background(GakimColors.SurfaceHigh).padding(horizontal=14.dp,vertical=4.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(row.arrival,style=GakimType.BodyBold,maxLines=1)
                Text(row.place,style=GakimType.BodyBold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                Text(row.detail,style=GakimType.Caption,maxLines=1)
                row.slackMinutes?.let { slack -> SlackChip(timingStatus(slack),slack.toInt()) }
            }
        }
        option.chargingRow()?.let { line ->
            Text(line,style=GakimType.CaptionBold,color=GakimColors.Voice,maxLines=1,overflow=TextOverflow.Ellipsis,
                modifier=Modifier.padding(horizontal=14.dp))
        }
    }
}

/** Canvas construction follows v2's shared deadline timeline; durations come only from v3 results. */
@Composable
private fun PlannerTimeline(option:Option,depart:LocalDateTime,end:LocalDateTime,deadline:LocalDateTime?) {
    val segments=option.timeline(depart)
    val labels=option.timelineLabelTexts(depart)
    val span=Duration.between(depart,end).toMinutes().coerceAtLeast(1).toFloat()
    val dl=deadline?.let { Duration.between(depart,it).toMinutes()/span }
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(26.dp)) {
            if(dl==null || dl>.25f) Text(clock(depart),style=GakimType.Caption,modifier=Modifier.align(Alignment.CenterStart))
            if(deadline!=null && dl!=null) {
                val labelWidth=112.dp
                Text("${clock(deadline)}까지",style=GakimType.CaptionBold,color=GakimColors.Text,textAlign=TextAlign.Center,
                    modifier=Modifier.offset(x=(maxWidth*dl.coerceIn(0f,1f)-labelWidth/2).coerceIn(0.dp,maxWidth-labelWidth)).width(labelWidth),maxLines=1)
            } else {
                Text("마치는 시간 비교",style=GakimType.CaptionBold,color=GakimColors.Text,modifier=Modifier.align(Alignment.Center))
                Text(clock(end),style=GakimType.Caption,modifier=Modifier.align(Alignment.CenterEnd))
            }
        }
        Canvas(Modifier.fillMaxWidth().height(38.dp)) {
            val top=8.dp.toPx();val h=24.dp.toPx()
            drawRoundRect(GakimColors.Outline,Offset(0f,top),Size(size.width,h),CornerRadius(8.dp.toPx()))
            var start=0f
            segments?.forEachIndexed { i,m ->
                val right=(start+m/span*size.width).coerceIn(start,size.width)
                val line=(dl ?: 1f).coerceIn(0f,1f)*size.width
                val base=if(i==1) GakimColors.Accent else GakimColors.Voice
                val before=minOf(right,line)
                if(before>start) drawRect(base,Offset(start,top),Size((before-start-2).coerceAtLeast(0f),h))
                val late=maxOf(start,line)
                if(right>late) drawRect(GakimColors.Late,Offset(late,top),Size((right-late-2).coerceAtLeast(0f),h))
                start=right
            }
            if(dl!=null) {
                val x=(dl*size.width).coerceIn(2.dp.toPx(),size.width-2.dp.toPx())
                drawLine(GakimColors.Text,Offset(x,0f),Offset(x,size.height),3.dp.toPx())
            }
        }
        if(segments==null || labels==null) Text("이동 또는 충전 시간을 아직 확인하지 못했어요",style=GakimType.Caption)
        else BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 막대와 같은 축(같은 dp 폭)에서 라벨을 놓고, 좁은 구간은 아랫줄로 밀어 겹치지 않게 한다.
            val axisWidth=maxWidth.value
            val labelWidths=labels.map { text ->
                val px=measurer.measure(text,GakimType.Caption,maxLines=1).size.width
                with(density) { px.toDp().value }
            }
            val bounds=labels.indices.map { index ->
                (segmentStart(segments,index)/span).coerceIn(0f,1f)..
                    (segmentStart(segments,index+1)/span).coerceIn(0f,1f)
            }
            val placed=axisLabelLayout(bounds,labels,labelWidths,axisWidth,8f)
            val rows=(placed.maxOfOrNull { it.row } ?: 0)+1
            Box(Modifier.fillMaxWidth().height(26.dp*rows)) {
                placed.forEach { label ->
                    Text(label.text,style=GakimType.Caption,maxLines=1,
                        modifier=Modifier.offset(x=label.left.dp,y=26.dp*label.row))
                }
            }
        }
    }
}

/** 구간 누적 시작(분). */
private fun segmentStart(segments: List<Long>, index: Int): Float =
    segments.take(index.coerceIn(0,segments.size)).sumOf { it }.toFloat()
