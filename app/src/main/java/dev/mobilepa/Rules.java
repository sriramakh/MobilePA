package dev.mobilepa;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.Telephony;
import android.util.Patterns;
import android.widget.*;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.ApiException;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class Rules {
  final MainActivity a;
  final Store db;
  private long pendingEnable, editing;
  private String editingKind = Language.PHRASE_KIND;
  private TextView account;
  private LinearLayout history;
  private String historyVersion = "";
  private EditText sender, phrases, destination;
  private AlertDialog editor;

  Rules(MainActivity a) {
    this.a = a;
    db = a.db;
  }

  private SharedPreferences prefs() {
    return a.getSharedPreferences("mail", Context.MODE_PRIVATE);
  }

  void page() {
    a.layout(
        "Rules",
        "Forwarding",
        "Email new carrier SMS that match a rule, including verification codes.");
    account = a.action("", this::connect, false);
    a.wide(a.page, account);
    a.gap(a.page, 8);
    a.wide(a.page, a.action("＋  New rule", () -> editor(0, "", ""), true));
    a.section("Your rules");
    List<Store.Row> rows =
        db.rows("SELECT * FROM rule ORDER BY CASE kind WHEN 'otp' THEN 0 ELSE 1 END, id DESC");
    if (rows.isEmpty()) a.hint(a.page, "Choose a sender, a phrase, and your email address.");
    for (Store.Row r : rows) {
      boolean enabled = r.n("enabled") == 1 && a.allowed(Manifest.permission.RECEIVE_SMS);
      String target = r.s("destination").isEmpty() ? "Add your email" : r.s("destination");
      a.card(
          a.page,
          r.s("phrases"),
          (enabled ? "Active" : "Paused")
              + " · "
              + (r.s("sender").isEmpty() ? "Any sender" : r.s("sender"))
              + " → "
              + target,
          "⇄",
          () -> manage(r.id()));
    }
    a.section("Delivery activity");
    history = a.column();
    a.wide(a.page, history);
    historyVersion = "";
    refreshStatus();
  }

  void refreshStatus() {
    if (!a.tab.equals("Rules") || history == null) return;
    boolean connected = prefs().getBoolean("connected", false);
    account.setText(
        connected
            ? prefs().getString("method", "Gmail") + " · Account options"
            : "Connect sending account");
    List<Store.Row> rows = db.rows("SELECT * FROM mail ORDER BY id DESC LIMIT 50");
    StringBuilder version = new StringBuilder("v");
    for (Store.Row r : rows) version.append(r.id()).append(r.s("state")).append(r.s("detail"));
    if (version.toString().equals(historyVersion)) return;
    historyVersion = version.toString();
    history.removeAllViews();
    if (rows.isEmpty()) a.hint(history, "Matching messages and test emails will appear here.");
    for (Store.Row r : rows)
      a.card(
          history,
          r.s("state") + " · " + r.s("sender"),
          r.s("phrase") + " · " + a.when(r.n("received")),
          "↗",
          () -> delivery(r.id()));
  }

  private void delivery(long id) {
    Store.Row r = db.byId("mail", id);
    if (r == null) return;
    AlertDialog.Builder b =
        new AlertDialog.Builder(a)
            .setTitle(r.s("state") + " · " + r.s("sender"))
            .setMessage(
                "To "
                    + r.s("destination")
                    + "\nMatched “"
                    + r.s("phrase")
                    + "”\n"
                    + a.when(r.n("received"))
                    + "\n\n"
                    + r.s("body")
                    + "\n\n"
                    + r.s("detail"))
            .setNegativeButton("Close", null);
    if (r.n("rule_id") > 0) b.setNeutralButton("View rule", (d, w) -> manage(r.n("rule_id")));
    if ("Review".equals(r.s("state")))
      b.setPositiveButton(
          "Retry…",
          (d, w) ->
              new AlertDialog.Builder(a)
                  .setTitle("Send this email again?")
                  .setMessage(
                      "Check your Sent mail first. An interrupted delivery may already have reached"
                          + " Gmail.")
                  .setNegativeButton("Cancel", null)
                  .setPositiveButton(
                      "Retry",
                      (d2, w2) -> {
                        db.edit("mail", id, Store.values("state", "Queued", "detail", ""));
                        DeliveryJob.enqueue(a);
                        refreshStatus();
                      })
                  .show());
    b.show();
  }

  private void connect() {
    if (prefs().getBoolean("connected", false)) {
      new AlertDialog.Builder(a)
          .setTitle("Sending account")
          .setItems(
              new String[] {"Send test email", "Change sending account", "Disconnect account"},
              (d, w) -> {
                if (w == 0) testEmail();
                else if (w == 1) chooseMethod();
                else {
                  prefs().edit().putBoolean("connected", false).remove("smtp").remove("iv").apply();
                  refreshStatus();
                }
              })
          .show();
    } else chooseMethod();
  }

  private void chooseMethod() {
    new AlertDialog.Builder(a)
        .setTitle("Connect sending account")
        .setItems(
            new String[] {
              "SMTP with app password (including Gmail)",
              "Gmail with Google sign-in (requires OAuth setup)"
            },
            (d, w) -> {
              if (w == 0) smtpForm();
              else authorize();
            })
        .show();
  }

  private void smtpForm() {
    LinearLayout box = a.column();
    a.fieldLabel(box, "Sending email address");
    EditText email = a.field("", "you@example.com", false);
    a.wide(box, email);
    a.fieldLabel(box, "Outgoing server");
    EditText host = a.field("smtp.gmail.com", "smtp.example.com", false);
    a.wide(box, host);
    a.fieldLabel(box, "Implicit TLS port");
    EditText port = a.field("465", "465", false);
    port.setInputType(2);
    a.wide(box, port);
    a.fieldLabel(box, "Mail provider’s app password");
    EditText secret = a.field("", "App password", false);
    secret.setInputType(129);
    secret.setSaveEnabled(false);
    a.wide(box, secret);
    a.hint(
        box,
        "For Gmail, generate an app password in your Google Account with 2-Step Verification"
            + " enabled. MobilePA encrypts your sending credentials on this phone.");
    AlertDialog[] dialog = {null};
    dialog[0] =
        a.form(
            "SMTP account",
            box,
            "Save account",
            () -> {
              if (!validEmail(email)) return;
              String server = host.getText().toString().trim(),
                  password = secret.getText().toString();
              if (!server.matches("[a-zA-Z0-9.-]+")) {
                host.setError("Enter a server hostname");
                return;
              }
              if (server.equalsIgnoreCase("smtp.gmail.com")) {
                password = password.replaceAll("\\s+", "");
              }
              int number;
              try {
                number = Integer.parseInt(port.getText().toString());
              } catch (NumberFormatException e) {
                number = 0;
              }
              if (number < 1 || number > 65535) {
                port.setError("Enter a valid port");
                return;
              }
              if (password.isEmpty()) {
                secret.setError("Enter an app password");
                return;
              }
              try {
                SmtpAccount.configure(
                    prefs(), email.getText().toString().trim(), server, number, password);
                DeliveryJob.enqueue(a);
                dialog[0].dismiss();
                a.navigate("Rules");
                testEmail();
              } catch (Exception e) {
                a.toast("Could not save the encrypted account.");
              }
            });
  }

  private void authorize() {
    Identity.getAuthorizationClient(a)
        .authorize(DeliveryJob.request())
        .addOnSuccessListener(
            a,
            result -> {
              if (result.hasResolution()) {
                try {
                  a.startIntentSenderForResult(
                      result.getPendingIntent().getIntentSender(), 51, null, 0, 0, 0);
                } catch (IntentSender.SendIntentException e) {
                  gmailUnavailable("Google account selection could not open.");
                }
              } else connected(result);
            })
        .addOnFailureListener(a, this::authError);
  }

  private void authError(Exception e) {
    String code =
        e instanceof ApiException
            ? " Google returned status " + ((ApiException) e).getStatusCode() + "."
            : "";
    gmailUnavailable("Google sign-in could not authorize sending mail." + code);
  }

  void authorized(int result, Intent data) {
    if (data == null) {
      gmailUnavailable("Google sign-in ended before granting access.");
      return;
    }
    try {
      AuthorizationResult authorization =
          Identity.getAuthorizationClient(a).getAuthorizationResultFromIntent(data);
      if (result == Activity.RESULT_OK) connected(authorization);
      else gmailUnavailable("Google sign-in ended before granting access.");
    } catch (ApiException e) {
      authError(e);
    }
  }

  private void gmailUnavailable(String detail) {
    new AlertDialog.Builder(a)
        .setTitle("Gmail connection unavailable")
        .setMessage(
            detail
                + "\n\nGoogle sign-in requires an Android OAuth client registered for this build"
                + " and an enabled Gmail API. If your Gmail account supports app passwords,"
                + " you can connect through SMTP using an app password.")
        .setNegativeButton("Close", null)
        .setPositiveButton("Set up SMTP", (dialog, button) -> smtpForm())
        .show();
  }

  private void connected(AuthorizationResult result) {
    if (result.getAccessToken() == null) {
      gmailUnavailable("Google did not grant permission to send mail.");
      return;
    }
    prefs()
        .edit()
        .putBoolean("connected", true)
        .putString("method", "Gmail")
        .remove("smtp")
        .remove("iv")
        .apply();
    DeliveryJob.enqueue(a);
    a.navigate("Rules");
    testEmail();
  }

  private void rememberDestination(String to) {
    if (to == null || to.isEmpty()) return;
    prefs().edit().putString("destination", to).apply();
    for (Store.Row r : db.rows("SELECT id, destination FROM rule WHERE kind='otp'")) {
      if (r.s("destination").isEmpty())
        db.edit("rule", r.id(), Store.values("destination", to, "enabled", 1));
    }
  }

  void offerOtpPermission() {
    long id = 0;
    String destination = "";
    for (Store.Row r : db.rows("SELECT id, destination FROM rule WHERE kind='otp' AND enabled=1")) {
      if (!r.s("destination").isEmpty()) {
        id = r.id();
        destination = r.s("destination");
      }
    }
    if (id == 0) return;
    if (a.allowed(Manifest.permission.RECEIVE_SMS)) {
      if (!prefs().getBoolean("otp_ready_noted", false)) {
        prefs().edit().putBoolean("otp_ready_noted", true).apply();
        a.toast("Verification codes will be emailed to " + destination);
      }
      return;
    }
    if (prefs().getBoolean("otp_permission_asked", false)) return;
    prefs().edit().putBoolean("otp_permission_asked", true).apply();
    pendingEnable = id;
    a.getWindow()
        .getDecorView()
        .post(
            () -> {
              if (!a.isDestroyed() && !a.allowed(Manifest.permission.RECEIVE_SMS))
                a.requestPermissions(new String[] {Manifest.permission.RECEIVE_SMS}, 32);
            });
  }

  private boolean validEmail(EditText e) {
    String s = e.getText().toString().trim();
    if (!Patterns.EMAIL_ADDRESS.matcher(s).matches() || s.contains("\r") || s.contains("\n")) {
      e.setError("Enter one email address");
      return false;
    }
    return true;
  }

  private void testEmail() {
    LinearLayout box = a.column();
    a.fieldLabel(box, "Send a test to");
    EditText address = a.field(prefs().getString("destination", ""), "you@example.com", false);
    a.wide(box, address);
    AlertDialog[] d = {null};
    d[0] =
        a.form(
            "Test email",
            box,
            "Send test",
            () -> {
              if (!validEmail(address)) return;
              String to = address.getText().toString().trim();
              db.add(
                  "mail",
                  Store.values(
                      "fingerprint",
                      UUID.randomUUID().toString(),
                      "rule_id",
                      0,
                      "sender",
                      "MobilePA",
                      "body",
                      "Your MobilePA email connection is working.",
                      "phrase",
                      "Connection test",
                      "destination",
                      to,
                      "received",
                      System.currentTimeMillis(),
                      "state",
                      "Queued",
                      "kind",
                      Language.PHRASE_KIND));
              rememberDestination(to);
              DeliveryJob.enqueue(a);
              d[0].dismiss();
              a.navigate("Rules");
              offerOtpPermission();
            });
  }

  void editor(long id, String from, String words) {
    editing = id;
    Store.Row r = id == 0 ? null : db.byId("rule", id);
    editingKind = r == null || r.s("kind").isEmpty() ? Language.PHRASE_KIND : r.s("kind");
    boolean otp = Language.OTP_KIND.equals(editingKind);
    LinearLayout box = a.column();
    a.fieldLabel(box, "Sender ID or number");
    sender = a.field(r == null ? from : r.s("sender"), "Blank matches any sender", false);
    a.wide(box, sender);
    if (otp) {
      phrases = null;
      a.hint(
          box,
          "Forwards an SMS when it mentions OTP, one-time password, verification code, passcode,"
              + " security code, or login code, or when a 4–8 digit code appears beside the word"
              + " code or PIN. Matching ignores capitalization.");
    } else {
      a.fieldLabel(box, "Words or phrases");
      phrases =
          a.field(r == null ? words : r.s("phrases"), "Example: bill generated, payment due", true);
      a.wide(box, phrases);
      a.hint(
          box,
          "Separate alternatives with commas. Verification-code messages are handled by the OTP"
              + " rule.");
    }
    a.fieldLabel(box, "Forward to");
    destination =
        a.field(
            r == null ? prefs().getString("destination", "") : r.s("destination"),
            "you@example.com",
            false);
    destination.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
    a.wide(box, destination);
    a.gap(box, 12);
    a.wide(box, a.action("Test a sample", this::sample, false));
    a.gap(box, 8);
    a.wide(box, a.action("Preview recent inbox matches", this::previewPermission, false));
    editor =
        a.form(
            otp ? "OTP forwarding" : id == 0 ? "New forwarding rule" : "Edit forwarding rule",
            box,
            "Save rule",
            () -> {
              String text = otp ? Language.OTP_RULE_LABEL : phrases.getText().toString().trim();
              if (!otp && !Language.hasPhrases(text)) {
                phrases.setError("Enter at least one phrase");
                return;
              }
              if (!validEmail(destination)) return;
              String to = destination.getText().toString().trim();
              ContentValues v =
                  Store.values(
                      "sender",
                      sender.getText().toString().trim(),
                      "phrases",
                      text,
                      "destination",
                      to,
                      "enabled",
                      0,
                      "last_checked",
                      0,
                      "last_result",
                      "");
              long key;
              if (id == 0) key = db.add("rule", v);
              else {
                db.edit("rule", id, v);
                key = id;
              }
              rememberDestination(to);
              editor.dismiss();
              a.navigate("Rules");
              offerOtpPermission();
              new AlertDialog.Builder(a)
                  .setTitle("Enable this rule?")
                  .setMessage(
                      "Forward future matches to "
                          + to
                          + ". "
                          + (prefs().getBoolean("connected", false)
                              ? ""
                              : "Matches will queue on this phone until you connect a sending"
                                  + " account."))
                  .setPositiveButton("Enable", (d, w) -> enable(key))
                  .setNegativeButton("Keep paused", null)
                  .show();
            });
  }

  private void sample() {
    boolean otp = Language.OTP_KIND.equals(editingKind);
    String rulePhrases = otp ? Language.OTP_RULE_LABEL : phrases.getText().toString().trim();
    if (!otp && !Language.hasPhrases(rulePhrases)) {
      phrases.requestFocus();
      phrases.setError("Type a word or phrase before testing, for example: bill generated");
      return;
    }
    LinearLayout box = a.column();
    a.hint(box, "Checking for: " + rulePhrases);
    a.fieldLabel(box, "Sender");
    EditText from = a.field(sender.getText().toString(), "Sender ID", false);
    a.wide(box, from);
    a.fieldLabel(box, "Sample SMS");
    EditText message = a.field("", "Paste the message here", true);
    a.wide(box, message);
    a.gap(box, 12);
    TextView feedback = a.label("", 15, DeskUi.TEAL, true);
    a.wide(box, feedback);
    a.form(
        "Test a sample",
        box,
        "Check match",
        () -> {
          Language.MatchResult result =
              Language.checkMatch(
                  sender.getText().toString(),
                  rulePhrases,
                  from.getText().toString(),
                  message.getText().toString(),
                  SmsReceiver.region(a),
                  editingKind);
          feedback.setText(result.explanation());
        });
  }

  private void previewPermission() {
    if (a.allowed(Manifest.permission.READ_SMS)) preview();
    else a.requestPermissions(new String[] {Manifest.permission.READ_SMS}, 33);
  }

  private void preview() {
    if (editor == null || !editor.isShowing()) return;
    String from = sender.getText().toString();
    String words = phrases == null ? Language.OTP_RULE_LABEL : phrases.getText().toString();
    String kind = editingKind;
    Attachments.IO.execute(
        () -> {
          List<Store.Row> hits = new ArrayList<>();
          long start = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30);
          String region = SmsReceiver.region(a);
          try (Cursor c =
              a.getContentResolver()
                  .query(
                      Telephony.Sms.Inbox.CONTENT_URI,
                      new String[] {"address", "body", "date", "date_sent"},
                      "date>=?",
                      new String[] {Long.toString(start)},
                      "date DESC")) {
            if (c != null)
              while (c.moveToNext() && hits.size() < 30) {
                String source = c.getString(0), message = c.getString(1);
                if (source == null || message == null) continue;
                String hit = Language.match(from, words, source, message, region, kind);
                if (!hit.isEmpty()) {
                  Store.Row row = new Store.Row();
                  row.put("sender", source);
                  row.put("body", message);
                  row.put("phrase", hit);
                  row.put("kind", kind);
                  row.put(
                      "received", Long.toString(c.getLong(3) > 0 ? c.getLong(3) : c.getLong(2)));
                  hits.add(row);
                }
              }
            a.runOnUiThread(
                () -> {
                  if (!a.isDestroyed() && editor.isShowing()) showMatches(hits);
                });
          } catch (RuntimeException e) {
            a.runOnUiThread(
                () -> {
                  if (!a.isDestroyed()) a.toast("Inbox preview is unavailable.");
                });
          }
        });
  }

  private void showMatches(List<Store.Row> hits) {
    if (hits.isEmpty()) {
      a.info("Inbox preview · last 30 days", "No matches.");
      return;
    }
    String[] labels = new String[hits.size()];
    for (int i = 0; i < hits.size(); i++) {
      Store.Row row = hits.get(i);
      labels[i] = row.s("sender") + " · " + a.when(row.n("received")) + "\n" + row.s("body");
    }
    boolean[] selected = new boolean[hits.size()];
    new AlertDialog.Builder(a)
        .setTitle("Recent matches · " + hits.size())
        .setMultiChoiceItems(labels, selected, (d, i, checked) -> selected[i] = checked)
        .setNegativeButton("Close", null)
        .setPositiveButton(
            "Forward selected…",
            (d, w) -> {
              int count = 0;
              for (boolean checked : selected) if (checked) count++;
              if (count == 0) {
                a.toast("No messages selected.");
                return;
              }
              if (!validEmail(destination)) return;
              String to = destination.getText().toString().trim();
              new AlertDialog.Builder(a)
                  .setTitle("Forward " + count + " to " + to + "?")
                  .setNegativeButton("Cancel", null)
                  .setPositiveButton(
                      "Forward",
                      (confirm, button) -> {
                        int added = 0;
                        android.database.sqlite.SQLiteDatabase conn = db.getWritableDatabase();
                        conn.beginTransaction();
                        try {
                          for (int i = 0; i < selected.length; i++)
                            if (selected[i]) {
                              Store.Row row = hits.get(i);
                              if (SmsReceiver.queue(
                                  db,
                                  editing == 0 ? -1 : editing,
                                  row.s("sender"),
                                  row.s("body"),
                                  row.n("received"),
                                  row.s("phrase"),
                                  to,
                                  SmsReceiver.region(a),
                                  row.s("kind"))) added++;
                            }
                          conn.setTransactionSuccessful();
                        } finally {
                          conn.endTransaction();
                        }
                        DeliveryJob.enqueue(a);
                        a.toast(added + " added to the email queue.");
                      })
                  .show();
            })
        .show();
  }

  private void manage(long id) {
    Store.Row r = db.byId("rule", id);
    if (r == null) {
      a.toast("This rule is unavailable.");
      return;
    }
    boolean smsAllowed = a.allowed(Manifest.permission.RECEIVE_SMS);
    boolean live = r.n("enabled") == 1 && smsAllowed;
    String status = live ? "Active" : "Paused";
    if (!smsAllowed) status += " · SMS permission required";
    String lastCheck =
        r.n("last_checked") == 0
            ? "Waiting for a new carrier SMS after enabling this rule."
            : a.when(r.n("last_checked")) + "\n" + r.s("last_result");
    String otpNote =
        Language.OTP_KIND.equals(r.s("kind"))
            ? "\n\nForwards one-time passcodes. Pause stops new messages. Mail already queued is"
                + " still sent."
            : "";
    new AlertDialog.Builder(a)
        .setTitle(r.s("phrases"))
        .setMessage(
            "Sender · "
                + (r.s("sender").isEmpty() ? "Any" : r.s("sender"))
                + "\nEmail · "
                + (r.s("destination").isEmpty() ? "Add your email" : r.s("destination"))
                + "\nStatus · "
                + status
                + otpNote
                + (live ? "\n\nLast SMS check\n" + lastCheck : ""))
        .setPositiveButton(
            live ? "Pause" : "Enable",
            (d, w) -> {
              if (live) {
                db.edit("rule", id, Store.values("enabled", 0));
                a.navigate("Rules");
              } else enable(id);
            })
        .setNeutralButton("Edit", (d, w) -> editor(id, "", ""))
        .setNegativeButton("Close", null)
        .show();
  }

  private void enable(long id) {
    Store.Row existing = db.byId("rule", id);
    if (existing != null && existing.s("destination").trim().isEmpty()) {
      a.toast("Add the email address that should receive matches.");
      editor(id, "", "");
      return;
    }
    pendingEnable = id;
    if (a.allowed(Manifest.permission.RECEIVE_SMS)) permission(32);
    else a.requestPermissions(new String[] {Manifest.permission.RECEIVE_SMS}, 32);
  }

  void permission(int req) {
    if (req == 32) {
      if (a.allowed(Manifest.permission.RECEIVE_SMS) && pendingEnable > 0) {
        db.edit(
            "rule",
            pendingEnable,
            Store.values("enabled", 1, "last_checked", 0, "last_result", ""));
        a.toast("Rule enabled");
      } else
        a.info(
            "SMS access required",
            "This rule is paused. Incoming SMS access must be allowed by Android and the"
                + " installer.");
      pendingEnable = 0;
      a.navigate("Rules");
    } else if (req == 33) {
      if (a.allowed(Manifest.permission.READ_SMS)) preview();
      else a.toast("Inbox preview needs read access. You can still test a pasted sample.");
    }
  }

  void persist(Bundle b) {
    b.putLong("enable", pendingEnable);
    if (editor != null && editor.isShowing()) {
      b.putBoolean("ruleEditor", true);
      b.putLong("ruleId", editing);
      b.putString("from", sender.getText().toString());
      b.putString("words", phrases == null ? "" : phrases.getText().toString());
      b.putString("to", destination.getText().toString());
    }
  }

  void restore(Bundle b) {
    pendingEnable = b.getLong("enable");
    if (b.getBoolean("ruleEditor"))
      a.getWindow()
          .getDecorView()
          .post(
              () -> {
                editor(b.getLong("ruleId"), "", "");
                sender.setText(b.getString("from"));
                if (phrases != null) phrases.setText(b.getString("words"));
                destination.setText(b.getString("to"));
              });
  }
}
