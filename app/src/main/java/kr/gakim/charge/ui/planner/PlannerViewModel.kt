package kr.gakim.charge.ui.planner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kr.gakim.charge.R
import kr.gakim.charge.BuildConfig
import kr.gakim.charge.data.*
import kr.gakim.charge.data.pleos.GatewayFactory
import kr.gakim.charge.domain.itinerary.*
import org.json.JSONArray
import org.json.JSONObject

class PlannerViewModel(application: Application) : AndroidViewModel(application), PlannerActions {
    private var gateways = GatewayFactory.create(application, application.resources.getBoolean(R.bool.use_fakes_default))
    override val controller = ItineraryController(gateways, repository = kr.gakim.charge.data.remote.remoteRepository(application), journal = PreferencesRouteJournal(application))
    val state = controller.state
    private var energyJob: Job? = null
    private var readJob: Job? = null
    private var calcJob: Job? = null
    private var previewPlan: Pair<List<Visit>, java.time.LocalDateTime?>? = null
    private var applyJob: Job? = null
    private var speechJob: Job? = null
    private var speechGeneration = 0L
    private var awaitingSearch = false
    private var foreground=true
    private var parkJob:Job?=null
    init {
        observeEnergy()
        refresh()
        viewModelScope.launch { while(true) { delay(5_000); if(foreground) {
            controller.tick();controller.refreshPark()
            if(state.value.park==ParkState.MOVING) stopListening()
        } } }
    }
    private fun observeEnergy() { energyJob?.cancel(); energyJob = viewModelScope.launch { controller.observeEnergy() } }
    private fun preview() {
        val signature = state.value.plan.visits to state.value.plan.departure
        if (calcJob?.isActive == true && previewPlan == signature) return
        calcJob?.cancel()
        previewPlan = signature
        calcJob = viewModelScope.launch { controller.previewRoutes() }
    }
    override fun edit(action: () -> Unit) {
        if (state.value.busy) return
        stopListening()
        val wasDemo = state.value.demoEnergy
        action()
        if (wasDemo && !state.value.demoEnergy) { refresh(); return }
        preview()
    }
    override fun refresh() {
        if (state.value.busy) return
        stopListening(); readJob?.cancel()
        readJob = viewModelScope.launch { controller.refresh(); preview() }
    }
    fun useFakes(enabled: Boolean) {
        if (state.value.busy || enabled == gateways.usingFakes) return
        stopListening(); readJob?.cancel(); calcJob?.cancel(); energyJob?.cancel(); gateways.release()
        gateways = GatewayFactory.create(getApplication(), enabled)
        controller.replaceGateways(gateways); observeEnergy(); refresh()
    }
    override fun toggleListening() {
        if (state.value.busy || state.value.park == ParkState.MOVING) return
        if(speechJob?.isActive == true) { stopListening(); return }
        gateways.speechOutput.stop()
        val token = ++speechGeneration
        val source = gateways
        speechJob = viewModelScope.launch {
            try {
                var finalReceived=false
                val ended=withTimeoutOrNull(45_000) {
                source.speech.listen().collect { event ->
                    if (token == speechGeneration) {
                        if (event is SpeechEvent.Final) {
                            finalReceived=true
                            val speechSource=if(source.usingFakes) "모의 SpeechGateway" else "공식 SpeechToText · 확정"
                            val command=controller.handleVoiceCommand(event.text,speechSource)
                            if(command==PlannerVoiceCommand.Confirm) startGuidance()
                            else if(command==null && state.value.screen==2) controller.ignoreOnConfirm(event.text,speechSource)
                            else if(command==null) { controller.onSpeech(event,speechSource);preview() }
                            // 들은 내용을 되읽고 다음 물음으로 끝낸다. 안내 시작을 말한 뒤에는 다시 묻지 않는다.
                            if(state.value.tts && command!=PlannerVoiceCommand.Confirm) state.value.spokenPrompt().takeIf { it.isNotBlank() }?.let { source.speechOutput.speak(it) }
                            speechGeneration++
                        } else controller.onSpeech(event, if(source.usingFakes) "모의 SpeechGateway" else "공식 SpeechToText")
                    }
                }
                }
                if(!finalReceived && token==speechGeneration) controller.onSpeech(SpeechEvent.Error(if(ended==null) "45초 동안 확정 문장을 받지 못했습니다. 마이크 입력과 음성 서비스를 확인해 주세요" else "인식이 끝났지만 확정 문장을 받지 못했습니다"))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { controller.onSpeech(SpeechEvent.Error(e.message ?: "음성 연결 실패")) }
            finally { if(token == speechGeneration) controller.speechStopped() }
        }
    }
    override fun stopListening() { speechGeneration++; speechJob?.cancel(); speechJob = null; controller.speechStopped() }
    override fun startGuidance() {
        if(controller.prepareGuidance()) confirm()
    }
    fun confirm() {
        if (applyJob?.isActive == true) return
        stopListening(); calcJob?.cancel(); readJob?.cancel()
        applyJob = viewModelScope.launch { controller.confirm() }
    }
    override fun back() { if(!state.value.busy) { stopListening(); controller.back() } }
    fun searchPlace(keyword:String) {
        if(state.value.busy) return
        stopListening()
        calcJob=viewModelScope.launch {
            val result=gateways.navi.openPlaceSearch(keyword)
            awaitingSearch=result is NaviRequest.Sent
            if(result is NaviRequest.Failed) controller.onSpeech(SpeechEvent.Error(result.reason))
        }
    }
    fun onStart() {
        foreground=true
        parkJob?.cancel();parkJob=viewModelScope.launch { controller.refreshPark() }
        if(!state.value.busy && !state.value.loading) { awaitingSearch=false;refresh() }
    }
    fun onWindowRegainedFocus() {
        if(!foreground || awaitingSearch || state.value.ownHandoff) onStart()
        else { parkJob?.cancel();parkJob=viewModelScope.launch {controller.refreshPark()} }
    }
    fun onStop() {
        foreground=false;parkJob?.cancel()
        stopListening(); gateways.speechOutput.stop()
        if(!state.value.ownHandoff) { readJob?.cancel(); calcJob?.cancel(); applyJob?.cancel(); controller.stop() }
    }
    fun consumeReturnToFront() = controller.consumeReturnToFront()
    fun debugTranscript(text: String) {
        if(!BuildConfig.DEBUG) return
        val command=controller.handleVoiceCommand(text,"ADB 확정문장 주입 · STT 엔진 미시험")
        if(command==PlannerVoiceCommand.Confirm) startGuidance()
        else if(command==null && state.value.screen==2) controller.ignoreOnConfirm(text,"ADB 확정문장 주입 · STT 엔진 미시험")
        else if(command==null) edit { controller.onSpeech(SpeechEvent.Final(text), "ADB 확정문장 주입 · STT 엔진 미시험") }
    }
    fun debugAction(name: String) {
        if(!BuildConfig.DEBUG) return
        when(name) {
            "demo" -> edit { controller.setDemoClock(true); controller.setDemoRoutes(true); controller.setDemoEnergy(true) }
            "demo_off" -> { edit { controller.setDemoClock(false); controller.setDemoRoutes(false); controller.setDemoEnergy(false) }; refresh() }
            "missing" -> { controller.simulateMissing = true; controller.setDemoEnergy(false); refresh() }
            "recover" -> { controller.simulateMissing = false; controller.simulateApplyFailure = false; refresh() }
            "fail" -> { controller.simulateApplyFailure = true }
        }
    }
    override fun onCleared() { energyJob?.cancel(); stopListening(); readJob?.cancel(); calcJob?.cancel(); applyJob?.cancel();parkJob?.cancel(); gateways.release() }
}

private class PreferencesRouteJournal(app: Application) : RouteJournal {
    private val prefs = app.getSharedPreferences("navigation_journal", 0)
    private fun encode(p: NaviPlace) = JSONObject().put("name", p.name).put("address", p.address).put("lat",p.latitude).put("lon",p.longitude).put("poi",p.poiId).put("sub",p.poiSubId)
    private fun decode(p: JSONObject) = NaviPlace(p.getString("name"),p.optString("address"),p.getDouble("lat"),p.getDouble("lon"),p.optString("poi"),p.optString("sub"))
    override fun save(route: NaviRoute?) {
        if(route == null) prefs.edit().remove("pending").commit()
        else {
            val data = JSONObject().put("destination", route.destination?.let(::encode)).put("stops",JSONArray().apply { route.waypoints.forEach { put(encode(it)) } })
            prefs.edit().putString("pending",data.toString()).commit()
        }
    }
    override fun load(): NaviRoute? = try {
        prefs.getString("pending",null)?.let { raw ->
            val obj = JSONObject(raw); val stops = obj.getJSONArray("stops")
            NaviRoute(obj.optJSONObject("destination")?.let(::decode), (0 until stops.length()).map { decode(stops.getJSONObject(it)) })
        }
    } catch(e: Exception) { null }
}
