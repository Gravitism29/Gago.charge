package kr.gakim.charge.ui.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kr.gakim.charge.data.BatteryReading
import kr.gakim.charge.data.GatewaySet
import kr.gakim.charge.data.NaviPlace
import kr.gakim.charge.data.NaviRead
import kr.gakim.charge.data.NaviRequest
import kr.gakim.charge.data.NaviRoute
import kr.gakim.charge.data.ParkState
import kr.gakim.charge.data.PlaceMatcher
import kr.gakim.charge.data.SpeechEvent
import kr.gakim.charge.demo.TripDataRepository
import kr.gakim.charge.domain.ChargePreference
import kr.gakim.charge.domain.Deadline
import kr.gakim.charge.domain.Route
import kr.gakim.charge.domain.RouteAction
import kr.gakim.charge.domain.VerifyResult
import kr.gakim.charge.domain.chargingPrompt
import kr.gakim.charge.domain.compareMarts
import kr.gakim.charge.domain.formatClock
import kr.gakim.charge.domain.routeDecision
import kr.gakim.charge.domain.speech.SpeechIntentParser
import kr.gakim.charge.domain.speech.SpeechCommand
import kr.gakim.charge.domain.speech.SpeechCommandParser
import kr.gakim.charge.domain.StopMode
import kr.gakim.charge.domain.speech.SpokenDeadline
import kr.gakim.charge.domain.verifyReply

/**
 * 세 화면의 흐름을 진행하는 곳. Android 에 의존하지 않아 Fake 게이트웨이로 단위 테스트할 수 있다.
 * 판단은 모두 `:domain` 의 규칙 함수에 맡기고, 여기서는 읽기·요청·다시 읽기 순서만 정한다.
 */
