package com.portal.wake.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.portal.commons.DebugLog
import com.portal.wake.mic.MicArbiter
import com.portal.wake.system.Falcon
import com.portal.wake.wake.Consumer
import com.portal.wake.wake.WakeContract
import com.portal.wake.wake.WakeTarget

/**
 * The handoff for [WakeService]: stands down and wakes a fired wake target's [Consumer], decided at discovery —
 * a wake plugin via [WakeContract.ACTION_WAKE], or the native Alexa client via its LISTEN intent. A target with
 * no consumer is only logged. Main thread only.
 */
internal class WakeHandoff(private val context: Context, private val arbiter: MicArbiter) {

    /** Hand off a fired wake [target] to its consumer. */
    fun handOff(target: WakeTarget) {
        val id = target.word.id
        when (val consumer = target.consumer) {
            is Consumer.Plugin -> standDownThenWake("$id → ${consumer.packageName}") {
                context.sendBroadcast(
                    Intent(WakeContract.ACTION_WAKE)
                        .setComponent(ComponentName(consumer.packageName, consumer.className))
                        .putExtra(WakeContract.EXTRA_WAKE_ID, id)
                        .addFlags(HANDOFF_FLAGS),
                )
            }

            Consumer.NativeAlexa -> standDownThenWake("$id → falcon LISTEN") {
                context.sendBroadcast(
                    Intent(Falcon.LISTEN_ACTION)
                        .setPackage(Falcon.PACKAGE)
                        .addFlags(HANDOFF_FLAGS),
                )
            }

            null -> DebugLog.log("WAKE '$id' (no consumer installed)")
        }
    }

    /**
     * Stand down so the consumer can take the mic (via the arbiter), wake the consumer ([send]), and let the
     * arbiter reclaim by detection. Order is deliberate: the mic must be **free before the consumer starts**,
     * so [MicArbiter.standDown] pauses capture before we broadcast.
     */
    private inline fun standDownThenWake(what: String, send: () -> Unit) {
        arbiter.standDown(what)
        runCatching { send() }.onFailure { DebugLog.log("handoff trigger failed: ${it.message}") }
    }

    private companion object {
        // Handoff broadcast flags. INCLUDE_STOPPED_PACKAGES so a force-stopped consumer still receives it;
        // RECEIVER_FOREGROUND so it goes through Android's foreground broadcast queue (the background queue
        // adds noticeable latency to a cold consumer's "hey jarvis" → assistant-ready path).
        const val HANDOFF_FLAGS =
            Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND
    }
}
