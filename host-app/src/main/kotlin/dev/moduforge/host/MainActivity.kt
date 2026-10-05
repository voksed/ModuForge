package dev.moduforge.host

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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val appearance by appearanceStore.appearance.collectAsStateWithLifecycle()
            ModuForgeTheme(appearance) {
                ModuForgeApp(consent, input, background.wantsNotifications)
            }
        }
    }
}
