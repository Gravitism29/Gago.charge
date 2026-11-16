package kr.gakim.charge.ui.planner

import java.time.LocalDateTime
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.gakim.charge.data.*
import kr.gakim.charge.demo.ItineraryRepository
import kr.gakim.charge.domain.itinerary.*

enum class ApplyPhase { IDLE, SENDING, VERIFYING, APPLIED, FAILED, UNCONFIRMED }
data class PendingTime(val visitId: Long?, val at: LocalDateTime)
data class PlannerState(
    val plan: Itinerary = Itinerary(), val now: LocalDateTime = LocalDateTime.now(), val screen: Int = 0,
    val options: List<Option> = emptyList(), val comparisonOptions: List<Option> = emptyList(),
    val chargingSites: Map<String, ChargingSite> = emptyMap(), val candidatePage: Int = 0, val selectedId: String? = null, val selectionExplicit: Boolean = false,
    val allowRecommendation:Boolean=true, val approvalKey:String?=null, val pendingImport:NaviRoute?=null, val revision: Long = 0,
    val currentRoute: NaviRead? = null, val routeReadAt: LocalDateTime? = null,
    val savedPlaces: List<Place> = emptyList(), val energy: Energy = Energy(), val park: ParkState = ParkState.UNKNOWN,
    val parkReadAt:LocalDateTime?=null,
    val searchingPlaces: Boolean = false, val loading: Boolean = false, val message: String? = null, val inputBlocker: String? = null, val question: SpeechQuestion? = null,
    val pendingTime: PendingTime? = null, val transcript: String = "", val partial: String = "", val applied: List<String> = emptyList(),
    val speechSource: String = "", val listening: Boolean = false, val level: Float = 0f,
    val usingFakes: Boolean = false, val demoRoutes: Boolean = true, val demoEnergy: Boolean = false,
    val demoClock: Boolean = false, val tts: Boolean = false,
    val phase: ApplyPhase = ApplyPhase.IDLE, val applyMessage: String = "", val actualRoute: NaviRoute? = null,
    val requestSent: Boolean = false,
    val sourceStatuses: List<kr.gakim.charge.data.remote.SourceStatus> = emptyList(),
    val remotePlacesEnabled: Boolean = false, val nearbyRealMarts: List<Place> = emptyList(), val exampleDestination: String? = null,
    val reviewedRoute: NaviRoute? = null,
    val keptDestinationName: String? = null,
    val acknowledged: Boolean = false, val parkedConfirmed: Boolean = false, val parkedConfirmedAt:LocalDateTime?=null, val ownHandoff: Boolean = false,
) {
    val selected: Option? get() = options.firstOrNull { it.id == selectedId }
    val busy: Boolean get() = phase == ApplyPhase.SENDING || phase == ApplyPhase.VERIFYING
    val departureExpired:Boolean get()=plan.departure?.isBefore(now)==true
}
interface RouteJournal { fun save(route: NaviRoute?); fun load(): NaviRoute? }
object NoRouteJournal : RouteJournal { override fun save(route: NaviRoute?) = Unit; override fun load(): NaviRoute? = null }

