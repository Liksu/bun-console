package dev.bunconsole.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.APP)
@State(name = "dev.bunconsole.settings", storages = [Storage(value = "bun-console.xml", roamingType = RoamingType.DISABLED)])
class BunConsoleSettings : PersistentStateComponent<BunConsoleSettings.Options> {
    data class Options(
        var bunPath: String = "",
        var enterRuns: Boolean = false,
        var debugEnabled: Boolean = false,
    )

    @Volatile private var options = Options()
    private val enterModeListeners = CopyOnWriteArrayList<() -> Unit>()
    override fun getState(): Options = options.copy()
    override fun loadState(state: Options) {
        val changed = options.enterRuns != state.enterRuns
        options = state.copy()
        if (changed) enterModeListeners.forEach { it() }
    }
    var bunPath: String
        get() = options.bunPath
        set(value) { options = options.copy(bunPath = value) }
    var enterRuns: Boolean
        get() = options.enterRuns
        set(value) {
            if (options.enterRuns == value) return
            options = options.copy(enterRuns = value)
            enterModeListeners.forEach { it() }
        }
    var debugEnabled: Boolean
        get() = options.debugEnabled
        set(value) { options = options.copy(debugEnabled = value) }

    fun onEnterModeChanged(parent: Disposable, listener: () -> Unit) {
        enterModeListeners.add(listener)
        Disposer.register(parent, Disposable { enterModeListeners.remove(listener) })
    }

    companion object {
        fun getInstance(): BunConsoleSettings = ApplicationManager.getApplication().getService(BunConsoleSettings::class.java)
    }
}
