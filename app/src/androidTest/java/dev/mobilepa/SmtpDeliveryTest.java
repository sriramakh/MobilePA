package dev.mobilepa;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SmtpDeliveryTest {
  @Test
  public void encryptedAccountSubmitsMailOverVerifiedTls() throws Exception {
    Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SharedPreferences prefs = app.getSharedPreferences("smtp-loopback-test", Context.MODE_PRIVATE);
    SSLContext original = SSLContext.getDefault();
    ExecutorService io = Executors.newSingleThreadExecutor();
    try {
      okhttp3.tls.HeldCertificate certificate =
          new okhttp3.tls.HeldCertificate.Builder()
              .commonName("localhost")
              .addSubjectAlternativeName("localhost")
              .build();
      SSLContext server =
          new okhttp3.tls.HandshakeCertificates.Builder()
              .heldCertificate(certificate)
              .build()
              .sslContext();
      SSLContext client =
          new okhttp3.tls.HandshakeCertificates.Builder()
              .addTrustedCertificate(certificate.certificate())
              .build()
              .sslContext();
      SSLContext.setDefault(client);
      try (SSLServerSocket listen =
          (SSLServerSocket)
              server
                  .getServerSocketFactory()
                  .createServerSocket(0, 1, InetAddress.getByName("localhost"))) {
        listen.setSoTimeout(15000);
        Future<String> message = io.submit(() -> readMessage(listen));
        SmtpAccount.configure(
            prefs, "sender@example.test", "localhost", listen.getLocalPort(), "test-pass");
        assertFalse(prefs.getAll().toString().contains("test-pass"));
        int status;
        try {
          status =
              SmtpAccount.send(
                  prefs, "owner@example.test", "SMS from test", "Your bill generated today");
        } catch (Exception clientError) {
          try {
            message.get(2, TimeUnit.SECONDS);
          } catch (Exception serverError) {
            clientError.addSuppressed(serverError);
          }
          throw clientError;
        }
        assertEquals(200, status);
        String mime = message.get(10, TimeUnit.SECONDS);
        assertTrue(mime.contains("Subject: SMS from test"));
        assertTrue(mime.contains("To: owner@example.test"));
        assertTrue(mime.contains("Your bill generated today"));
      }
    } finally {
      SSLContext.setDefault(original);
      io.shutdownNow();

      prefs.edit().clear().commit();
    }
  }

  private String readMessage(SSLServerSocket listener) throws Exception {
    try (Socket socket = listener.accept()) {
      socket.setSoTimeout(12000);
      BufferedReader in =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
      BufferedWriter out =
          new BufferedWriter(
              new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
      reply(out, "220 localhost test");
      assertTrue(in.readLine().startsWith("EHLO"));
      reply(out, "250-localhost\r\n250 AUTH LOGIN");
      assertEquals("AUTH LOGIN", in.readLine());
      reply(out, "334 VXNlcm5hbWU6");
      assertEquals("sender@example.test", decode(in.readLine()));
      reply(out, "334 UGFzc3dvcmQ6");
      assertEquals("test-pass", decode(in.readLine()));
      reply(out, "235 Authenticated");
      assertTrue(in.readLine().startsWith("MAIL FROM:"));
      reply(out, "250 OK");
      assertEquals("RCPT TO:<owner@example.test>", in.readLine());
      reply(out, "250 OK");
      assertEquals("DATA", in.readLine());
      reply(out, "354 Send message");
      StringBuilder body = new StringBuilder();
      String line;
      while ((line = in.readLine()) != null && !line.equals(".")) body.append(line).append('\n');
      reply(out, "250 Accepted");
      return body.toString();
    }
  }

  private String decode(String s) {
    return new String(android.util.Base64.decode(s, 0), StandardCharsets.UTF_8);
  }

  private void reply(BufferedWriter out, String s) throws IOException {
    out.write(s + "\r\n");
    out.flush();
  }
}
