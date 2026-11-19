package dev.mobilepa;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.mail.*;
import javax.mail.internet.*;
import org.json.JSONObject;

final class SmtpAccount {
  static final class ConnectionFailure extends java.io.IOException {
    ConnectionFailure(MessagingException cause) {
      super("Could not connect to the outgoing server.", cause);
    }
  }

  private static final String ALIAS = "mobilepa.mail.smtp";

  private static SecretKey key() throws Exception {
    KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
    keys.load(null);
    if (keys.containsAlias(ALIAS)) return (SecretKey) keys.getKey(ALIAS, null);
    KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    gen.init(
        new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
    return gen.generateKey();
  }

  static void configure(
      SharedPreferences prefs, String address, String host, int port, String password)
      throws Exception {
    JSONObject config =
        new JSONObject()
            .put("address", address)
            .put("host", host)
            .put("port", port)
            .put("password", password);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    String data =
        Base64.encodeToString(
            cipher.doFinal(config.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    prefs
        .edit()
        .putString("smtp", data)
        .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
        .putString("method", "SMTP")
        .putBoolean("connected", true)
        .apply();
  }

  static int send(SharedPreferences prefs, String to, String subject, String body)
      throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.DEFAULT)));
    JSONObject config =
        new JSONObject(
            new String(
                cipher.doFinal(Base64.decode(prefs.getString("smtp", ""), Base64.DEFAULT)),
                StandardCharsets.UTF_8));
    Properties props = new Properties();
    props.setProperty("mail.smtps.ssl.checkserveridentity", "true");
    props.setProperty("mail.smtps.auth", "true");
    props.setProperty("mail.smtps.connectiontimeout", "10000");
    props.setProperty("mail.smtps.timeout", "20000");
    props.setProperty("mail.smtps.writetimeout", "20000");
    props.setProperty("mail.smtps.quitwait", "false");
    Session session = Session.getInstance(props);
    MimeMessage mail = new MimeMessage(session);
    String address = config.getString("address");
    mail.setFrom(new InternetAddress(address, true));
    mail.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, true));
    mail.setSubject(subject, "UTF-8");
    mail.setText(body, "UTF-8");
    mail.setSentDate(new Date());
    mail.saveChanges();
    Transport transport = session.getTransport("smtps");
    try {
      try {
        transport.connect(
            config.getString("host"), config.getInt("port"), address, config.getString("password"));
      } catch (AuthenticationFailedException e) {
        return 401;
      } catch (MessagingException e) {
        throw new ConnectionFailure(e);
      }
      try {
        transport.sendMessage(mail, mail.getAllRecipients());
        return 200;
      } catch (com.sun.mail.smtp.SMTPSendFailedException e) {
        return e.getReturnCode() / 100 == 4 ? 503 : 400;
      } catch (SendFailedException e) {
        return 400;
      }
    } finally {
      try {
        transport.close();
      } catch (MessagingException ignored) {
      }
    }
  }
}
