package dev.mobilepa;

import android.app.job.*;
import android.content.*;
import android.util.Base64;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

public final class DeliveryJob extends JobService {
  static final String SCOPE = "https://www.googleapis.com/auth/gmail.send";
  static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
  private volatile JobParameters active;

  static AuthorizationRequest request() {
    return AuthorizationRequest.builder()
        .setRequestedScopes(Collections.singletonList(new Scope(SCOPE)))
        .build();
  }

  static void enqueue(Context c) {
    if (Store.get(c)
        .rows("SELECT id FROM mail WHERE state IN ('Queued','Sending') LIMIT 1")
        .isEmpty()) return;
    JobInfo job =
        new JobInfo.Builder(7201, new ComponentName(c, DeliveryJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build();
    c.getSystemService(JobScheduler.class).enqueue(job, new JobWorkItem(new Intent()));
  }

  @Override
  public boolean onStartJob(JobParameters p) {
    active = p;
    WORKER.execute(
        () -> {
          boolean retry = false;
          try {
            JobWorkItem work;
            while (active == p && (work = p.dequeueWork()) != null) {
              if (!deliver(p)) {
                retry = true;
                break;
              }
              if (active == p) p.completeWork(work);
            }
          } catch (Exception e) {
            retry = true;
          }
          boolean again = retry;
          getMainExecutorCompat(
              () -> {
                if (active == p) {
                  active = null;
                  jobFinished(p, again);
                }
              });
        });
    return true;
  }

  private void getMainExecutorCompat(Runnable r) {
    new android.os.Handler(getMainLooper()).post(r);
  }

  @Override
  public boolean onStopJob(JobParameters p) {
    if (active == p) active = null;
    return true;
  }

  private boolean deliver(JobParameters p) throws Exception {
    Store store = Store.get(this);
    store
        .getWritableDatabase()
        .execSQL(
            "UPDATE mail SET state='Review',detail='Delivery was interrupted. Check Sent mail"
                + " before retrying.' WHERE state='Sending'");
    SharedPreferences prefs = getSharedPreferences("mail", MODE_PRIVATE);
    while (active == p && prefs.getBoolean("connected", false)) {
      List<Store.Row> waiting =
          store.rows("SELECT * FROM mail WHERE state='Queued' ORDER BY id LIMIT 1");
      if (waiting.isEmpty()) break;
      boolean smtp = "SMTP".equals(prefs.getString("method", "Gmail"));
      String token = "";
      if (!smtp) {
        AuthorizationResult authorization =
            Tasks.await(
                Identity.getAuthorizationClient(this).authorize(request()), 30, TimeUnit.SECONDS);
        if (authorization.hasResolution() || authorization.getAccessToken() == null) {
          prefs.edit().putBoolean("connected", false).apply();
          return true;
        }
        token = authorization.getAccessToken();
      }
      if (active != p) return false;
      Store.Row row = waiting.get(0);
      store.edit("mail", row.id(), Store.values("state", "Sending", "detail", ""));
      int status;
      String subject = Language.mailSubject(row.n("rule_id"), row.s("kind"), row.s("sender"));
      String message =
          Language.mailBody(row.s("body"), row.s("sender"), row.n("received"), row.s("phrase"));
      try {
        status =
            smtp
                ? SmtpAccount.send(prefs, row.s("destination"), subject, message)
                : send(row, token, subject, message);
      } catch (SmtpAccount.ConnectionFailure e) {
        store.edit(
            "mail",
            row.id(),
            Store.values(
                "state",
                "Queued",
                "detail",
                "Connection failed. Check the SMTP host, port, and TLS certificate. Delivery will"
                    + " retry."));
        return false;
      } catch (Exception e) {
        store.edit(
            "mail",
            row.id(),
            Store.values(
                "state",
                "Review",
                "detail",
                "Delivery could not be confirmed. Check Sent mail before retrying."));
        continue;
      }
      if (status >= 200 && status < 300)
        store.edit(
            "mail",
            row.id(),
            Store.values("state", "Sent", "detail", "Accepted by your mail provider"));
      else if (status == 429 || status >= 500 || status == 401) {
        store.edit(
            "mail",
            row.id(),
            Store.values(
                "state",
                "Queued",
                "detail",
                status == 401
                    ? "Reconnect your sending account."
                    : "Provider unavailable. Delivery will retry."));
        if (status == 401)
          getSharedPreferences("mail", MODE_PRIVATE).edit().putBoolean("connected", false).apply();
        return status == 401;
      } else
        store.edit(
            "mail",
            row.id(),
            Store.values(
                "state",
                "Review",
                "detail",
                "The provider declined the message. Check the account and recipient."));
    }
    return active == p;
  }

  private int send(Store.Row r, String token, String subject, String message) throws Exception {
    String safeSubject =
        Base64.encodeToString(subject.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    String body = Base64.encodeToString(message.getBytes(StandardCharsets.UTF_8), Base64.CRLF);
    String mime =
        "To: "
            + r.s("destination")
            + "\r\nSubject: =?UTF-8?B?"
            + safeSubject
            + "?=\r\n"
            + "MIME-Version: 1.0\r\n"
            + "Content-Type: text/plain; charset=UTF-8\r\n"
            + "Content-Transfer-Encoding: base64\r\n\r\n"
            + body;
    String raw =
        Base64.encodeToString(
            mime.getBytes(StandardCharsets.UTF_8),
            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    HttpURLConnection con =
        (HttpURLConnection)
            new URL("https://gmail.googleapis.com/gmail/v1/users/me/messages/send")
                .openConnection();
    try {
      con.setConnectTimeout(15000);
      con.setReadTimeout(20000);
      con.setInstanceFollowRedirects(false);
      con.setRequestMethod("POST");
      con.setRequestProperty("Authorization", "Bearer " + token);
      con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
      con.setDoOutput(true);
      byte[] payload = new JSONObject().put("raw", raw).toString().getBytes(StandardCharsets.UTF_8);
      con.setFixedLengthStreamingMode(payload.length);
      try (OutputStream out = con.getOutputStream()) {
        out.write(payload);
      }
      return con.getResponseCode();
    } finally {
      con.disconnect();
    }
  }
}
