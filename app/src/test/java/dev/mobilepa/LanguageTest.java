package dev.mobilepa;

import static org.junit.Assert.*;

import java.time.LocalDateTime;
import org.junit.Test;

public class LanguageTest {
  @Test
  public void sampleShowsWhichRuleConditionFailed() {
    String sms = "Your electricity bill generated for November is ₹850.";
    String phrases = "bill generated, payment due";
    assertEquals(
        "bill generated", Language.checkMatch("POWER", phrases, "POWER", sms, "IN").phrase());
    assertFalse(Language.hasPhrases(" ,\n, "));
    assertTrue(
        Language.checkMatch("POWER", "", "POWER", sms, "IN").explanation().contains("phrase"));
    assertTrue(
        Language.checkMatch("POWER", phrases, "POWER", "", "IN")
            .explanation()
            .contains("SMS text"));
    assertTrue(
        Language.checkMatch("POWER", phrases, "OTHER", sms, "IN")
            .explanation()
            .contains("expects sender"));
    assertTrue(
        Language.checkMatch("POWER", phrases, "POWER", "OTP for payment due", "IN")
            .explanation()
            .contains("verification-code"));
    assertTrue(
        Language.checkMatch("POWER", phrases, "POWER", "Hello", "IN")
            .explanation()
            .contains("doesn’t contain"));
  }

  @Test
  public void senderAndAnyPhraseAreRequired() {
    assertEquals(
        "payment due",
        Language.match(
            "+91 100-200", "bill generated, payment due", "+91100200", "PAYMENT DUE today", "IN"));
    assertEquals("", Language.match("POWER", "payment due", "STORE", "payment due", "IN"));
    assertEquals(
        "bill generated",
        Language.match("", "bill generated", "POWER", "Your Bill Generated today", "IN"));
    assertEquals("", Language.match("", " , ", "POWER", "anything", "IN"));
  }

  @Test
  public void inboxAndIncomingSmsCanUseDifferentNumberFormats() {
    assertEquals(
        "bill", Language.match("5551234567", "bill", "+15551234567", "bill generated", "US"));
    assertEquals(
        "bill", Language.match("+91 98765 43210", "bill", "9876543210", "bill generated", "IN"));
    assertEquals(
        "", Language.match("+919876543210", "bill", "+19876543210", "bill generated", "IN"));
  }

  @Test
  public void commonOtpMessagesAreExcludedEvenIfTheyMatch() {
    assertEquals(
        "",
        Language.match(
            "BANK", "card", "BANK", "Your one-time password for your card is 123456", "IN"));
  }

  @Test
  public void reminderShowsFutureLocalTimeAndCorrectTask() {
    LocalDateTime now = LocalDateTime.of(2026, 11, 18, 19, 0);
    Language.Reminder reminder =
        Language.reminder("Remind me tomorrow at 6 PM to take the parking pass", now);
    assertEquals(LocalDateTime.of(2026, 11, 19, 18, 0), reminder.time);
    assertEquals("take the parking pass", reminder.body);
    assertEquals(
        LocalDateTime.of(2026, 11, 19, 0, 0),
        Language.reminder("Remind me at 12 AM to lock up", now).time);
    assertNull(Language.reminder("Remind me at 6 to lock up", now).time);
    assertNull(Language.reminder("Remind me tomorrow at 28:00 to lock up", now).time);
  }

  @Test
  public void searchHandlesExamplesShownOnDesk() {
    assertEquals("park", Language.searchTerm("Where did I park?"));
    assertEquals("jaipur", Language.searchTerm("Show my Jaipur plans."));
  }
}