class PlanController(
    private var gateways: GatewaySet,
    private val repository: TripDataRepository,
    // 에뮬레이터에서 경유지 추가 후 새 경로가 잡히기까지 약 7초 걸렸다(2026-11-14). 여유 있게 15초.
    private val verifyAttempts: Int = 15,
    private val verifyDelayMs: Long = 1_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(decorate(initialState()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var generation = 0L
    private var comparing = false
    private var demoBusy = false

    private fun decorate(s: UiState): UiState = s.copy(
        assistant = AssistantLines.build(s).let { a -> a.copy(line = stripDemoTag(a.line), sub = a.sub?.let(::stripDemoTag)) },
        recommendedIndex = AssistantLines.recommendedIndex(s),
    )
    /** '(시연)' 꼬리표는 상단 출처 표시가 대신하므로 말·문장에서는 뺀다. */
    private fun stripDemoTag(text: String) = text.replace(" (시연)", "").replace("(시연)", "")
    private fun update(change: (UiState) -> UiState) = _state.update { decorate(change(it)) }
    private fun checkGeneration(token: Long) {
        if (token != generation) throw CancellationException("Superseded plan")
    }
    fun cancelAutoAdvance(announce: Boolean = true) = update { it.copy(autoAdvanceAtMs = null,
        inputMessage = if (announce && it.autoAdvanceAtMs != null) "멈췄어요. 준비되면 눌러 주세요." else it.inputMessage) }
    fun setTtsEnabled(enabled: Boolean) = update { it.copy(ttsEnabled = enabled, autoAdvanceAtMs = null) }
    suspend fun advanceIfDue() {
        val due = state.value.autoAdvanceAtMs ?: return
        if (nowMs() >= due && state.value.screen == Screen.TODAY) openCompare()
    }

    /** 비교 화면을 만들 때 읽은 경로. 요청 직전에 다시 읽은 값과 비교한다. */
    private var baseline: NaviRoute? = null

    private fun initialState() = UiState(
        usingFakes = gateways.usingFakes,
        dataLabel = repository.sourceLabel,
        nowMinutes = repository.nowMinutes(),
        thresholdPercent = repository.chargeThresholdPercent(),
    )

    // ---------- 공통 ----------

    /** 배터리와 내비 상태를 다시 읽는다. 실패는 '알 수 없음'으로 남는다. */
    suspend fun refresh() {
        val token = ++generation
        comparing = false
        cancelAutoAdvance(announce = false)
        update { it.copy(guide = it.guide.copy(busy = false)) }
        readSnapshot(gateways, token)
    }

    private suspend fun readSnapshot(g: GatewaySet, token: Long) {
        update { it.copy(loading = true) }
        try {
            val battery = g.vehicle.readBatterySoc()
            checkGeneration(token)
            val park = readPark(g, token)
            checkGeneration(token)
            val navi = g.navi.readRoute()
            checkGeneration(token)
            update {
                it.copy(
                    loading = false, battery = it.demoBatteryOverride?.let { p -> BatteryReading.Known(p) } ?: battery,
                    parkState = park, navi = navi, nowMinutes = repository.nowMinutes(),
                )
            }
            lastRealBattery = battery
        } finally {
            if (token == generation) update { it.copy(loading = false) }
        }
    }

    private suspend fun readPark(g: GatewaySet, token: Long): ParkState {
        val park = g.vehicle.readParkState()
        checkGeneration(token)
        update { it.copy(parkState = park, parkReadAtMs = nowMs()) }
        return park
    }

    fun hasOwnNavigationHandoff(): Boolean {
        val guide = state.value.guide
        val confirmed = guide.confirmedAtMs ?: return false
        return guide.requestStarted && !guide.returnConsumed && nowMs() - confirmed in 0..30_000
    }

    fun consumeReturnToFront(): Boolean {
        val guide = state.value.guide
        if (!hasOwnNavigationHandoff() || guide.busy ||
            guide.verify !in listOf(VerifyResult.APPLIED, VerifyResult.MISMATCH)) return false
        setGuide { it.copy(returnConsumed = true) }
        return true
    }

    /** 차에서 마지막으로 읽은 실제 잔량(시연 덮어쓰기를 끄면 되돌아갈 값). */
    private var lastRealBattery: BatteryReading? = null

    /**
     * 시연 잔량 덮어쓰기(디버그 패널 전용). null 이면 차에서 읽은 값을 그대로 쓴다.
     * 화면이 쓰는 배터리 값과 비교 계산이 모두 이 값을 쓰도록 [UiState.battery] 를 즉시 바꾼다.
     */
    fun setDemoBatteryOverride(percent: Int?) {
        if (state.value.guide.busy) return
        generation++
        comparing = false
        baseline = null
        val clamped = percent?.coerceIn(0, 100)
        update { s ->
            s.copy(
                demoBatteryOverride = clamped,
                screen = Screen.TODAY, options = emptyList(), selectedIndex = null, cardModeIndices = emptyMap(),
                guide = GuideUi(), prompt = null, arrivalSoc = null, loading = false, autoAdvanceAtMs = null,
                battery = clamped?.let { BatteryReading.Known(it) } ?: lastRealBattery ?: s.battery,
            )
        }
    }

    suspend fun setDemoDestination(place: NaviPlace) {
        if (demoBusy || state.value.loading || state.value.guide.busy) return
        demoBusy = true
        val token = generation
        val g = gateways
        try {
            val park = readPark(g, token)
            checkGeneration(token)
            if (park == ParkState.MOVING) return
            g.navi.setDestinationForDemo(place)
            checkGeneration(token)
            delay(3_000)
            checkGeneration(token)
            refresh()
        } finally { demoBusy = false }
    }

    /** Pleos ↔ Fake 전환. 진행 중이던 계획은 버리고 첫 화면으로 돌아간다. */
    fun replaceGateways(newGateways: GatewaySet) {
        generation++
        comparing = false
        gateways = newGateways
        baseline = null
        lastRealBattery = null
        _state.value = decorate(
            initialState().copy(
                form = _state.value.form, ttsEnabled = state.value.ttsEnabled,
                demoBatteryOverride = state.value.demoBatteryOverride,
            ),
        )
    }

    fun back() {
        if (state.value.guide.busy) return
        cancelAutoAdvance(announce = false)
        generation++
        comparing = false
        update {
            when (it.screen) {
                Screen.TODAY -> it.copy(loading = false)
                Screen.COMPARE -> it.copy(screen = Screen.TODAY, loading = false)
                Screen.GUIDE -> it.copy(screen = Screen.COMPARE, guide = GuideUi())
            }
        }
    }

    fun restart() {
        generation++
        comparing = false
        baseline = null
        update {
            it.copy(
                screen = Screen.TODAY, form = TodayForm(), speech = SpeechUi(), prompt = null, arrivalSoc = null,
                options = emptyList(), selectedIndex = null, cardModeIndices = emptyMap(), guide = GuideUi(),
                autoAdvanceAtMs = null, spokenSlots = emptySet(), loading = false, inputMessage = null, touchedForm = false,
            )
        }
    }

    // ---------- 1. 오늘 할 일 ----------

    fun setActivityMinutes(minutes: Int) = updateForm { it.copy(activityMinutes = minutes) }
    fun setPickup(pickup: Boolean) = updateForm { it.copy(pickup = pickup) }
    fun setDeadline(deadline: Deadline) {
        if (deadline is Deadline.At && deadline.minutes < repository.nowMinutes() + 10) {
            update { it.copy(inputMessage = deadlineMessage(repository.nowMinutes()), autoAdvanceAtMs = null) }
        } else updateForm { it.copy(deadline = deadline) }
    }
    fun setCharge(charge: ChargePreference) = updateForm { it.copy(charge = charge) }

    /** 시각을 [deltaMinutes] 만큼 옮긴다. 아직 시각이 없으면 가장 가까운 선택지에서 시작한다. */
    fun nudgeDeadline(deltaMinutes: Int) {
        val s = state.value
        val now = repository.nowMinutes()
        val current = (s.form.deadline as? Deadline.At)?.minutes
        val next = current?.let { (it + deltaMinutes).coerceAtLeast(now + 10) }
            ?: deadlineChoices(now).first()
        setDeadline(Deadline.At(next))
    }

    private fun updateForm(change: (TodayForm) -> TodayForm) {
        if (state.value.parkState == ParkState.MOVING) return
        generation++
        comparing = false
        baseline = null
        update { it.copy(inputMessage = null, touchedForm = true, form = change(it.form), options = emptyList(), selectedIndex = null, cardModeIndices = emptyMap(),
            guide = GuideUi(), autoAdvanceAtMs = null, spokenSlots = emptySet(), speech = SpeechUi(), loading = false) }
    }

    /** 음성 인식 이벤트를 반영한다. 완성된 문장은 규칙 기반 파서로만 해석한다. */
    suspend fun onSpeechEvent(event: SpeechEvent) {
        when (event) {
            SpeechEvent.Listening ->
                update { it.copy(speech = SpeechUi(listening = true, note = "듣고 있습니다…")) }
            is SpeechEvent.Partial ->
                update { it.copy(speech = it.speech.copy(heard = event.text)) }
            is SpeechEvent.Final -> onSpokenSentence(event.text)
            is SpeechEvent.Level -> update { it.copy(speech = it.speech.copy(level = event.value.takeIf { v -> v.isFinite() }?.coerceIn(0f, 1f) ?: 0f)) }
            is SpeechEvent.Error -> update {
                it.copy(
                    speech = it.speech.copy(
                        listening = false, available = false, level = 0f,
                        note = "음성 인식을 쓸 수 없습니다(${event.reason}). 버튼으로 입력해 주세요.",
                    ),
                )
            }
        }
    }

    /** 듣기 흐름이 끝났을 때(정상 종료·취소 모두) 호출한다. */
    fun onSpeechStopped() = update {
        if (it.speech.listening) it.copy(speech = it.speech.copy(listening = false, level = 0f, note = null)) else it
    }

    suspend fun onSpokenSentence(text: String) {
        cancelAutoAdvance(announce = false)
        val s = state.value
        if (s.parkState == ParkState.MOVING) return
        if (s.screen == Screen.TODAY) {
            generation++
            comparing = false
            baseline = null
            applySpokenText(text)
            return
        }
        update { it.copy(speech = SpeechUi(heard = text)) }
        if (s.guide.busy) return
        val command = SpeechCommandParser.parse(text, s.options.map { it.place.name }.distinct())
        when (command) {
            SpeechCommand.Back -> back()
            SpeechCommand.Restart -> restart()
            SpeechCommand.Recheck -> if (s.screen == Screen.GUIDE && s.guide.sent) confirmGuidance()
            SpeechCommand.Parked -> if (s.screen == Screen.GUIDE) setParkedConfirmed(true)
            SpeechCommand.Confirm -> if (s.screen == Screen.GUIDE &&
                (s.guide.parkState == ParkState.PARKED || (s.guide.parkState == ParkState.UNKNOWN && s.guide.parkedConfirmed))) confirmGuidance()
            else -> if (s.screen == Screen.COMPARE) {
                val selectable = s.options.indices.filter { s.options[it].option.selectable }
                val index = when (command) {
                    is SpeechCommand.SelectIndex -> martCards(s).getOrNull(command.index)?.let { selectionIndex(s, it) }
                    is SpeechCommand.SelectByName -> martCards(s).firstOrNull { card -> s.options[listOfNotNull(card.shopOnly, card.charge).first()].place.name == command.name }?.let { selectionIndex(s, it) }
                    SpeechCommand.SelectFastest -> selectable.filter { s.options[it].option.arrival != null }.minByOrNull { s.options[it].option.arrival!! }
                    SpeechCommand.SelectRecommended -> s.recommendedIndex
                    is SpeechCommand.PreferCharging -> selectable.filter {
                        s.options[it].option.mode == if (command.charging) StopMode.SHOP_AND_CHARGE else StopMode.SHOP_ONLY
                    }.let { matching -> matching.firstOrNull { s.options[it].place == s.selected?.place }
                        ?: matching.minByOrNull { s.options[it].option.arrival ?: Int.MAX_VALUE } }
                    else -> null
                }
                if (index != null) { select(index); openGuide() }
            }
        }
    }

    private fun applySpokenText(text: String) = update { s ->
        val now = repository.nowMinutes()
        val intent = SpeechIntentParser.parse(text, now)
        intent.clarification?.let { message ->
            return@update s.copy(inputMessage = message, autoAdvanceAtMs = null, speech = SpeechUi(heard = text, note = message))
        }
        val spokenDeadline = intent.deadline
        if (spokenDeadline is SpokenDeadline.At && spokenDeadline.minutes < now + 10) {
            return@update s.copy(nowMinutes = now, inputMessage = deadlineMessage(now), autoAdvanceAtMs = null,
                speech = SpeechUi(heard = text))
        }
        if (intent.isEmpty) {
            return@update s.copy(
                inputMessage = null, speech = SpeechUi(heard = text, note = "알아듣지 못했습니다. 버튼으로 선택해 주세요."),
                spokenSlots = emptySet(), autoAdvanceAtMs = null,
            )
        }
        var form = s.form
        intent.activityMinutes?.let { form = form.copy(activityMinutes = it) }
        val understood = mutableListOf<String>()
        intent.activity?.let { understood += it }
        intent.pickup?.let {
            form = form.copy(pickup = it)
            understood += if (it) "픽업 있음" else "픽업 없음"
        }
        when (val d = intent.deadline) {
            is SpokenDeadline.At -> {
                form = form.copy(deadline = Deadline.At(d.minutes))
                understood += "${formatClock(d.minutes)}까지"
            }
            SpokenDeadline.Anytime -> {
                form = form.copy(deadline = Deadline.Anytime)
                understood += "몇 시든 괜찮음"
            }
            null -> Unit
        }
        intent.charge?.let {
            form = form.copy(charge = it)
            understood += "충전 ${it.label}"
        }
        val slots = buildSet {
            if (intent.activity != null) add(Slot.ACTIVITY)
            if (intent.pickup != null) add(Slot.PICKUP)
            if (intent.deadline != null) add(Slot.DEADLINE)
            if (intent.charge != null) add(Slot.CHARGE)
        }
        val next = s.copy(
            nowMinutes = now, inputMessage = null, touchedForm = false, form = form, spokenSlots = slots, options = emptyList(), selectedIndex = null, cardModeIndices = emptyMap(), guide = GuideUi(), loading = false,
            speech = SpeechUi(heard = text, note = "알아들은 내용: ${understood.joinToString(" · ")} (틀리면 버튼으로 고쳐 주세요)"),
        )
        val duration = if (next.ttsEnabled) maxOf(2_500L, AssistantLines.build(next).line.length * 180L + 600L) else 2_500L
        next.copy(autoAdvanceDurationMs = duration, autoAdvanceAtMs = if (next.blocker == null) nowMs() + duration else null)
    }

    // ---------- 2. 두 마트 비교 ----------

    /** 배터리·내비를 새로 읽고 두 마트를 비교한다. 입력이 모자라면 아무것도 하지 않는다. */
    suspend fun openCompare() {
        if (_state.value.blocker != null || comparing || state.value.screen != Screen.TODAY) return
        if (state.value.parkState == ParkState.MOVING) return
        comparing = true
        val token = ++generation
        val g = gateways
        cancelAutoAdvance(announce = false)
        try {
            readSnapshot(g, token)
            val s = _state.value
            if (s.blocker != null || s.parkState == ParkState.MOVING) return
            val route = s.route ?: return
            baseline = route

            val consumption = repository.estimatedConsumptionPercent()
            val soc = (s.battery as? BatteryReading.Known)?.percent
            // 도착 전에 바닥나는 경우는 0%로 본다(0%는 유효한 값이며 충전 비교로 이어진다).
            val arrivalSoc = if (soc != null && consumption != null) (soc - consumption).coerceAtLeast(0) else null
            val prompt = chargingPrompt(s.form.charge, arrivalSoc, s.thresholdPercent)

            val marts = repository.martCandidates(route.destination)
            val details = marts.associateBy { it.candidate.id }
            val options = compareMarts(
                candidates = marts.map { it.candidate },
                depart = s.nowMinutes,
                activityMinutes = s.form.activityMinutes,
                deadline = (s.form.deadline as? Deadline.At)?.minutes,
                margin = repository.marginMinutes(),
                // Expose optional charging without changing the domain prompt or default recommendation.
                prompt = kr.gakim.charge.domain.ChargingPrompt.ASK_USER,
            ).map { o -> details.getValue(o.candidate.id).let {
                OptionUi(o, it.place, it.toMartMinutes, it.martToDestinationMinutes)
            } }

            update {
                it.copy(
                    screen = Screen.COMPARE, prompt = prompt, arrivalSoc = arrivalSoc, options = options, cardModeIndices = emptyMap(),
                    selectedIndex = AssistantLines.recommendedIndex(it.copy(options = options, prompt = prompt)),
                    guide = GuideUi(), inputMessage = null, speech = SpeechUi(),
                )
            }
        } finally { if (token == generation) comparing = false }
    }

    fun select(index: Int) = update {
        if (it.parkState == ParkState.MOVING) it
        else if (it.options.getOrNull(index)?.option?.selectable == true) it.copy(selectedIndex = index, autoAdvanceAtMs = null,
            cardModeIndices = it.cardModeIndices + (it.options[index].option.candidate.id to index)) else it
    }

    // ---------- 3. 이대로 안내 ----------

    suspend fun openGuide() {
        if (_state.value.selected == null) return
        cancelAutoAdvance(announce = false)
        val token = generation
        val parkState = readPark(gateways, token)
        checkGeneration(token)
        update { it.copy(screen = Screen.GUIDE, guide = GuideUi(parkState = parkState)) }
    }

    fun setParkedConfirmed(confirmed: Boolean) =
        update { it.copy(guide = it.guide.copy(parkedConfirmed = confirmed), autoAdvanceAtMs = null) }

    /**
     * 사용자가 '이대로 안내'를 눌렀을 때만 호출한다(이 호출이 사용자 확인이다).
     * 내비를 다시 읽고 → 규칙으로 판단하고 → 필요하면 한 번만 요청하고 → 다시 읽어 확인한다.
     */
    suspend fun confirmGuidance() {
        cancelAutoAdvance(announce = false)
        val token = generation
        val g = gateways
        val s = _state.value
        val selected = s.selected ?: return
        val base = baseline ?: return
        if (s.screen != Screen.GUIDE || s.guide.busy) return
        setGuide { it.copy(busy = true, message = null, action = null,
            confirmedAtMs = nowMs(), requestStarted = false, returnConsumed = false) }
        try {

            val current = when (val read = g.navi.readRoute().also { checkGeneration(token) }) {
                is NaviRead.Known -> read.route
                is NaviRead.Unknown -> {
                    setGuide {
                        it.copy(busy = false, message = "내비 상태를 알 수 없어 요청을 보내지 않았습니다. (${read.reason})")
                    }
                    return
                }
            }

            val known: List<NaviPlace> = listOfNotNull(base.destination) + base.waypoints + selected.place
            val baselineRoute = PlaceMatcher.toRoute(base, known)
            val currentRoute = PlaceMatcher.toRoute(current, known)
            val martKey = PlaceMatcher.keyOf(selected.place, known)
            val planned =
                if (martKey in baselineRoute.stops) baselineRoute
                else Route(baselineRoute.destination, listOf(martKey) + baselineRoute.stops)

            // Read immediately before the synchronous decision/send boundary, not at Guide entry.
            val freshPark = readPark(g, token)
            checkGeneration(token)
            setGuide { it.copy(parkState = freshPark) }
            val parked = freshPark == ParkState.PARKED || (freshPark == ParkState.UNKNOWN && s.guide.parkedConfirmed)
            val action = routeDecision(
                baseline = baselineRoute, current = currentRoute, planned = planned,
                parked = parked, confirmed = true, sent = s.guide.sent,
            )
            setGuide { it.copy(action = action, actual = current) }

            when (action) {
                RouteAction.REQUEST_ONCE -> {
                    // The final cached reading must still be fresh at the send boundary.
                    val lastRead = state.value.parkReadAtMs
                    val sendPark = if (lastRead == null || nowMs() - lastRead > 3_000) readPark(g, token) else state.value.parkState
                    if (sendPark == ParkState.MOVING || (sendPark == ParkState.UNKNOWN && !s.guide.parkedConfirmed)) return
                    setGuide { it.copy(requestStarted = true) }
                    when (val result = g.navi.addFirstWaypoint(selected.place).also { checkGeneration(token) }) {
                        is NaviRequest.Failed ->
                            setGuide { it.copy(busy = false, message = "내비에 요청하지 못했습니다. (${result.reason})") }
                        NaviRequest.Sent -> {
                            setGuide { it.copy(sent = true) }
                            verify(planned, known, g, token)
                        }
                    }
                }
                RouteAction.REQUERY -> verify(planned, known, g, token)
                RouteAction.ALREADY_APPLIED ->
                    setGuide { it.copy(busy = false, verify = VerifyResult.APPLIED, message = "이미 이 경로로 안내 중입니다.") }
                else -> setGuide { it.copy(busy = false, message = explain(action)) }
            }
        } finally {
            if (token == generation) setGuide { it.copy(busy = false) }
        }
    }

    /** 요청 성공 ≠ 반영 완료. 내비를 다시 읽어 계획과 같아질 때까지 몇 번 확인한다. */
    private suspend fun verify(planned: Route, known: List<NaviPlace>, g: GatewaySet, token: Long) {
        var result = VerifyResult.PENDING
        setGuide { it.copy(verify = result) }
        for (attempt in 1..verifyAttempts) {
            delay(verifyDelayMs)
            checkGeneration(token)
            when (val read = g.navi.readRoute().also { checkGeneration(token) }) {
                is NaviRead.Known -> {
                    // 내비가 새 경로를 계산하는 동안은 목적지가 비어서 온다. 이것은 불일치가 아니라 아직 반영 중이다.
                    result = if (read.route.destination == null && planned.destination.isNotEmpty()) {
                        if (result == VerifyResult.MISMATCH) result else VerifyResult.PENDING
                    } else {
                        verifyReply(PlaceMatcher.toRoute(read.route, known), planned)
                    }
                    setGuide { it.copy(verify = result, actual = read.route) }
                }
                is NaviRead.Unknown -> {
                    if (result != VerifyResult.MISMATCH) result = verifyReply(null, planned)
                    setGuide { it.copy(verify = result) }
                }
            }
            if (result == VerifyResult.APPLIED) break
        }
        val finalResult = result
        setGuide {
            it.copy(
                busy = false,
                message = when (finalResult) {
                    VerifyResult.APPLIED -> "내비에 반영된 것을 확인했습니다."
                    VerifyResult.PENDING -> "아직 반영을 확인하지 못했습니다. '다시 확인'을 눌러 주세요."
                    VerifyResult.MISMATCH -> "내비 경로가 계획과 다릅니다. 내비 화면을 확인해 주세요."
                },
            )
        }
    }

    private fun explain(action: RouteAction): String = when (action) {
        RouteAction.HOLD -> "주차 상태가 확인되지 않아 내비를 바꾸지 않았습니다. 주차한 뒤 다시 눌러 주세요."
        RouteAction.RECOMPARE -> "비교한 뒤에 내비 경로가 바뀌었습니다. 뒤로 가서 다시 비교해 주세요."
        RouteAction.CHECK_DESTINATION -> "내비 목적지를 확인할 수 없습니다. 목적지를 확인한 뒤 다시 비교해 주세요."
        RouteAction.WAYPOINT_LIMIT -> "경유지는 최대 2곳입니다. 더 추가하지 않았습니다."
        RouteAction.CHECK_EXISTING_STOPS -> "기존 경유지가 바뀔 수 있어 요청하지 않았습니다. 내비 일정을 확인해 주세요."
        else -> action.label
    }

    private fun setGuide(change: (GuideUi) -> GuideUi) = update { it.copy(guide = change(it.guide)) }
}
