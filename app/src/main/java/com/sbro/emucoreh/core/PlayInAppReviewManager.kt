package com.sbro.emucoreh.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.google.android.play.core.review.ReviewManagerFactory
import java.util.concurrent.atomic.AtomicBoolean

object PlayInAppReviewManager {
    enum class Result {
        COMPLETED,
        RETRYABLE_FAILURE,
        ACTIVITY_NOT_READY
    }

    private const val TAG = "PlayInAppReview"
    private const val PLAY_STORE_PACKAGE = "com.android.vending"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun canRequest(context: Context): Boolean {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return false

        val installerPackage = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager
                    .getInstallSourceInfo(context.packageName)
                    .installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()

        return installerPackage == PLAY_STORE_PACKAGE
    }

    fun request(activity: ComponentActivity, onComplete: (Result) -> Unit) {
        val completionDelivered = AtomicBoolean(false)
        fun complete(result: Result) {
            if (!completionDelivered.compareAndSet(false, true)) return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                onComplete(result)
            } else {
                mainHandler.post { onComplete(result) }
            }
        }

        if (!activity.isReadyForReview()) {
            complete(Result.ACTIVITY_NOT_READY)
            return
        }

        val reviewManager = runCatching {
            ReviewManagerFactory.create(activity.applicationContext)
        }.getOrElse { error ->
            Log.w(TAG, "Unable to create ReviewManager", error)
            complete(Result.RETRYABLE_FAILURE)
            return
        }
        val requestTask = runCatching { reviewManager.requestReviewFlow() }.getOrElse { error ->
            Log.w(TAG, "Unable to request review flow", error)
            complete(Result.RETRYABLE_FAILURE)
            return
        }

        requestTask.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Review flow request failed", task.exception)
                complete(Result.RETRYABLE_FAILURE)
                return@addOnCompleteListener
            }
            if (!activity.isReadyForReview()) {
                complete(Result.ACTIVITY_NOT_READY)
                return@addOnCompleteListener
            }

            val reviewInfo = runCatching { task.result }.getOrElse { error ->
                Log.w(TAG, "ReviewInfo was unavailable", error)
                complete(Result.RETRYABLE_FAILURE)
                return@addOnCompleteListener
            }
            val launchTask = runCatching {
                reviewManager.launchReviewFlow(activity, reviewInfo)
            }.getOrElse { error ->
                Log.w(TAG, "Unable to launch review flow", error)
                complete(Result.RETRYABLE_FAILURE)
                return@addOnCompleteListener
            }
            launchTask.addOnCompleteListener { completedTask ->
                if (completedTask.isSuccessful) {
                    complete(Result.COMPLETED)
                } else {
                    Log.w(TAG, "Review flow launch failed", completedTask.exception)
                    complete(Result.RETRYABLE_FAILURE)
                }
            }
        }
    }

    private fun ComponentActivity.isReadyForReview(): Boolean =
        !isFinishing &&
            !isDestroyed &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
}
