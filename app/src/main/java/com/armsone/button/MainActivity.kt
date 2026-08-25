package com.armsone.button

import android.os.Bundle
import android.content.pm.ApplicationInfo
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.armsone.button.platform.AndroidHardwareGateway
import com.armsone.button.state.AppViewModel
import com.armsone.button.ui.ButtonApp
import com.armsone.button.update.DirectUpdateManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_WIDGET_ACTION = "button_action" }
    private lateinit var model: AppViewModel
    private lateinit var hardware: AndroidHardwareGateway
    private var pendingWidgetAction: String? = null
    private var widgetDispatchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hardware = AndroidHardwareGateway(this)
        DirectUpdateManager.get(applicationContext).start()
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(application, hardware) as T
        })[AppViewModel::class.java]
        model.attachHardware(hardware)
        hardware.onIncoming = model::presentIncoming
        hardware.onAcknowledge = model::presentAcknowledge
        hardware.onTransportStatus = model::updateTransport
        hardware.onPresence = model::updateRemoteMember
        hardware.onMembers = model::replaceRemoteMembers
        pendingWidgetAction = intent.getStringExtra(EXTRA_WIDGET_ACTION)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.uiState.collect { state ->
                    hardware.sync(state)
                    schedulePendingWidgetAction()
                }
            }
        }
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            model.applyFixture(intent.getStringExtra("button_fixture"))
        }
        setContent { ButtonApp(model) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingWidgetAction = intent.getStringExtra(EXTRA_WIDGET_ACTION)
        if (::model.isInitialized) {
            hardware.sync(model.uiState.value)
            schedulePendingWidgetAction()
        }
    }

    override fun onStart() {
        super.onStart()
        if (::model.isInitialized) model.onForeground()
    }

    override fun onStop() {
        if (::model.isInitialized) model.onBackground()
        super.onStop()
    }

    private fun schedulePendingWidgetAction() {
        if (pendingWidgetAction == null || widgetDispatchJob?.isActive == true) return
        widgetDispatchJob = lifecycleScope.launch {
            delay(750)
            handlePendingWidgetAction()
        }
    }

    private fun handlePendingWidgetAction() {
        val action = pendingWidgetAction ?: return
        pendingWidgetAction = null
        val state = model.uiState.value
        if (state.phase != com.armsone.button.state.AppPhase.HOME) {
            model.showError("먼저 가족 공간에 참여해 주세요.")
        } else when (action) {
            "quiet" -> model.sendQuietTap()
            "ding" -> model.sendDingDong()
            "voice" -> model.showVoice(true)
        }
    }

    override fun onDestroy() {
        widgetDispatchJob?.cancel()
        hardware.close()
        super.onDestroy()
    }
}
