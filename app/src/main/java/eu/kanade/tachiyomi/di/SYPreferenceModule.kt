package eu.kanade.tachiyomi.di

import android.app.Application
import exh.pref.DelegateSourcePreferences
import exh.source.ExhPreferences
import uy.kohesive.injekt.api.InjektRegistrar

class SYPreferenceModule(val application: Application) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory {
            DelegateSourcePreferences(
                preferenceStore = get(),
            )
        }

        addSingletonFactory {
            ExhPreferences(get())
        }
    }
}
