package kr.gakim.charge

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kr.gakim.charge.data.pleos.GatewayFactory
import kr.gakim.charge.ui.planner.PlannerViewModel
import kr.gakim.charge.ui.planner.ItineraryApp
import kr.gakim.charge.ui.theme.GakimTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PlannerViewModel by viewModels()
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.refresh() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = (GatewayFactory.PLEOS_PERMISSIONS.toList() + "android.car.permission.CAR_ENERGY").filter { ContextCompat.checkSelfPermission(this,it) != PackageManager.PERMISSION_GRANTED }
        if(missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        setContent {
            GakimTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                BackHandler(enabled = state.screen > 0) { viewModel.back() }
                ItineraryApp(state,viewModel)
            }
        }
        lifecycleScope.launch {
            viewModel.state.collect {
                if(viewModel.consumeReturnToFront() && !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    startActivity(Intent(this@MainActivity,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            }
        }
        debugIntent(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); debugIntent(intent) }
    private fun debugIntent(intent: Intent) {
        if(!BuildConfig.DEBUG) return
        intent.getStringExtra("gakim_debug_action")?.let(viewModel::debugAction)
        intent.getStringExtra("gakim_transcript_b64")?.let { encoded ->
            runCatching { String(Base64.decode(encoded,Base64.DEFAULT),Charsets.UTF_8) }.getOrNull()?.let(viewModel::debugTranscript)
        }
        intent.removeExtra("gakim_debug_action"); intent.removeExtra("gakim_transcript_b64")
    }
    override fun onStart() { super.onStart(); viewModel.onStart() }
    override fun onWindowFocusChanged(hasFocus:Boolean) { super.onWindowFocusChanged(hasFocus);if(hasFocus) viewModel.onWindowRegainedFocus() }
    override fun onStop() { viewModel.onStop(); super.onStop() }
}
