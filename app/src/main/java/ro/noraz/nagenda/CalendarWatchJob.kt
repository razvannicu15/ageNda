package ro.noraz.nagenda

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract

/** Wakes up when anything in the calendar changes and refreshes the widget. */
class CalendarWatchJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        AgendaWidget.updateAll(applicationContext) // also re-arms this job
        return false
    }

    override fun onStopJob(params: JobParameters) = false

    companion object {
        private const val ID = 4711

        fun schedule(c: Context) {
            val info = JobInfo.Builder(ID, ComponentName(c, CalendarWatchJob::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        CalendarContract.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                    ),
                )
                .setTriggerContentUpdateDelay(1_500)
                .setTriggerContentMaxDelay(8_000)
                .build()
            try {
                c.getSystemService(JobScheduler::class.java).schedule(info)
            } catch (_: Exception) { }
        }

        fun cancel(c: Context) = c.getSystemService(JobScheduler::class.java).cancel(ID)
    }
}
