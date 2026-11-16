package kr.gakim.charge.ui.planner

/** Small screen contract also lets previews render without vehicle services or a ViewModel scope. */
interface PlannerActions {
    val controller: ItineraryController
    fun edit(action: () -> Unit)
    fun toggleListening()
    fun stopListening()
    fun refresh()
    fun back()
    fun startGuidance()
}
