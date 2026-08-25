package tr.izdeniz.signage;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/** Relaunches the signage player after BOOT_COMPLETED with two delayed retries. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        launchNow(context);
        scheduleLaunchRetry(context, 5000L, 13101);
        scheduleLaunchRetry(context, 15000L, 13102);
    }

    private static Intent playerIntent(Context context) {
        return new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    private static void launchNow(Context context) {
        try {
            context.startActivity(playerIntent(context));
        } catch (Exception ignored) {
        }
    }

    static void scheduleLaunchRetry(Context context, long delayMs, int requestCode) {
        try {
            AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarm == null) return;
            PendingIntent pending = PendingIntent.getActivity(
                context,
                requestCode,
                playerIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            long at = SystemClock.elapsedRealtime() + Math.max(1000L, delayMs);
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending);
        } catch (Exception ignored) {
        }
    }
}
