package kr.gakim.charge.ui.planner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import kr.gakim.charge.data.ParkState
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.screens.*
import kr.gakim.charge.ui.theme.*

@Composable
internal fun PlannerCompare(s:PlannerState,vm:PlannerActions,open:(String)->Unit) {
    val c=vm.controller
    val groups=s.candidateGroups()
    val pages=groups.chunked(2)
    val pager=rememberPagerState(pageCount={pages.size.coerceAtLeast(1)})
    val scope=rememberCoroutineScope()
    LaunchedEffect(s.screen, s.recommendedForDisplay()?.id) {
        if(s.selectedId==null && s.allowRecommendation && s.rangeUnknown && s.park!=ParkState.MOVING)
            s.recommendedForDisplay()?.let { c.select(it.id) }
    }
    LaunchedEffect(s.candidatePage) {
        if(pages.isNotEmpty()) pager.animateScrollToPage(s.candidatePage.coerceIn(0,pages.lastIndex))
    }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { c.setCandidatePage(it) } }
    LaunchedEffect(s.selectedId) {
        val selectedGroup=groups.indexOfFirst { group -> group.any { it.id==s.selectedId } }
        if(selectedGroup>=0 && selectedGroup/2!=pager.currentPage) pager.scrollToPage(selectedGroup/2)
    }
    val enabled=!s.busy && !s.departureExpired && s.park!=ParkState.MOVING
    val depart=s.shownDeparture(s.selected ?: s.recommendedForDisplay() ?: s.cardOptions.firstOrNull())
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if(groups.size==1) "이 일정으로 갈까요?" else "어디로 들렀다 갈까요?",style=GakimType.Assistant,maxLines=1)
                Text(s.presentationIssue()?.line ?: s.processingLine()?.takeIf { groups.isNotEmpty() } ?: (if(s.demoMartFallback) martFallbackLine else s.message) ?: sameTimesLine(pages.getOrNull(pager.currentPage).orEmpty()) ?: "${at(depart,s.now)} 출발 · ${s.plan.visits.joinToString(" → ") { it.place?.name ?: it.label }}",style=GakimType.Caption,maxLines=2)
            }
            if(s.presentationIssue()?.retry==true) PlannerPill("다시 시도",vm::refresh,enabled=enabled)
            if(pages.size>1) {
                PlannerPill("←",{scope.launch { pager.animateScrollToPage((pager.currentPage-1).coerceAtLeast(0)) }},Modifier.width(76.dp),pager.currentPage>0)
                Text("${pager.currentPage*2+1}–${(pager.currentPage*2+2).coerceAtMost(groups.size)} / ${groups.size}곳",style=GakimType.BodyBold)
                PlannerPill("→",{scope.launch { pager.animateScrollToPage((pager.currentPage+1).coerceAtMost(pages.lastIndex)) }},Modifier.width(76.dp),pager.currentPage<pages.lastIndex)
            }
        }
        if(groups.isEmpty()) Box(Modifier.weight(1f),contentAlignment=Alignment.Center) { Text(if(s.searchingPlaces) "주변 장소를 찾는 중이에요…" else s.inputBlocker ?: "이전 화면에서 들를 장소를 골라 주세요.",style=GakimType.Body) }
        else HorizontalPager(pager,Modifier.weight(1f).fillMaxWidth(),pageSpacing=24.dp) { page ->
            Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                pages[page].forEach { options ->
                    CandidateCard(s,options,vm,depart,enabled,{open("details:$it")},Modifier.weight(1f).fillMaxHeight(),wide=groups.size==1)
                }
                if(pages[page].size==1 && groups.size>1) Spacer(Modifier.weight(1f))
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            PlannerStepMic(s,"“이걸로 가자” · “충전하고 갈게” · “다른 곳 보여줘”",vm::toggleListening,Modifier.weight(1f),enabled)
            PlannerPill("← 일정 수정",vm::back,enabled=enabled)
            PrimaryButton(s.selected?.let { goLabel(it.placeName(s)) } ?: "갈 곳을 골라 주세요",{vm.stopListening();c.guide()},Modifier.width(370.dp),enabled=enabled && s.selected?.selectable==true)
        }
    }
}

