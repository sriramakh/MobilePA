package dev.mobilepa;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;

public final class Reminders extends BroadcastReceiver {
  static final String FIRE = "dev.mobilepa.REMIND";

  static void schedule(Context c, long id, long due) {
    Intent intent =
        new Intent(c, Reminders.class)
            .setAction(FIRE)
            .setData(android.net.Uri.parse("mobilepa:reminder/" + id))
            .putExtra("id", id);
    PendingIntent pending =
        PendingIntent.getBroadcast(
            c, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    AlarmManager alarms = c.getSystemService(AlarmManager.class);
    if (due == 0) alarms.cancel(pending);
    else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending);
  }

  static void restore(Context c) {
    for (Store.Row r : Store.get(c).rows("SELECT id,due FROM memory WHERE due>0 AND alerted=0"))
      schedule(c, r.id(), r.n("due"));
  }

  @Override
  public void onReceive(Context c, Intent intent) {
    if (!FIRE.equals(intent.getAction())) {
      restore(c);
      DeliveryJob.enqueue(c);
      return;
    }
    long id = intent.getLongExtra("id", -1);
    Store store = Store.get(c);
    Store.Row r = store.byId("memory", id);
    if (r == null || r.n("due") == 0 || r.n("alerted") == 1) return;
    if (r.n("due") > System.currentTimeMillis()) {
      schedule(c, id, r.n("due"));
      return;
    }
    if (Build.VERSION.SDK_INT >= 33
        && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return;
    NotificationManager manager = c.getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel("reminders", "Reminders", NotificationManager.IMPORTANCE_HIGH));
    Intent open = new Intent(c, MainActivity.class).putExtra("memory", id);
    PendingIntent view =
        PendingIntent.getActivity(
            c, (int) id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    manager.notify(
        (int) id,
        new Notification.Builder(c, "reminders")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("MobilePA reminder")
            .setContentText(r.s("body"))
            .setStyle(new Notification.BigTextStyle().bigText(r.s("body")))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(view)
            .build());
    store.edit("memory", id, Store.values("alerted", 1));
  }
}
