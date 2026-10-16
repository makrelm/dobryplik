package pl.agent.telefon

import android.app.Application
import pl.agent.telefon.core.AgentRuntime

class AgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AgentRuntime.init(this)
    }
}