@Composable
private fun CandidateCard(s:PlannerState,options:List<Option>,vm:PlannerActions,depart:LocalDateTime,enabled:Boolean,details:(String)->Unit,modifier:Modifier,wide:Boolean=false) {
    val key=options.first().visits.map { it.place?.id }.toString()
    var preview by remember(key) { mutableStateOf<String?>(null) }
    val recommended=s.recommendedForDisplay()
    val selected=options.firstOrNull { it.id==s.selectedId }
    val chosen=selected ?: options.firstOrNull { it.id==preview } ?: options.firstOrNull { it.id==recommended?.id } ?: options.firstOrNull { it.mode==s.plan.chargeMode } ?: options.firstOrNull { it.mode==ChargeMode.NONE } ?: options.first()
    fun choose(option:Option) {
        vm.stopListening();preview=option.id
        if(option.chargeControlEnabled()) vm.controller.selectMode(option.id)
    }
    @Suppress("NAME_SHADOWING") val depart=s.shownDeparture(chosen)
    val chargeOptions=options.filter { it.mode!=ChargeMode.NONE }
    val focus=chosen.chargeVisitId ?: chargeOptions.firstOrNull { it.selectable && s.chargingSites[it.visits.firstOrNull { v->v.id==it.chargeVisitId }?.place?.id]!=null }?.chargeVisitId ?: chargeOptions.firstOrNull()?.chargeVisitId
    val place=chosen.visits.firstOrNull { it.id==focus }?.place
    val site=place?.id?.let(s.chargingSites::get)
    val noCharger=site==null && place?.demo!=true
    val warning=chosen.warningSummary()
    Row(modifier.clip(CardShape).background(GakimColors.Surface)
        .border(if(selected!=null) 3.dp else 2.dp,if(selected!=null) GakimColors.Accent else GakimColors.Outline,CardShape)
        .clickable(enabled=enabled && chosen.chargeControlEnabled(),role=Role.RadioButton) { choose(chosen) }
        .padding(16.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        // Each block has the same allocation in every candidate, regardless of recommendation or warnings.
        Column(Modifier.fillMaxWidth().height(64.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(chosen.placeName(s),style=GakimType.Title,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(chosen.closingNotices.isNotEmpty()) FitLine("⚠ ${chosen.closingNotices.joinToString(" · ") { it.label }}",GakimType.CaptionBold.copy(color=GakimColors.Tight))
            else Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                if(options.any { it.id==recommended?.id }) Box(Modifier.clip(PillShape).background(GakimColors.brandH()).padding(horizontal=10.dp,vertical=2.dp)) {
                    Text(s.recommendationLabel(chosen),style=GakimType.CaptionBold.copy(fontSize=18.sp,lineHeight=22.sp),color=GakimColors.OnAccent,maxLines=1)
                }
                // 2026-11-17 평가(2팀): 두 식당의 숫자가 같으면 고를 근거가 없었다. 곳마다 다른 충전기 이름·거리를 보여 준다.
                site?.placeLine()?.let { Text(it,style=GakimType.Caption.copy(fontSize=18.sp),maxLines=1,overflow=TextOverflow.Ellipsis) }
            }
        }
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text("모두 끝나는 시각",style=GakimType.Caption)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { FitLine(clock(chosen.results.lastOrNull()?.finish),GakimType.Big) }
                val slack=chosen.critical?.slackMinutes
                Text(chosen.deadlineLabel(),style=GakimType.CaptionBold.copy(fontSize=18.sp),color=if(slack!=null && slack<5) GakimColors.Tight else GakimColors.TextDim,
                    modifier=Modifier.clip(PillShape).background(GakimColors.SurfaceHigh).padding(horizontal=10.dp,vertical=6.dp),maxLines=1)
            }
            // 2026-11-18 심사 지적: 핵심 가치(따로 충전하러 가지 않아 아끼는 시간)가 숫자로 보이지 않았다.
            savedTimeLine(chosen,site)?.let { FitLine(it,GakimType.Title.copy(color=GakimColors.Accent)) }
        }
        PlannerTimeline(chosen,depart,Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min=94.dp).clip(CardShape).background(GakimColors.SurfaceHigh).border(1.dp,GakimColors.Outline,CardShape)) {
                val modes=visibleChargeModes(options.firstOrNull { it.mode==ChargeMode.REQUIRED && it.chargeVisitId==focus })
                modes.forEachIndexed { index,mode ->
                    val target=options.firstOrNull { it.mode==mode && (mode==ChargeMode.NONE || it.chargeVisitId==focus) }
                    // 가득 찬 배터리에는 충전을 권하지 않는다(심사 검증 지적: 100%인데 충전 선택지가 살아 있었다).
                    val full=mode!=ChargeMode.NONE && batteryFull(s.energy,s.now)
                    val available=enabled && !full && target?.chargeControlEnabled()==true && (mode==ChargeMode.NONE || !noCharger)
                    val on=chosen.mode==mode
                    // 고른 카드만 민트로 채운다. 고르지 않은 카드는 테두리로만 보여 준다(두 카드가 모두 선택된 것처럼 보였다).
                    Column(Modifier.weight(1f).heightIn(min=94.dp).background(if(on && selected!=null) GakimColors.Accent else androidx.compose.ui.graphics.Color.Transparent)
                        .then(if(on && selected==null) Modifier.border(2.dp,GakimColors.Accent,CardShape) else Modifier)
                        .clickable(enabled=available,role=Role.RadioButton) { target?.let(::choose) }.padding(horizontal=6.dp,vertical=10.dp),
                        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                        val color=if(on && selected!=null) GakimColors.Background else if(on) GakimColors.Accent else if(!available) GakimColors.TextDim else GakimColors.Text
                        Text(mode.driverLabel(),style=GakimType.CaptionBold.copy(fontSize=18.sp,fontWeight=FontWeight.Bold),color=color,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                        Text(if(full) "배터리가 가득 찼어요" else chargeControlLine(target),style=GakimType.Caption.copy(fontSize=18.sp,lineHeight=22.sp),color=color,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                        if(!full) target?.let { socChangeLine(it,s.energy,s.now) }?.let {
                            Text(it,style=GakimType.Caption.copy(fontSize=18.sp,lineHeight=22.sp),color=color,textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=1)
                        }
                    }
                    if(index<modes.lastIndex) Box(Modifier.width(1.dp).fillMaxHeight().background(GakimColors.Outline))
                }
            }
            if(noCharger) Text("충전소 정보가 없어 시간만 비교해요",style=GakimType.Caption,color=GakimColors.Tight)
            Text(benefitLine(chosen,s.energy,s.now,site),style=GakimType.Body.copy(fontSize=22.sp,lineHeight=30.sp),color=if(chosen.selectable) GakimColors.Text else GakimColors.Tight)
            val segments=chosen.elapsedSegments(depart)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                listOf("이동" to (segments?.get(0)?.let { "${it}분" } ?: "—"),
                    chosen.activityLabel() to "${chosen.visits.filter { it.activity!=Activity.CHARGE }.sumOf { it.stayMinutes }}분",
                    chosen.extraStopLabel() to (if(chosen.mode==ChargeMode.NONE) "—" else segments?.get(2)?.let { "${it}분" } ?: "—")).forEach { (label,value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label,style=GakimType.Caption.copy(fontSize=18.sp))
                        Text(value,style=GakimType.Title.copy(fontSize=28.sp,fontWeight=FontWeight.Bold))
                    }
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                chosen.statusChips(site,chosen.visits.any { it.place?.demo==true }).forEach { label ->
                    Text(label,style=GakimType.Caption.copy(fontSize=18.sp,lineHeight=24.sp),color=if(label=="사용 가능") GakimColors.Accent else GakimColors.TextDim,
                        modifier=Modifier.clip(PillShape).background(GakimColors.SurfaceHigh).padding(horizontal=8.dp,vertical=3.dp),maxLines=1)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(64.dp).clip(PillShape).clickable(role=Role.Button) { details(chosen.id) }.padding(horizontal=4.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(warning?.let { if(it.risk) it.text else "${it.text} ·" } ?: "",style=if(warning?.risk==true) GakimType.CaptionBold else GakimType.Caption,color=if(warning?.risk==true) GakimColors.Tight else GakimColors.TextDim,textAlign=if(warning?.risk==true) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.End,modifier=Modifier.weight(1f),maxLines=1)
            Text("자세히 ›",style=GakimType.BodyBold,color=GakimColors.Voice)
        }
    }
    if(wide) RouteTimeline(s.copy(selectedId=chosen.id,options=listOf(chosen)),Modifier.weight(.54f).fillMaxHeight(),compact=true)
    }
}

/** Shrinks a complete sentence to the available width instead of cutting its meaning off. */
@Composable
internal fun FitLine(text:String,style:androidx.compose.ui.text.TextStyle,modifier:Modifier=Modifier) {
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val measured=measurer.measure(text,style,softWrap=false).size.width
        val available=with(density) { maxWidth.toPx() }
        val scale=if(measured>available && measured>0) available/measured else 1f
        Text(text,style=style.copy(fontSize=(style.fontSize.value*scale).coerceAtLeast(18f).sp),softWrap=true)
    }
}

