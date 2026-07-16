package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.PrefillRequestMapper
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

internal interface PrefillScheduler {
    fun schedule(delayMs: Int, task: () -> Unit): Any
    fun cancel(handle: Any)
}

internal class PrefillSubmission(
    private val api: AdventureApi,
    private val scheduler: PrefillScheduler = BrowserPrefillScheduler,
) {
    private val scope = MainScope()
    private var pending: Any? = null
    private var nextRevision = 0L
    private var turnActive = false

    fun changed(session: GameSession, scenario: Scenario, sessionId: String, action: String) {
        pending?.let(scheduler::cancel)
        pending = null
        val revision = ++nextRevision
        if (turnActive || action.isBlank()) return
        pending = scheduler.schedule(150) {
            pending = null
            if (!turnActive && revision == nextRevision) scope.launch {
                runCatching { api.prefill(PrefillRequestMapper.map(session, scenario, sessionId, revision, action)) }
            }
        }
    }

    fun turnStarted() {
        turnActive = true
        pending?.let(scheduler::cancel)
        pending = null
    }

    fun turnFinished() {
        turnActive = false
    }
}

private object BrowserPrefillScheduler : PrefillScheduler {
    override fun schedule(delayMs: Int, task: () -> Unit): Any = window.setTimeout(task, delayMs)
    override fun cancel(handle: Any) = window.clearTimeout(handle.unsafeCast<Int>())
}
