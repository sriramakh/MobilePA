package dev.mobilepa;

import static org.junit.Assert.*;

import org.junit.Test;

public class OtpMatchTest {
  @Test
  public void keywordMentionsMatchRegardlessOfCase() {
    String[] samples = {
      "Your OTP is 482913",
      "otp 1111",
      "(OTP) 102938",
      "One-Time Password: 102938",
      "ONE TIME PASSWORD 102938",
      "onetime password 102938",
      "one-time-password 102938",
      "Your one-time code is 654321",
      "one-time passcode 2468",
      "one time pin 1357",
      "Your verification code is 445566",
      "VERIFICATION CODE 445566",
      "Passcode 839201",
      "Here is your passcode",
      "Security code 4412",
      "SeCuRiTy CoDe",
      "Login code: 778899",
      "authentication code 192837"
    };
    for (String sample : samples) {
      assertTrue(sample, Language.isOtp(sample));
      assertEquals(
          sample,
          Language.OTP_MATCH,
          Language.match(
              "", Language.OTP_RULE_LABEL, "BANK", sample, "IN", Language.OTP_KIND));
    }
  }

  @Test
  public void digitsBesideCodeOrPinMatch() {
    assertTrue(Language.isOtp("Use code 123456 to continue"));
    assertTrue(Language.isOtp("Code 582910"));
    assertTrue(Language.isOtp("PIN 4821"));
    assertTrue(Language.isOtp("pin 9999"));
    assertTrue(Language.isOtp("123456 is your code"));
    assertTrue(Language.isOtp("code: 1234"));
    assertTrue(Language.isOtp("code 12345678"));
    assertTrue(Language.isOtp("Your WhatsApp code: 123-456"));
    assertTrue(Language.isOtp("backup code 1234-5678"));
    assertTrue(Language.isOtp("G-482913 is your Google code"));
    assertTrue(Language.isOtp("code" + ".".repeat(32) + "123456"));
    assertTrue(Language.isOtp("123456" + " ".repeat(32) + "code"));
  }

  @Test
  public void ordinaryMessagesAndLongNumbersDoNotMatch() {
    assertFalse(Language.isOtp(null));
    assertFalse(Language.isOtp(""));
    assertFalse(Language.isOtp("   "));
    assertFalse(Language.isOtp("Your electricity bill generated for November is ₹850."));
    assertFalse(Language.isOtp("The code is ready"));
    assertFalse(Language.isOtp("PIN setup is complete"));
    assertFalse(Language.isOtp("Hello there"));
    assertFalse(Language.isOtp("code 123"));
    assertFalse(Language.isOtp("code 123456789"));
    assertFalse(Language.isOtp("customer code 9876543210"));
    assertFalse(Language.isOtp("code123456"));
    assertFalse(Language.isOtp("encoding 123456"));
    assertFalse(Language.isOtp("Your pincode is 560034"));
    assertFalse(Language.isOtp("A one time offer on your card"));
    assertFalse(Language.isOtp("Call us about your code at +1 555 123 4567"));
    assertFalse(Language.isOtp("code" + ".".repeat(33) + "123456"));
    assertFalse(Language.isOtp("123456" + " ".repeat(33) + "code"));
  }

  @Test
  public void otpRuleRespectsSenderAndPhraseRulesStillSkipCodes() {
    String sms = "Your one-time password for your card is 123456";
    assertEquals("", Language.match("BANK", "card", "BANK", sms, "IN"));
    assertEquals(
        Language.OTP_MATCH,
        Language.match("BANK", "card", "BANK", sms, "IN", Language.OTP_KIND));
    assertEquals(
        "",
        Language.match("VK-ICICI", Language.OTP_RULE_LABEL, "VK-HDFC", "OTP 999999", "IN", "otp"));
    assertEquals(
        Language.OTP_MATCH,
        Language.match("vk-icici", Language.OTP_RULE_LABEL, "VK-ICICI", "OTP 999999", "IN", "otp"));
    assertEquals(
        "",
        Language.match(
            "",
            Language.OTP_RULE_LABEL,
            "POWER",
            "Your bill generated today",
            "IN",
            Language.OTP_KIND));
    assertEquals(
        "",
        Language.match("", "continue", "BANK", "Use code 123456 to continue", "IN"));
    assertTrue(
        Language.checkMatch("BANK", "card", "BANK", "A one time offer on your card", "IN")
            .explanation()
            .contains("verification-code"));
    assertTrue(
        Language.checkMatch(
                "", Language.OTP_RULE_LABEL, "POWER", "Your bill generated today", "IN", "otp")
            .explanation()
            .contains("look like a verification code"));
    Language.MatchResult sender =
        Language.checkMatch("BANK", Language.OTP_RULE_LABEL, "SHOP", "OTP 123456", "IN", "otp");
    assertEquals("", sender.phrase());
    assertTrue(sender.explanation().contains("expects sender"));
  }

  @Test
  public void matchResultDoesNotEchoTheCode() {
    String sms = "Your OTP is 482913. Do not share it.";
    Language.MatchResult result =
        Language.checkMatch("", Language.OTP_RULE_LABEL, "VK-ICICI", sms, "IN", Language.OTP_KIND);
    assertEquals(Language.OTP_MATCH, result.phrase());
    assertFalse(result.phrase().contains("482913"));
    assertFalse(result.explanation().contains("482913"));
    assertFalse(result.explanation().contains(sms));
  }

  @Test
  public void forwardedMailUsesSearchableSubjectAndFullText() {
    assertEquals("MobilePA test email", Language.mailSubject(0, Language.PHRASE_KIND, "MobilePA"));
    assertEquals(
        "MobilePA test email", Language.mailSubject(0, Language.OTP_KIND, "VK-ICICI"));
    assertEquals(
        "MobilePA OTP from VK-ICICI",
        Language.mailSubject(4, Language.OTP_KIND, "VK-ICICI"));
    assertEquals("SMS from POWER", Language.mailSubject(4, Language.PHRASE_KIND, "POWER"));
    String injected = Language.mailSubject(4, Language.OTP_KIND, "VK-ICICI\r\nBcc: evil@example.com");
    assertFalse(injected.contains("\r"));
    assertFalse(injected.contains("\n"));
    assertTrue(injected.startsWith("MobilePA OTP from VK-ICICI"));

    String sms = "Your OTP is 482913. Do not share it.";
    String body = Language.mailBody(sms, "VK-ICICI", 1_763_500_000_000L, Language.OTP_MATCH);
    assertTrue(body.startsWith(sms + "\n\nFrom: VK-ICICI\nReceived: "));
    assertTrue(body.contains("\nReceived: "));
    assertTrue(body.endsWith("\nMatched: OTP"));
  }
}
