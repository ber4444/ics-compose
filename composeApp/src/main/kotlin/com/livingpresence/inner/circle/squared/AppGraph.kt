package com.livingpresence.inner.circle.squared

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlin.reflect.KClass

@DependencyGraph(AppScope::class)
interface AppGraph : ViewModelGraph {
    val httpClient: HttpClient

    @SingleIn(AppScope::class)
    @Provides
    fun provideHttpClient(): HttpClient = HttpClient(Android)
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<MetroViewModelFactory>())
class AppMetroViewModelFactory(
    override val viewModelProviders: Map<KClass<out ViewModel>, Provider<ViewModel>>,
    override val assistedFactoryProviders: Map<KClass<out ViewModel>, Provider<ViewModelAssistedFactory>>,
    override val manualAssistedFactoryProviders: Map<KClass<out ManualViewModelAssistedFactory>, Provider<ManualViewModelAssistedFactory>>,
) : MetroViewModelFactory()