class ItineraryController(private var gateways: GatewaySet, private val repository: ItineraryRepository = ItineraryRepository(),
                          private val clock: () -> LocalDateTime = LocalDateTime::now,
                          private val journal: RouteJournal = NoRouteJournal,
                          private val verifyAttempts: Int = 15, private val verifyDelayMs: Long = 1000) {
    private val mutable = MutableStateFlow(PlannerState(now = clock(), usingFakes = gateways.usingFakes, sourceStatuses = repository.sourceStatuses,
        remotePlacesEnabled = repository.remotePlacesEnabled))
    val state: StateFlow<PlannerState> = mutable.asStateFlow()
    private var realEnergy = Energy()
    private var socVersion = 0L
    private var rangeVersion = 0L
    suspend fun observeEnergy() {
        val g = gateways
        g.vehicle.energyUpdates.collect { update ->
            if (g === gateways) {
                when (update) {
                    is VehicleEnergyUpdate.Soc -> {
                        socVersion++
                        realEnergy = realEnergy.copy(socPercent = (update.reading as? BatteryReading.Known)?.let {
                            Measured(it.percent, Evidence(if(g.usingFakes) "차량 모의 값" else "Vehicle SOC", now(), g.usingFakes))
                        })
                    }
                    is VehicleEnergyUpdate.Range -> { rangeVersion++; realEnergy = realEnergy.copy(rangeKm = update.reading) }
                }
                if (!state.value.demoEnergy && !simulateMissing) applyEnergy(realEnergy)
            }
        }
    }
    private fun energyChanged(a: Energy, b: Energy) =
        a.socPercent?.value != b.socPercent?.value || a.rangeKm?.value != b.rangeKm?.value ||
            a.rangeKm?.evidence?.source != b.rangeKm?.evidence?.source || a.kmPerKwh?.value != b.kmPerKwh?.value
    private fun applyEnergy(energy: Energy) {
        val s = state.value
        val changed = energyChanged(s.energy, energy)
        if (!changed) {
            // 근거 시각이 화면 시계(s.now)보다 뒤면 fresh() 가 '미래 값'으로 버린다. 값이 그대로여도 시계를 함께 당긴다.
            val at = now()
            set(s.copy(energy = energy, now = if (at.isAfter(s.now)) at else s.now)); return
        }
        // Invalidate a pre-send confirmation; an already sent request still needs verification.
        if (changed && s.busy && !s.ownHandoff) revision++
        set(calculate(s.copy(energy = energy, now = now(),
            phase = if(changed && s.busy && !s.ownHandoff) ApplyPhase.IDLE else s.phase,
            acknowledged = if(changed) false else s.acknowledged,
            approvalKey = if(changed) null else s.approvalKey)))
    }
    private var remoteGeneration = 0L
    private var revision = 0L
    private var readGeneration = 0L
    private var parkGeneration=0L
    private var nextId = 2L
    private var speechFocus: Long? = null
    fun focusSpeechVisit(id:Long?) { speechFocus=id }
    private val tripCache = mutableMapOf<String, TripPreview>()
    private val sentPlaces = mutableListOf<Place>()
    private var approvedBaseline: NaviRoute? = null
    private var confirmedAt: LocalDateTime? = null
    var simulateMissing = false
    var simulateApplyFailure = false
    private fun now() = if (state.value.demoClock) clock().toLocalDate().atTime(13, 0) else clock()
    private fun set(s: PlannerState) { mutable.value = s }
    private fun active(token: Long) { if (token != revision) throw CancellationException("Newer itinerary") }
    private fun activeRead(token:Long,g:GatewaySet) { if(token!=readGeneration || g!==gateways) throw CancellationException("Newer vehicle read") }
    private fun edit(change: (PlannerState) -> PlannerState) {
        if (state.value.busy || state.value.park == ParkState.MOVING) return
        revision++
        val s = change(state.value).copy(revision = revision, phase = ApplyPhase.IDLE, acknowledged = false,
            approvalKey=null,allowRecommendation=true,applyMessage = "", actualRoute = null, requestSent=false, reviewedRoute=null, keptDestinationName=null, loading = false)
        val at = if(s.demoClock) clock().toLocalDate().atTime(13,0) else clock()
        set(calculate(s.copy(now = at)))
    }
    fun allPlaces(visit:Visit?=null): List<Place> = (listOfNotNull(visit?.place) +
        (visit?.let { repository.candidates(it.copy(place=null)) } ?: emptyList()) + repository.namedPlaceChoices + state.value.savedPlaces +
        if (repository.allowDemoPlaces) repository.catalog.map { it.place } else emptyList())
        .filter { it.name.isNotBlank() && it.validCoordinates }.distinctBy { it.id }
    private fun resolvedPlace(p:NaviPlace):Place {
        val raw=p.toPlace("현재 내비")
        val matches=sentPlaces.filter { PlaceMatcher.samePlace(p,it.toNavi()) }.distinctBy { it.id }
        if(matches.size!=1) return raw
        val known=matches.first()
        return if(raw.validCoordinates) known.copy(latitude=raw.latitude,longitude=raw.longitude,address=raw.address,source="현재 내비 · 이 앱에서 선택한 장소")
            else known.copy(source="현재 내비 · 이 앱에서 전송한 좌표")
    }
    private fun routePlaces(route:NaviRoute)= (route.waypoints+listOfNotNull(route.destination)).map(::resolvedPlace)
    private fun speechCatalog(): List<CatalogEntry> = (if (repository.remotePlacesEnabled) emptyList() else repository.catalog) +
        state.value.savedPlaces.filter { it.validCoordinates && !it.demo }.map { p -> CatalogEntry(p, setOf(Activity.CUSTOM), listOf(p.name)) }
    fun tick() { if (!state.value.busy) set(calculate(state.value.copy(now = now()))) }
    fun add(activity: Activity, label: String = "") = edit {
        if(activity==Activity.CHARGE && it.plan.visits.any { v->v.activity==Activity.CHARGE && !v.placeholder })
            it.copy(message="한 일정의 충전 정차는 한 곳까지 계산해요. 기존 충전 방문을 수정해 주세요.")
        else if(it.plan.visits.size==1 && it.plan.visits.single().placeholder) it.copy(plan=it.plan.copy(
            visits=listOf(it.plan.visits.single().copy(activity=activity,customLabel=label,placeholder=false,
                stayMinutes=if(activity==Activity.ARRIVE) 0 else it.plan.visits.single().stayMinutes)),
            chargeMode=if(activity==Activity.CHARGE && it.plan.chargeMode==ChargeMode.AUTO) ChargeMode.TOP_UP else it.plan.chargeMode),message=null)
        else if (it.plan.visits.size >= 3) it.copy(message = "방문지는 세 곳까지입니다. 기존 방문지를 수정해 주세요.")
        else it.copy(plan = it.plan.copy(visits = it.plan.visits + Visit(nextId++, activity, label),
            chargeMode=if(activity==Activity.CHARGE && it.plan.chargeMode==ChargeMode.AUTO) ChargeMode.TOP_UP else it.plan.chargeMode), message = null)
    }
    fun setActivity(id: Long, activity: Activity, label: String = "") = edit { s -> s.copy(plan = s.plan.copy(visits = s.plan.visits.map {
        if (it.id == id) it.copy(activity = activity, customLabel = label, placeholder=false,
            stayMinutes=if(activity==Activity.ARRIVE) 0 else it.stayMinutes) else it
    },chargeMode=if(activity==Activity.CHARGE && s.plan.chargeMode==ChargeMode.AUTO) ChargeMode.TOP_UP else s.plan.chargeMode), message = null) }
    fun setPlace(id: Long, place: Place?) = edit { s -> s.copy(plan = s.plan.copy(visits = s.plan.visits.map { if (it.id == id) it.copy(place = place,placeholder=false) else it }), question = null, message = null) }
    fun setStay(id: Long, minutes: Int) = edit { s ->
        if (minutes !in 0..720) s.copy(message = "머무는 시간은 0분부터 12시간까지 입력해 주세요.")
        else s.copy(plan = s.plan.copy(visits = s.plan.visits.map { if (it.id == id) it.copy(stayMinutes = minutes,placeholder=false) else it }), message = null)
    }
    fun remove(id: Long) = edit { s -> if (s.plan.visits.size == 1) s else s.copy(plan = s.plan.copy(visits = s.plan.visits.filterNot { it.id == id })) }
    fun move(id: Long, delta: Int) = edit { s -> s.copy(plan = moveVisit(s.plan, s.plan.visits.indexOfFirst { it.id == id }, delta)) }
    fun setCharge(mode: ChargeMode) = edit { it.copy(plan = it.plan.copy(chargeMode = mode), selectedId = null) }
    fun setReserve(km: Double) = edit { if (km.isFinite() && km in 0.0..200.0) it.copy(plan = it.plan.copy(reserveKm = km)) else it }
    fun setTime(id: Long?, at: LocalDateTime?) = edit { s ->
        if (at != null && at.isBefore(now())) s.copy(pendingTime = PendingTime(id, at), message = null)
        else withTime(s, id, at).copy(pendingTime = null, message = null)
    }
    private fun withTime(s: PlannerState, id: Long?, at: LocalDateTime?): PlannerState =
        if (id == null) s.copy(plan = s.plan.copy(departure = at))
        else s.copy(plan = s.plan.copy(visits = s.plan.visits.map { if (it.id == id) it.copy(deadline = at,placeholder=false) else it }))
    fun resolvePastTime(tomorrow: Boolean?) = edit { s ->
        val pending = s.pendingTime ?: return@edit s
        if (tomorrow == null) s.copy(pendingTime = null)
        else if (pending.visitId == null && !tomorrow) s.copy(pendingTime = null, message = "출발 예정 시각은 현재 이후로 정해 주세요.")
        else withTime(s, pending.visitId, if (tomorrow) pending.at.toLocalTime().atDate(now().toLocalDate().plusDays(1)) else pending.at)
            .copy(pendingTime = null, message = if (tomorrow) "내일 일정으로 정했어요." else "오늘 이미 지난 마감입니다. 후보에 지각 시간이 표시됩니다.")
    }
    fun setDemoRoutes(enabled: Boolean) = edit { it.copy(demoRoutes = enabled) }
    fun setDemoEnergy(enabled: Boolean) = edit { it.copy(demoEnergy = enabled, energy = if (enabled) repository.demoEnergy(now()) else realEnergy) }
    fun setDemoClock(enabled: Boolean) = edit { it.copy(demoClock = enabled, plan = it.plan.copy(departure = null)) }
    fun setTts(enabled: Boolean) { set(state.value.copy(tts = enabled)) }
    fun setAcknowledged(value: Boolean) {
        val s=state.value;if(s.busy) return
        val key=if(value && !s.departureExpired && s.question==null) s.selected?.let(::reviewKey) else null
        set(s.copy(acknowledged=key!=null,approvalKey=key))
    }
    private fun freshManualPark(s:PlannerState)=s.parkedConfirmed && s.parkedConfirmedAt?.let {Duration.between(it,clock()).seconds in 0..30}==true
    fun setParked(value: Boolean) { if(!state.value.busy) set(state.value.copy(parkedConfirmed = value,parkedConfirmedAt=if(value) clock() else null)) }
    fun select(id: String) { if (!state.value.busy && state.value.park!=ParkState.MOVING && state.value.options.any { it.id == id && it.selectable }) set(state.value.copy(selectedId = id, selectionExplicit = true, acknowledged = false,approvalKey=null)) }
    /** Select a computed mode result without changing the requested place or charging location. */
    fun selectMode(id: String) {
        val s=state.value
        if(s.busy || s.park==ParkState.MOVING) return
        val option=s.comparisonOptions.firstOrNull { it.id==id && it.chargeControlEnabled() } ?: return
        if(s.options.none { it.id==id }) setCharge(option.mode)
        select(id)
    }
    fun setCandidatePage(page: Int) {
        val s=state.value
        if(!s.busy && s.park!=ParkState.MOVING) set(s.copy(candidatePage=page.coerceAtLeast(0)))
    }
    /** UI and voice share this gate before the existing suspend preflight and send path. */
    fun prepareGuidance(): Boolean {
        val s=state.value
        if(!s.canStartGuidance()) return false
        if(s.removedPlaces().isEmpty()) setAcknowledged(true)
        return true
    }
    fun handleVoiceCommand(text: String, source: String): PlannerVoiceCommand? {
        val s=state.value
        if(s.busy || s.park==ParkState.MOVING || s.phase==ApplyPhase.APPLIED || s.pendingImport!=null || s.question!=null || s.pendingTime!=null) return null
        val command=plannerVoiceCommand(text,s.screen) ?: return null
        set(s.copy(transcript=text,partial="",listening=false,speechSource=source,message=null))
        when(command) {
            is PlannerVoiceCommand.Proceed -> {
                val groups=s.candidateGroups()
                val targets=if(command.placeName==null) emptyList() else groups.filter { group ->
                    group.any { it.placeName(s).replace(" ","")==command.placeName }
                }
                val target=if(command.placeName==null) s.selected?.takeIf { selected ->
                    groups.chunked(2).getOrNull(s.candidatePage).orEmpty().flatten().any { it.id==selected.id }
                } else targets.singleOrNull()?.let { group ->
                    group.firstOrNull { it.id==s.selectedId } ?: group.firstOrNull { it.mode==s.plan.chargeMode && it.selectable }
                        ?: group.firstOrNull { it.mode==ChargeMode.NONE && it.selectable }
                }
                if(target!=null) { selectMode(target.id);guide() }
                else set(state.value.copy(message="갈 곳을 화면에서 골라 주세요."))
            }
            is PlannerVoiceCommand.Charge -> {
                val group=s.candidateGroups().firstOrNull { it.any { o -> o.id==s.selectedId } }
                val target=group?.firstOrNull { it.mode==command.mode && it.chargeVisitId==s.selected?.chargeVisitId && it.selectable }
                    ?: group?.firstOrNull { it.mode==command.mode && it.selectable }
                if(target!=null) selectMode(target.id)
                else set(state.value.copy(message="이 장소에서는 해당 충전 방식을 확인할 수 없어요. 자세히를 확인해 주세요."))
            }
            PlannerVoiceCommand.OtherPlaces -> setCandidatePage((s.candidatePage+1) % s.candidateGroups().chunked(2).size.coerceAtLeast(1))
            PlannerVoiceCommand.Back -> back()
            PlannerVoiceCommand.Confirm -> Unit // ViewModel invokes the shared CTA path, never a second send path.
            PlannerVoiceCommand.KeepDestination -> if(s.removedPlaces().isNotEmpty() && s.canKeepDestination()) keepExistingDestination()
                else set(state.value.copy(message="기존 목적지를 남기려면 화면에서 방문을 확인해 주세요."))
            PlannerVoiceCommand.ReplaceDestination -> if(s.removedPlaces().isNotEmpty()) setAcknowledged(true)
        }
        return command
    }
    fun clearSelection() { if(!state.value.busy && state.value.park!=ParkState.MOVING) set(state.value.copy(selectedId=null,selectionExplicit=false,allowRecommendation=false,acknowledged=false,approvalKey=null)) }
    fun compare() { if (state.value.pendingTime == null && state.value.question == null) set(calculate(state.value).copy(screen = 1)) }
    fun guide() {
        val s = state.value
        if (s.selected == null || s.question!=null || s.pendingTime!=null || s.departureExpired) { set(s.copy(message = "장소·충전 방식과 출발 시각을 확인해 주세요.")); return }
        approvedBaseline = (s.currentRoute as? NaviRead.Known)?.route
        set(s.copy(screen = 2, selectionExplicit=true, acknowledged = false, approvalKey=null,phase = ApplyPhase.IDLE, requestSent=false, reviewedRoute=approvedBaseline, applyMessage = ""))
    }
    fun back() { if (!state.value.busy) set(state.value.copy(screen = (state.value.screen - 1).coerceAtLeast(0))) }
    fun restart() = edit { it.copy(plan=Itinerary(),screen=0,selectedId=null,selectionExplicit=false,
        transcript="",partial="",applied=emptyList(),question=null,pendingTime=null,message=null) }
    /** Materialize the selected candidate before appending: no unresolved stop is silently replaced. */
    fun keepExistingDestination() {
        val s=state.value
        if(s.busy || s.park==ParkState.MOVING || !s.canKeepDestination()) return
        val chosen=s.selected ?: return
        val destination=(s.currentRoute as? NaviRead.Known)?.route?.destination ?: return
        edit { it.copy(plan=it.plan.copy(visits=chosen.visits + Visit(nextId++,Activity.CUSTOM,"방문",
            place=resolvedPlace(destination),stayMinutes=0)),selectedId=null,selectionExplicit=false) }
        val matching=state.value.options.firstOrNull { it.mode==chosen.mode && it.chargeVisitId==chosen.chargeVisitId && it.selectable }
        if(matching!=null) { select(matching.id); guide();set(state.value.copy(keptDestinationName=destination.name)) }
    }
    fun importRoute() {
        val s=state.value;if(s.busy || s.park==ParkState.MOVING) return
        val route=(s.currentRoute as? NaviRead.Known)?.route
        set(if(route==null) s.copy(message="내비 상태를 다시 읽어 주세요.") else s.copy(pendingImport=route))
    }
    fun confirmImport(replace:Boolean) {
        if(!replace) { set(state.value.copy(pendingImport=null));return }
        edit { s ->
        val route = s.pendingImport ?: return@edit s
        val places = route.waypoints + listOfNotNull(route.destination)
        if (places.isEmpty()) s.copy(pendingImport=null,message = "내비에 설정된 목적지가 없습니다.")
        else s.copy(plan = s.plan.copy(visits = places.map { p ->
            val resolved=resolvedPlace(p)
            val matched=s.plan.visits.filter { it.place?.let { known -> PlaceMatcher.samePlace(known.toNavi(),p) }==true }
            if(matched.size==1) matched.first().copy(place=resolved,placeholder=false)
            else Visit(nextId++, Activity.CUSTOM, "방문", place=resolved)
        }), selectedId = null,pendingImport=null,
            message = if(places.any { !resolvedPlace(it).validCoordinates }) "내비의 일부 경유지 좌표가 미확인입니다. 해당 장소를 직접 다시 선택해 주세요." else "현재 내비 순서를 가져왔어요. 할 일과 시간을 수정할 수 있어요.")
        }
    }
    fun replaceGateways(g: GatewaySet) {
        revision++; readGeneration++; realEnergy = Energy(); socVersion++; rangeVersion++; gateways = g; tripCache.clear(); approvedBaseline = null
        set(state.value.copy(usingFakes = g.usingFakes, currentRoute = null, energy = Energy(), options = emptyList(), selectedId = null, phase = ApplyPhase.IDLE, listening = false))
    }
    suspend fun refresh() {
        if (state.value.busy) return
        val socToken = socVersion
        val rangeToken = rangeVersion
        val token = ++readGeneration
        val g = gateways
        set(state.value.copy(loading = true))
        try {
            val battery = if (simulateMissing) BatteryReading.Unknown("시연 미수신") else g.vehicle.readBatterySoc()
            activeRead(token,g)
            val range = if (simulateMissing) null else g.vehicle.readRangeKm()
            activeRead(token,g)
            val efficiency = if (simulateMissing) null else g.vehicle.readEfficiencyKmPerKwh()
            activeRead(token,g)
            val parkToken=++parkGeneration
            val park = g.vehicle.readParkState(); val parkAt=clock();activeRead(token,g)
            val route = if (simulateMissing) NaviRead.Unknown("시연: 내비 데이터 미수신") else g.navi.readRoute(); activeRead(token,g)
            val saved = if (simulateMissing) emptyList() else g.navi.readSavedPlaces(); activeRead(token,g)
            val at = now()
            val readEnergy = Energy(range, efficiency,
                (battery as? BatteryReading.Known)?.let { Measured(it.percent, Evidence(if(g.usingFakes) "차량 모의 값" else "Vehicle SOC", at, g.usingFakes)) })
            realEnergy = readEnergy.copy(
                socPercent = if(socToken == socVersion) readEnergy.socPercent else realEnergy.socPercent,
                rangeKm = if(rangeToken == rangeVersion) readEnergy.rangeKm else realEnergy.rangeKm)
            val energy = if(simulateMissing) Energy() else if(state.value.demoEnergy) repository.demoEnergy(at) else realEnergy
            val changed = energyChanged(state.value.energy, energy)
            val known = (route as? NaviRead.Known)?.route?.let { it.waypoints + listOfNotNull(it.destination) }.orEmpty()
            val acceptedPark=if(parkToken==parkGeneration) park else state.value.park
            val acceptedParkAt=if(parkToken==parkGeneration) parkAt else state.value.parkReadAt
            val keepManual=acceptedPark==state.value.park && acceptedPark!=ParkState.MOVING && freshManualPark(state.value)
            set(calculate(state.value.copy(energy = energy, acknowledged = if(changed) false else state.value.acknowledged, approvalKey = if(changed) null else state.value.approvalKey, park = acceptedPark, parkReadAt=acceptedParkAt,currentRoute = route, routeReadAt = at,
                parkedConfirmed=keepManual,parkedConfirmedAt=if(keepManual) state.value.parkedConfirmedAt else null,
                savedPlaces = (known.map(::resolvedPlace) + saved.filterNot { it.isBlankPlace() }.map { it.toPlace("내비 즐겨찾기·최근 장소") }).filter { it.name.isNotBlank() }.distinctBy { it.id }, loading = false, now = at)))
            val pending = journal.load()
            if (pending != null) {
                val actual = (route as? NaviRead.Known)?.route
                val applied = actual != null && sameRoute(actual, pending)
                set(state.value.copy(message = if(applied) "이전 요청의 경로 반영을 확인했어요." else "이전 경로 요청 결과가 미확인입니다. 현재 경로를 확인한 뒤 새로 선택해 주세요."))
                if(applied) {
                    journal.save(null)
                    set(state.value.copy(phase = ApplyPhase.APPLIED, acknowledged=false, approvalKey=null, actualRoute = actual, applyMessage = "다시 읽은 차량 내비에서 이전 요청의 반영을 확인했어요."))
                }
            }
        } finally { if (token == readGeneration && g===gateways) set(state.value.copy(loading = false)) }
    }
    suspend fun previewRoutes() {
        if (state.value.busy) return
        var plan = state.value.plan
        if (repository.remotePlacesEnabled && plan.visits.any { it.place?.demo == true && it.spokenPlace.isNotBlank() }) {
            plan = plan.copy(visits = plan.visits.map {
                if (it.place?.demo == true && it.spokenPlace.isNotBlank()) it.copy(place = null) else it
            })
            set(state.value.copy(plan = plan, options = emptyList(), selectedId = null, selectionExplicit = false,
                acknowledged = false, approvalKey = null))
        }
        var signature = plan.visits to plan.departure
        val g = gateways
        val generation = ++remoteGeneration
        fun current() = generation == remoteGeneration && g === gateways &&
            signature == (state.value.plan.visits to state.value.plan.departure)
        fun publish() {
            if (current()) set(calculate(state.value.copy(sourceStatuses = repository.sourceStatuses,
                searchingPlaces = repository.searchingPlaces)))
        }
        repository.beginRemote(plan)
        set(calculate(state.value.copy(searchingPlaces = repository.searchingPlaces, sourceStatuses = repository.sourceStatuses)))
        val searchStarted = System.nanoTime()
        val position = if (repository.needsPosition && !g.usingFakes)
            kotlinx.coroutines.withTimeoutOrNull(8_000) { g.navi.currentPosition() } else null
        if (!current()) return
        val resolutions = repository.resolveNamedVisits(plan, position, ::current)
        if (!current()) return
        if (resolutions.isNotEmpty()) {
            val visits = plan.visits.map { v -> resolutions[v.id]?.place?.let { v.copy(place = it) } ?: v }
            val question = plan.visits.firstNotNullOfOrNull { v -> resolutions[v.id]?.let { result ->
                when {
                    result.choices.isNotEmpty() -> SpeechQuestion("어느 ${v.spokenPlace}인가요?", v.id, result.choices.take(4))
                    result.missing -> SpeechQuestion("'${v.spokenPlace}'${kr.gakim.charge.ui.state.withReul(v.spokenPlace).removePrefix(v.spokenPlace)} 찾지 못했어요. 화면에서 장소를 골라 주세요", v.id)
                    else -> null
                }
            } }
            val changed = visits != plan.visits
            plan = state.value.plan.copy(visits = visits)
            signature = plan.visits to plan.departure
            set(calculate(state.value.copy(plan = plan, question = state.value.question ?: question,
                acknowledged = if (changed) false else state.value.acknowledged,
                approvalKey = if (changed) null else state.value.approvalKey)))
        }
        val budget = (8_000 - (System.nanoTime() - searchStarted) / 1_000_000).coerceAtLeast(0)
        repository.refreshRemote(plan, position, now(), ::current, ::publish, budget)
        if (!current()) return
        publish()
        if (state.value.demoRoutes) return
        val visits = state.value.plan.visits
        val first = visits.firstOrNull() ?: return
        for (place in repository.candidates(first)) {
            if (tripCache[place.id]?.let { Evidence(it.source, it.observedAt).fresh(now(), 120) } == true) continue
            val result = gateways.navi.previewTrip(place.toNavi()); if (!current()) return
            if (result != null) tripCache[place.id] = result
        }
        if (!current()) return; set(calculate(state.value))
    }
    suspend fun refreshPark() {
        if(state.value.busy) return
        val token=++parkGeneration;val g=gateways
        val park=g.vehicle.readParkState()
        if(token!=parkGeneration || g!==gateways) return
        val s=state.value
        val keepManual=park==s.park && park!=ParkState.MOVING && freshManualPark(s)
        set(s.copy(park=park,parkReadAt=clock(),acknowledged=if(park==ParkState.MOVING) false else s.acknowledged,
            parkedConfirmed=keepManual,parkedConfirmedAt=if(keepManual) s.parkedConfirmedAt else null,
            approvalKey=if(park==ParkState.MOVING) null else s.approvalKey))
    }
    private fun reviewKey(o:Option):String = listOf(o.id,o.mode,o.chargeVisitId,o.chargingStatus,o.chargeMinutes,o.energyKwh,
        o.totalDistanceKm,o.energy.status,o.energy.missingKm,o.conditions,
        o.visits.map { listOf(it.id,it.activity,it.customLabel,it.place?.id,it.place?.latitude,it.place?.longitude,it.stayMinutes,it.deadline) },
        o.results.map { listOf(it.arrival?.withSecond(0)?.withNano(0),it.slackMinutes) },o.evidence.map { it.source to it.demo }).toString()
    private fun calculate(s: PlannerState): PlannerState {
        // Keep the reviewed result after handoff; live readings no longer revise an already applied route.
        if(s.phase==ApplyPhase.APPLIED) return s
        val s = s.copy(searchingPlaces = repository.searchingPlaces,
            nearbyRealMarts = repository.candidates(Visit(-1)).filterNot { it.demo }.ifEmpty { s.nearbyRealMarts },
            exampleDestination = repository.exampleDestination ?: s.exampleDestination)
        val calculationEnergy=s.energy.copy(rangeKm=s.energy.usableRange(s.now))
        val unresolved = s.plan.visits.filter { it.place == null }
        if (unresolved.size > 1) return s.copy(options = emptyList(), comparisonOptions = emptyList(), chargingSites = emptyMap(), selectedId = null, selectionExplicit=false,
            inputBlocker = "장소 미정인 볼일이 여러 개예요. 한 곳의 장소부터 골라 주세요.")
        val index = s.plan.visits.indexOfFirst { it.place == null }.let { if (it < 0) 0 else it }
        val candidates = repository.candidates(s.plan.visits[index])
        val sites = mutableMapOf<String, ChargingSite>()
        val noChargerFallback = mutableSetOf<String>()
        val allOptions = candidates.flatMap { candidate ->
            val visits = s.plan.visits.mapIndexed { i, v -> if(i == index) v.copy(place = candidate) else v }
            val plan = s.plan.copy(visits = visits)
            val legs = visits.mapIndexed { i, v ->
                val p = v.place ?: return@mapIndexed Leg(null, null)
                val fixture = repository.leg(if(i == 0) null else visits[i-1].place, p, s.now, s.demoRoutes)
                if (fixture.minutes != null) fixture else if (i == 0) tripCache[p.id]?.let {
                    val e = Evidence(it.source, it.observedAt)
                    Leg(Measured(it.minutes, e), Measured(it.distanceKm, e))
                } ?: Leg(null, null) else Leg(null, null)
            }
            val chargeIndices=visits.indices.toList()
            val modes = listOf(ChargeMode.NONE, ChargeMode.TOP_UP, ChargeMode.REQUIRED)
            val raw=modes.flatMap { mode -> if(mode==ChargeMode.NONE) listOf(evaluateItinerary(plan,legs,mode,null,null,calculationEnergy,s.now))
                else chargeIndices.map { idx ->
                    val site=repository.charging(visits[idx].place!!,s.now)?.also { sites[visits[idx].place!!.id]=it } ?: ChargingSite(Availability.UNKNOWN,evidence=Evidence("충전 시설 정보 없음",s.now))
                    evaluateItinerary(plan,legs,mode,idx,site,calculationEnergy,s.now)
                } }
            // 충전을 요청했어도 충전기가 없는 장소는 '충전 안 함' 결과로라도 비교·선택할 수 있어야 한다(에뮬레이터 확인: 전부 '시각 미확인'으로 막혔다).
            if (visits.none { v -> v.place?.id?.let(sites::containsKey) == true }) noChargerFallback += raw.first { it.mode == ChargeMode.NONE }.id
            val baseline = evaluateItinerary(plan, legs, ChargeMode.NONE, null, null, calculationEnergy, s.now).totalMinutes
            raw.map { o ->
                o.copy(extraMinutes = if(o.totalMinutes != null && baseline != null) o.totalMinutes!! - baseline else null,
                    closingNotices = o.results.mapNotNull { result -> result.visit.place?.let { place ->
                        repository.closingCalendar.notice(place.name, (result.arrival ?: result.visit.deadline ?: plan.departure ?: s.now).toLocalDate(), s.now.toLocalDate(), place.address)
                    } })
            }
        }
        val options = allOptions.filter { s.plan.chargeMode == ChargeMode.AUTO || it.mode == s.plan.chargeMode || it.id in noChargerFallback }
        val previous = s.selectedId?.takeIf { id -> s.selectionExplicit && options.any { it.id == id && it.selectable } }
        val searching = repository.searchingPlaces
        val next=s.copy(searchingPlaces = searching, options = options, comparisonOptions = allOptions, chargingSites = sites, selectedId = previous ?: if(s.allowRecommendation) recommend(options)?.id else null, selectionExplicit = previous != null,
            inputBlocker=when { s.departureExpired -> "출발 예정 시각이 지났어요. 지금 출발하거나 새 시각을 정해 주세요.";candidates.isEmpty() && !searching -> noNearbyPlace;else->null })
        // A completed handoff has no pending approval to invalidate. Pre-send checks remain unchanged.
        return if(next.phase != ApplyPhase.APPLIED && next.acknowledged && next.approvalKey!=next.selected?.let(::reviewKey)) next.copy(acknowledged=false,approvalKey=null,
            applyMessage="표시된 도착·충전 조건이 바뀌었어요. 다시 확인해 주세요.") else next
    }
    fun onSpeech(event: SpeechEvent, source: String = "공식 SpeechToText") {
        when (event) {
            SpeechEvent.Listening -> set(state.value.copy(listening = true, partial = "", message = null, speechSource = source))
            is SpeechEvent.Level -> set(state.value.copy(level = event.value.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0f))
            is SpeechEvent.Partial -> set(state.value.copy(partial = event.text, listening = true, speechSource = source))
            is SpeechEvent.Error -> set(state.value.copy(listening = false, message = if(Regex("확정 문장|인식|NO_MATCH|NO_SPEECH",RegexOption.IGNORE_CASE).containsMatchIn(event.reason)) speechNotUnderstood else "음성 서비스 오류: ${event.reason}. 화면으로 이어서 입력할 수 있어요."))
            is SpeechEvent.Final -> acceptTranscript(event.text, source)
        }
    }
    fun speechStopped() { set(state.value.copy(listening = false, level = 0f)) }
    fun acceptTranscript(text: String, source: String = "테스트 확정 문장") = edit { s ->
        val result = interpretItinerary(text, s.plan, speechCatalog(), now(), focusedVisitId=speechFocus, remotePlaces = repository.remotePlacesEnabled)
        nextId = maxOf(nextId, (result.plan.visits.maxOfOrNull { it.id } ?: 0) + 1)
        val past = result.plan.visits.firstOrNull { v -> v.deadline?.isBefore(now()) == true && s.plan.visits.firstOrNull { it.id == v.id }?.deadline != v.deadline }
        var next = s.copy(plan = result.plan, transcript = text, partial = "", listening = false, speechSource = source,
            applied = result.applied, question = result.question, pendingTime = past?.let { PendingTime(it.id, it.deadline!!) },
            message = if(result.applied.isEmpty() && result.question == null) speechNotUnderstood else null)
        if (past != null) next = withTime(next, past.id, s.plan.visits.firstOrNull { it.id == past.id }?.deadline)
        next
    }
    fun resolveTimeRole(departure: Boolean) {
        val text = state.value.transcript
        acceptTranscript(if(departure) "$text 출발" else "$text 도착까지", state.value.speechSource)
    }
    fun clearQuestion() { set(state.value.copy(question = null)) }
    fun resolveTimeChoice(id:Long?,at:LocalDateTime) = edit { s ->
        if(at.isBefore(now())) s.copy(question=null,pendingTime=PendingTime(id,at))
        else withTime(s,id,at).copy(question=null,applied=s.applied+"${if(id==null) "출발 시각" else "도착 마감"} ${clock(at)} 확인")
    }

    suspend fun confirm() {
        val s = state.value
        val option = s.selected ?: return
        if (s.busy || s.phase in listOf(ApplyPhase.APPLIED,ApplyPhase.UNCONFIRMED) || !s.acknowledged || s.pendingTime != null || s.question != null || s.pendingImport!=null || s.departureExpired) return
        if(s.approvalKey!=reviewKey(option)) { set(s.copy(acknowledged=false,approvalKey=null,applyMessage="비교 조건을 다시 확인해 주세요."));return }
        if (option.visits.size > 3 || option.visits.any { it.place?.validCoordinates != true }) { set(s.copy(applyMessage = "유효한 장소와 방문 순서를 확인해 주세요.")); return }
        val token = ++revision
        val g = gateways
        confirmedAt = clock()
        set(s.copy(phase = ApplyPhase.SENDING, applyMessage = "차량과 현재 경로를 다시 확인하는 중", revision = revision))
        try {
            val initialParkToken = ++parkGeneration
            val park = g.vehicle.readParkState(); active(token)
            if (initialParkToken != parkGeneration || g !== gateways) {
                set(state.value.copy(phase=ApplyPhase.FAILED, acknowledged=false, approvalKey=null,
                    applyMessage="주차 상태가 다시 조회되어 기존 확인을 취소했어요. 차량 상태와 경로를 다시 확인해 주세요."))
                return
            }
            if (park == ParkState.MOVING || park == ParkState.UNKNOWN && !freshManualPark(state.value)) {
                set(state.value.copy(park = park, parkedConfirmed=false,parkedConfirmedAt=null,phase = ApplyPhase.FAILED, acknowledged=false,approvalKey=null,applyMessage = "주차 상태를 확인한 뒤 다시 시도해 주세요.")); return
            }
            if(park==ParkState.PARKED) set(state.value.copy(park=park,parkReadAt=clock(),parkedConfirmed=false,parkedConfirmedAt=null))
            val current = if (simulateMissing) NaviRead.Unknown("시연 미수신") else g.navi.readRoute(); active(token)
            if (current !is NaviRead.Known) { set(state.value.copy(phase = ApplyPhase.FAILED, applyMessage = "현재 경로를 읽지 못해 요청을 보내지 않았어요.", currentRoute = current)); return }
            if(ambiguousRead(current.route)) {
                set(state.value.copy(phase=ApplyPhase.FAILED,acknowledged=false,approvalKey=null,
                    applyMessage="동명이 장소의 좌표·식별 정보가 없어 방문 순서를 확인할 수 없어요. 차량 내비에서 직접 확인하고 장소를 다시 선택해 주세요."));return
            }
            val baseline = approvedBaseline
            if (baseline == null || !sameRoute(current.route, baseline)) {
                approvedBaseline = current.route
                set(state.value.copy(phase = ApplyPhase.IDLE, currentRoute = current, reviewedRoute=current.route, acknowledged = false,
                    applyMessage = "내비 경로가 바뀌었어요. 아래 현재 경로와 방문 순서를 확인하고 다시 승인해 주세요.")); return
            }
            val places = option.visits.map { it.place!!.toNavi() }
            val planned = NaviRoute(places.last(), places.dropLast(1))
            if (sameRoute(current.route, planned)) {
                set(state.value.copy(phase = ApplyPhase.APPLIED, acknowledged=false, approvalKey=null, actualRoute = current.route, applyMessage = "이미 선택한 순서로 설정돼 있어요. 추가 요청 없이 확인했어요.")); return
            }
            if (simulateApplyFailure) { set(state.value.copy(phase = ApplyPhase.FAILED, applyMessage = "시연 실패 주입: 내비 요청 전송 실패")); return }
            val finalParkToken = ++parkGeneration
            val finalPark = g.vehicle.readParkState(); active(token)
            if (finalParkToken != parkGeneration || g !== gateways) {
                set(state.value.copy(phase=ApplyPhase.FAILED, acknowledged=false, approvalKey=null,
                    applyMessage="주차 상태가 다시 조회되어 전송을 중단했어요. 차량 상태와 경로를 다시 확인해 주세요."))
                return
            }
            if(finalPark == ParkState.MOVING || finalPark == ParkState.UNKNOWN && !freshManualPark(state.value)) {
                set(state.value.copy(park=finalPark,parkedConfirmed=false,parkedConfirmedAt=null,phase=ApplyPhase.FAILED,acknowledged=false,approvalKey=null,applyMessage="전송 직전 주차 상태를 확인하지 못해 중단했어요."));return
            }
            if(s.plan.departure?.isBefore(now())==true) {
                set(calculate(state.value.copy(now=now(),phase=ApplyPhase.IDLE,acknowledged=false,approvalKey=null,
                    applyMessage="확인 중 출발 예정 시각이 지났어요. 출발 시각을 다시 정해 주세요.")))
                return
            }
            journal.save(planned)
            sentPlaces.addAll(option.visits.mapNotNull { it.place })
            set(state.value.copy(applyMessage = "확인한 경로 요청을 보내는 중", ownHandoff = true))
            val request = g.navi.applyRoute(planned); active(token)
            if (request is NaviRequest.Failed) {
                journal.save(null)
                set(state.value.copy(phase = ApplyPhase.FAILED, ownHandoff = false, applyMessage = "요청 실패: ${request.reason}")); return
            }
            set(state.value.copy(phase = ApplyPhase.VERIFYING, requestSent=true, applyMessage = "요청 전송됨 · 차량 내비 반영을 다시 읽는 중"))
            repeat(verifyAttempts) {
                delay(verifyDelayMs); active(token)
                val read = g.navi.readRoute(); active(token)
                val actual = (read as? NaviRead.Known)?.route
                if (actual != null && sameRoute(actual, planned)) {
                    journal.save(null)
                    set(state.value.copy(phase = ApplyPhase.APPLIED, acknowledged=false, approvalKey=null, actualRoute = actual, currentRoute = read,
                        savedPlaces=(routePlaces(actual)+state.value.savedPlaces.filterNot { it.source.startsWith("현재 내비") }).distinctBy { it.id },
                        applyMessage = "차량 내비에서 목적지와 방문 순서 반영을 확인했어요.")); return
                }
                set(state.value.copy(actualRoute = actual))
            }
            set(state.value.copy(phase = ApplyPhase.UNCONFIRMED, acknowledged = false, applyMessage = "요청은 보냈지만 반영을 확인하지 못했어요. 차량 내비를 확인하고 다시 읽어 주세요. 자동 재전송하지 않습니다."))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { set(state.value.copy(phase = if(state.value.ownHandoff) ApplyPhase.UNCONFIRMED else ApplyPhase.FAILED,
            acknowledged=false,applyMessage = "내비 처리 중 응답을 확인하지 못했어요: ${e.message}. 현재 경로를 다시 읽어 주세요.")) }
    }
    fun consumeReturnToFront(): Boolean {
        if (state.value.ownHandoff && state.value.phase in listOf(ApplyPhase.APPLIED, ApplyPhase.UNCONFIRMED, ApplyPhase.FAILED)) {
            val recent=confirmedAt?.let { Duration.between(it,clock()).seconds in 0..30 } == true
            set(state.value.copy(ownHandoff = false)); return recent
        }
        return false
    }
    fun stop() {
        speechStopped()
        if(!state.value.ownHandoff) {
            revision++;readGeneration++;remoteGeneration++
            set(state.value.copy(loading=false,phase=if(state.value.busy) ApplyPhase.IDLE else state.value.phase,
                acknowledged=false,parkedConfirmed=false,parkedConfirmedAt=null,applyMessage=if(state.value.busy) "전송 전 화면을 벗어나 중단했어요. 다시 확인해 주세요." else state.value.applyMessage))
        }
    }
    companion object {
        private fun NaviPlace.hasCoordinates()=latitude!=0.0 || longitude!=0.0
        fun ambiguousRead(route:NaviRoute):Boolean = (route.waypoints+listOfNotNull(route.destination))
            .filter { it.poiId.isBlank() && !it.hasCoordinates() }.groupBy { it.name.trim() }.any { it.value.size>1 }
        fun sameRoute(a: NaviRoute, b: NaviRoute): Boolean {
            if (a.destination == null || b.destination == null) return a.destination == null && b.destination == null && a.waypoints == b.waypoints
            if(a.waypoints.size!=b.waypoints.size) return false
            val pairs=a.waypoints.zip(b.waypoints)+listOf(a.destination to b.destination)
            if(pairs.any { (x,y)->!PlaceMatcher.samePlace(x,y) }) return false
            val weak=pairs.filter { (x,y)->!(x.poiId.isNotBlank() && y.poiId.isNotBlank()) && !(x.hasCoordinates() && y.hasCoordinates()) }
            return weak.groupBy { it.first.name.trim() }.none { it.value.size>1 }
        }
    }
}

fun Place.toNavi() = NaviPlace(name, address, latitude, longitude, poiId, poiSubId)
fun NaviPlace.toPlace(source: String) = Place(PlaceMatcher.keyOf(this), name, latitude, longitude, address, poiId, poiSubId, source = source)
