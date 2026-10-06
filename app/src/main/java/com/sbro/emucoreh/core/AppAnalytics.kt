// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.sbro.emucoreh.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralized, best-effort product analytics.
 *
 * Event methods intentionally accept only bounded technical values. Never add game titles,
 * serials, file paths, account identifiers, or other user-provided strings here.
 */
object AppAnalytics {
    private const val TAG = "AppAnalytics"

    private val ready = AtomicBoolean(false)

    @Volatile
    private var firebaseAnalytics: FirebaseAnalytics? = null

    fun initialize(context: Context) {
        if (ready.get()) return

        runCatching {
            val analytics = FirebaseAnalytics.getInstance(context.applicationContext)
            analytics.setConsent(
                mapOf(
                    FirebaseAnalytics.ConsentType.ANALYTICS_STORAGE to
                        FirebaseAnalytics.ConsentStatus.GRANTED,
                    FirebaseAnalytics.ConsentType.AD_STORAGE to
                        FirebaseAnalytics.ConsentStatus.DENIED,
                    FirebaseAnalytics.ConsentType.AD_USER_DATA to
                        FirebaseAnalytics.ConsentStatus.DENIED,
                    FirebaseAnalytics.ConsentType.AD_PERSONALIZATION to
                        FirebaseAnalytics.ConsentStatus.DENIED
                )
            )
            analytics.setAnalyticsCollectionEnabled(true)
            analytics.setUserProperty(
                "build_type",
                if (BuildConfig.DEBUG) "debug" else "release"
            )
            firebaseAnalytics = analytics
            ready.set(true)
        }.onFailure { error ->
            Log.w(TAG, "Firebase Analytics initialization failed", error)
        }
    }

    fun logOnboardingCompleted(performanceProfile: Int) {
        logEvent("onboarding_complete") {
            putString("performance_profile", AnalyticsDimensions.performanceProfile(performanceProfile))
        }
    }

    private inline fun logEvent(name: String, buildParams: Bundle.() -> Unit) {
        if (!ready.get()) return
        val analytics = firebaseAnalytics ?: return
        runCatching {
            analytics.logEvent(name, Bundle().apply(buildParams))
        }.onFailure { error ->
            Log.w(TAG, "Failed to log analytics event: $name", error)
        }
    }
}

internal object AnalyticsDimensions {
    fun performanceProfile(value: Int): String = when (PerformanceProfiles.normalize(value)) {
        PerformanceProfiles.FAST -> "fast"
        else -> "safe"
    }
}
