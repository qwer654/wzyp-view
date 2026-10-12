package cn.wzyp.view;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opt-in, metered periodic refresh of public catalogs already known locally.
 * Android may defer periodic jobs due to battery/network constraints.
 */
public final class PublicRefreshJob extends JobService {
    public static final int JOB_ID=301006;
    private static final long PERIOD=24L*60*60*1000;
    private ExecutorService work;
    private volatile boolean stopped;

    static void schedule(Context context) {
        JobScheduler jobs=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(jobs==null)return;
        SharedPreferences prefs=context.getSharedPreferences("wzyp_public_sync_v6",0);
        if(!prefs.getBoolean("auto",false)){
            jobs.cancel(JOB_ID);
            return;
        }
        if(jobs.getPendingJob(JOB_ID)!=null)return;
        jobs.schedule(new JobInfo.Builder(JOB_ID,new ComponentName(context,PublicRefreshJob.class))
            .setPeriodic(PERIOD)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setPersisted(true)
            .build());
    }

    @Override public boolean onStartJob(JobParameters params) {
        stopped=false;
        work=Executors.newSingleThreadExecutor();
        work.execute(()->{
            try{
                OfflineStore store=new OfflineStore(getApplicationContext());
                // No background full-site harvesting: at most four known shops in one job.
                new PublicStorefrontUpdater(getApplicationContext(),store)
                    .refreshBatch(4,null);
            }catch(Exception e){
                android.util.Log.w("WzypPublicSync","Shop refresh deferred: "+
                    e.getClass().getSimpleName());
            }finally{
                if(!stopped)jobFinished(params,false);
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        stopped=true;
        if(work!=null)work.shutdownNow();
        return false;
    }
}
