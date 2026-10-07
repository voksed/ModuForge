package dev.moduforge.host

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.moduforge.host.appearance.AppearanceStore
import dev.moduforge.host.consent.ConsentCoordinator
import dev.moduforge.host.consent.InputCoordinator
import dev.moduforge.host.runtime.BackgroundCoordinator
import dev.moduforge.host.runtime.IncomingPackages
import dev.moduforge.host.update.UpdateManager
import dev.moduforge.host.ui.ModuForgeApp
import dev.moduforge.host.ui.theme.ModuForgeTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var consent: ConsentCoordinator

    @Inject
    lateinit var input: InputCoordinator

    @Inject
    lateinit var background: BackgroundCoordinator

    @Inject
    lateinit var appearanceStore: AppearanceStore

    @Inject
    lateinit var incoming: IncomingPackages

    @Inject
    lateinit var updates: UpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A restored activity has already handled the intent it was started with.
        if (savedInstanceState == null) {
            incoming.offer(intent)
            updates.checkAtStartup()
        }
        setContent {
            val appearance by appearanceStore.appearance.collectAsStateWithLifecycle()
            ModuForgeTheme(appearance) {
                ModuForgeApp(consent, input, incoming, updates, background.wantsNotifications)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.offer(intent)
    }
}
