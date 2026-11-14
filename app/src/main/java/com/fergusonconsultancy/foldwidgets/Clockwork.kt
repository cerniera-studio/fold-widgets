package com.fergusonconsultancy.foldwidgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract

/**
 * Keeps the agenda and weather current without a running service: a job when the calendar
 * changes, a job every half hour for the weather, and an alarm for when the agenda next changes
 * by itself (an event ending, or midnight turning today into tomorrow).
 */
class Clockwork : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Widgets.inBackground(this) {
            // Every widget: the agenda and month move on, and the glass's light follows the hour
            Widgets.renderAll(c)
            if (i.action != ACTION_TICK) RefreshJob.schedule(c)
            arm(c)
        }
    }

    companion object {
        const val ACTION_TICK = "com.fergusonconsultancy.foldwidgets.TICK"

        /**
         * Sets the alarm for the next change: the agenda's (an event ending, midnight) or the top of
         * the hour, when the glass's light moves on. Inexact, so it needs no special permission.
         */
        fun arm(c: Context) {
            if (Kind.entries.all { Widgets.ids(c, it).isEmpty() }) return
            val hour = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.HOUR_OF_DAY, 1); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 5)
            }.timeInMillis
            val agenda = if (Widgets.ids(c, Kind.AGENDA).isEmpty()) Long.MAX_VALUE else runCatching { Agenda.nextChange(c) }.getOrDefault(hour)
            val at = minOf(hour, agenda)
            val pi = PendingIntent.getBroadcast(c, 7, Intent(c, Clockwork::class.java).setAction(ACTION_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            c.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC, at, 4 * 60_000L, pi)
            RefreshJob.watchCalendar(c)
        }
    }
}

class RefreshJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                when (params.jobId) {
                    WEATHER, WEATHER_NOW -> { runCatching { Weather.refresh(this) }; Widgets.renderAll(this, Kind.WEATHER) }
                    CALENDAR -> { Widgets.renderAll(this, Kind.AGENDA); Widgets.renderAll(this, Kind.MONTH); Clockwork.arm(this) }
                }
            } finally { jobFinished(params, false) }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        const val WEATHER = 1
        const val CALENDAR = 2
        const val WEATHER_NOW = 3
        private const val MAIL_RETIRED = 4

        /** The weather, about every half hour, only with a network. */
        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            // The retired mail widget's inbox job, if one is still queued from before (5 Nov 2026)
            js.cancel(MAIL_RETIRED); Prefs.retireMail(c)
            if (Widgets.ids(c, Kind.WEATHER).isEmpty()) { js.cancel(WEATHER); return }
            if (js.getPendingJob(WEATHER) != null) return
            js.schedule(JobInfo.Builder(WEATHER, ComponentName(c, RefreshJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(30 * 60_000L)
                .setPersisted(false)
                .build())
            // And once now, so a new widget has something to show
            js.schedule(JobInfo.Builder(WEATHER_NOW, ComponentName(c, RefreshJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
        }

        /** Fires when any calendar changes; content jobs fire once, so it is set again each time. */
        fun watchCalendar(c: Context) {
            if (!Agenda.allowed(c)) return
            c.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(CALENDAR, ComponentName(c, RefreshJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(2_000L)
                .build())
        }
    }
}
