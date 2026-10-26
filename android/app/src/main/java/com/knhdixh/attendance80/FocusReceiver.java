package com.knhdixh.attendance80;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Notification and widget buttons, the end-of-phase alarm, widget refreshes, and re-arming after a reboot. */
public class FocusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        switch (a) {
            case FocusController.ACTION_TOGGLE: FocusController.toggle(c); break;
            case FocusController.ACTION_SKIP: FocusController.skip(c); break;
            case FocusController.ACTION_STOP: FocusController.stop(c); break;
            case FocusController.ACTION_PHASE_END: FocusController.phaseEnded(c); break;
            case FocusWidget.ACTION_REFRESH: FocusWidget.updateAll(c); break;
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED: {
                FocusTimer t = new FocusStore(c).timer();
                if (t.isDue(System.currentTimeMillis())) FocusController.phaseEnded(c);
                else FocusController.apply(c, t, null, true);
                break;
            }
            default: break;
        }
    }
}
