package dev.mobilepa;

import android.content.*;
import android.database.sqlite.*;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class SmsReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    // Queue the SMS locally. Do not log the body or any code.
    if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
    SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
    if (parts == null || parts.length == 0 || parts[0] == null) return;
    String sender = parts[0].getOriginatingAddress();
    if (sender == null) return;
    StringBuilder body = new StringBuilder();
    for (SmsMessage part : parts)
      if (part != null && part.getMessageBody() != null) body.append(part.getMessageBody());
    String message = body.toString();
    long received = parts[0].getTimestampMillis();
    long checked = System.currentTimeMillis();
    Store store = Store.get(context);
    SQLiteDatabase db = store.getWritableDatabase();
    boolean queued = false;
    String region = region(context);
    db.beginTransaction();
    try {
      for (Store.Row rule : store.rows("SELECT * FROM rule WHERE enabled=1 ORDER BY id")) {
        Language.MatchResult result =
            Language.checkMatch(
                rule.s("sender"),
                rule.s("phrases"),
                sender,
                message,
                region,
                rule.s("kind"));
        String detail = result.explanation();
        if (!result.phrase().isEmpty()) {
          boolean added =
              queue(
                  store,
                  rule.id(),
                  sender,
                  message,
                  received,
                  result.phrase(),
                  rule.s("destination"),
                  region,
                  rule.s("kind"));
          detail =
              result.explanation()
                  + (added
                      ? " See Delivery activity for status."
                      : " This SMS was already queued for this address.");
          queued = true;
        }
        store.edit("rule", rule.id(), Store.values("last_checked", checked, "last_result", detail));
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    if (queued) DeliveryJob.enqueue(context);
  }

  static String region(Context context) {
    android.telephony.TelephonyManager phone =
        context.getSystemService(android.telephony.TelephonyManager.class);
    String country = phone == null ? "" : phone.getSimCountryIso();
    return country == null || country.isEmpty()
        ? java.util.Locale.getDefault().getCountry()
        : country.toUpperCase(java.util.Locale.ROOT);
  }

  static boolean queue(
      Store store,
      long ruleId,
      String sender,
      String body,
      long received,
      String phrase,
      String destination,
      String region,
      String kind) {
    if (kind == null || kind.isEmpty()) kind = Language.PHRASE_KIND;
    String key =
        digest(
            Language.normalizeSender(sender, region)
                + "\n"
                + received
                + "\n"
                + body
                + "\n"
                + destination.toLowerCase(java.util.Locale.ROOT)
                + "\n"
                + kind);
    ContentValues values =
        Store.values(
            "fingerprint",
            key,
            "rule_id",
            ruleId,
            "sender",
            sender,
            "body",
            body,
            "phrase",
            phrase,
            "destination",
            destination,
            "received",
            received,
            "state",
            "Queued",
            "kind",
            kind);
    return store
            .getWritableDatabase()
            .insertWithOnConflict("mail", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        != -1;
  }

  private static String digest(String s) {
    try {
      return Base64.encodeToString(
          MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)),
          Base64.NO_WRAP);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