@Composable
internal fun PlannerTimeline(option:Option,depart:LocalDateTime,modifier:Modifier=Modifier) {
    val segments=option.elapsedSegments(depart)
    // 민트는 '충전되는 동안'에만 쓴다: 볼일 구간에 민트 띠, 주차·연결은 중립색.
    val chargingDuringErrand=option.mode!=ChargeMode.NONE && option.chargeOverlapsVisit()
    val colours=listOf(GakimColors.Voice,GakimColors.TextDim,androidx.compose.ui.graphics.Color(0xFF5E6E7E))
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp))) {
            drawRoundRect(GakimColors.Outline,cornerRadius=CornerRadius(8.dp.toPx()))
            val span=segments?.sum()?.coerceAtLeast(1)?.toFloat() ?: 1f
            var start=0f
            segments?.forEachIndexed { i,m ->
                val width=m/span*size.width
                if(width>0) drawRect(colours[i],Offset(start,0f),Size((width-2).coerceAtLeast(0f),size.height))
                if(width>0 && i==1 && chargingDuringErrand) drawRect(GakimColors.Accent,Offset(start,size.height*.55f),Size((width-2).coerceAtLeast(0f),size.height*.45f))
                start+=width
            }
        }
        if(segments==null) Text("이동 또는 충전 시간 미확인",style=GakimType.Caption)
        else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
            val labels=option.timelineSegmentLabels(segments)
            labels.forEachIndexed { i,label -> if(i<2 || option.mode!=ChargeMode.NONE) {
                Dot(if(i==1 && chargingDuringErrand) GakimColors.Accent else colours[i],7.dp)
                Text(label,style=GakimType.Caption.copy(fontSize=18.sp),maxLines=1)
                if(i==0 || i==1 && option.mode!=ChargeMode.NONE) Text("·",style=GakimType.Caption)
            } }
        }
    }
}
