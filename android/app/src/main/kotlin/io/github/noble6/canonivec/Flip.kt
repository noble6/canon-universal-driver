// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.noble6.canonivec

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Manual 2-sided printing: after the fronts, ask the user to put the stack
 * back and wait for Continue (notification button or the flip screen).
 */
object FlipNotifier {
    private const val CHANNEL = "flip"
    const val EXTRA_KEY = "key"
    const val EXTRA_GO = "go"
    private val waits = ConcurrentHashMap<String, Pair<CountDownLatch, BooleanArray>>()

    const val INSTRUCTION = "When the front sides have finished printing, put the whole stack back in the " +
        "rear tray with the blank side facing you and the TOP edge going in first. Then tap Continue."

    /** Blocks until the user continues (true) or cancels / an hour passes (false). */
    fun waitForFlip(ctx: Context, key: String, title: String): Boolean {
        val latch = CountDownLatch(1)
        val result = BooleanArray(1)
        waits[key] = latch to result
        show(ctx, key, title)
        val ok = latch.await(1, TimeUnit.HOURS) && result[0]
        waits.remove(key)
        nm(ctx).cancel(key.hashCode())
        return ok
    }

    fun answer(ctx: Context, key: String, go: Boolean) {
        waits[key]?.let { (l, r) -> r[0] = go; l.countDown() }
        nm(ctx).cancel(key.hashCode())
    }

    fun cancel(key: String) {
        waits[key]?.let { (l, r) -> r[0] = false; l.countDown() }
    }

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun show(ctx: Context, key: String, title: String) {
        val nm = nm(ctx)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "2-sided printing", NotificationManager.IMPORTANCE_HIGH))
        fun action(go: Boolean) = PendingIntent.getBroadcast(ctx, key.hashCode() * 2 + if (go) 1 else 0,
            Intent(ctx, FlipReceiver::class.java).putExtra(EXTRA_KEY, key).putExtra(EXTRA_GO, go),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(ctx, key.hashCode(),
            Intent(ctx, FlipActivity::class.java).putExtra(EXTRA_KEY, key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_print)
            .setContentTitle("Turn the paper over: $title")
            .setContentText("Blank side facing you, TOP edge in first, then Continue")
            .setStyle(Notification.BigTextStyle().bigText(INSTRUCTION))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Continue", action(true)).build())
            .addAction(Notification.Action.Builder(null, "Cancel", action(false)).build())
            .build()
        nm.notify(key.hashCode(), n)
    }
}

class FlipReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        FlipNotifier.answer(ctx, i.getStringExtra(FlipNotifier.EXTRA_KEY) ?: return, i.getBooleanExtra(FlipNotifier.EXTRA_GO, false))
    }
}

/** Full instructions with a drawing, like canon-ivec-flip on Linux. */
class FlipActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(FlipNotifier.EXTRA_KEY)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply {
            text = FlipNotifier.INSTRUCTION
            textSize = 18f
        })
        root.addView(FlipDrawing(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(this).apply { gravity = Gravity.END }
        buttons.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { key?.let { FlipNotifier.answer(this@FlipActivity, it, false) }; finish() }
        })
        buttons.addView(Button(this).apply {
            text = "Continue"
            setOnClickListener { key?.let { FlipNotifier.answer(this@FlipActivity, it, true) }; finish() }
        })
        root.addView(buttons)
        setContentView(root)
    }

    /** The printer's rear tray with the stack going in blank side out, TOP edge down. */
    private class FlipDrawing(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dark = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val s = minOf(w, h * 1.2f) / 10f
            val cx = w / 2
            val fg = if (dark) Color.LTGRAY else Color.DKGRAY
            // printer body
            p.style = Paint.Style.FILL
            p.color = if (dark) Color.rgb(70, 70, 70) else Color.rgb(200, 200, 200)
            c.drawRoundRect(RectF(cx - 4 * s, h * 0.62f, cx + 4 * s, h * 0.9f), s / 2, s / 2, p)
            // sheet going into the rear tray: blank side facing the viewer
            p.color = Color.WHITE
            val sheet = RectF(cx - 2.2f * s, h * 0.12f, cx + 2.2f * s, h * 0.7f)
            c.drawRect(sheet, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = s / 12
            p.color = fg
            c.drawRect(sheet, p)
            p.style = Paint.Style.FILL
            p.textAlign = Paint.Align.CENTER
            p.textSize = s * 0.55f
            c.drawText("blank side", cx, h * 0.4f, p)
            c.drawText("TOP edge", cx, h * 0.66f, p)
            // arrow: into the printer
            val path = Path().apply {
                moveTo(cx + 3.2f * s, h * 0.3f); lineTo(cx + 3.2f * s, h * 0.6f)
                moveTo(cx + 2.8f * s, h * 0.54f); lineTo(cx + 3.2f * s, h * 0.6f); lineTo(cx + 3.6f * s, h * 0.54f)
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = s / 8
            c.drawPath(path, p)
        }
    }
}
