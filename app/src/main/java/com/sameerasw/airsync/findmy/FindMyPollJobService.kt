package com.sameerasw.airsync.findmy

import android.app.job.JobParameters
import android.app.job.JobService
import kotlin.concurrent.thread

/**
 * Every 15 minutes (JobScheduler, needs network): if the relay stream isn't running, poll the
 * relay once (`poll=1&since=5m`) so a Find My command still gets through.
 */
class FindMyPollJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        if (FindMyRelayClient.streaming.value) return false
        thread(name = "HIBFindMyPoll") {
            val ok = FindMyRelayClient.pollOnce(applicationContext)
            jobFinished(params, !ok)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
