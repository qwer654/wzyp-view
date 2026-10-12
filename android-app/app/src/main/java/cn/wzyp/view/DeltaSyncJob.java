package cn.wzyp.view;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Background sync is scheduled by Android JobScheduler, not an app-resident timer. */
public final class DeltaSyncJob extends JobService {
    private ExecutorService executor;
    private volatile boolean stopped;

    @Override public boolean onStartJob(JobParameters params) {
        stopped=false;
        executor=Executors.newSingleThreadExecutor();
        executor.execute(()->{
            try {
                OfflineStore store=new OfflineStore(getApplicationContext());
                DeltaSyncEngine.sync(getApplicationContext(),store,null);
            }catch(Exception e) {
                android.util.Log.w("WzypDeltaSync","Periodic sync did not complete: "+
                    e.getClass().getSimpleName()); // never log secret URLs or contents
            }finally{
                if(!stopped)jobFinished(params,false);
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        stopped=true;
        if(executor!=null)executor.shutdownNow();
        return false; // periodic schedule remains; don't pile up immediate retries
    }
}
