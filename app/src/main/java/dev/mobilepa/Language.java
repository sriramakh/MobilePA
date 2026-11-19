package dev.mobilepa;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import java.text.DateFormat;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.*;

final class Language {
  static final String PHRASE_KIND = "phrase";
  static final String OTP_KIND = "otp";
  static final String OTP_RULE_LABEL = "OTP and verification codes";
  static final String OTP_MATCH = "OTP";

  /** Phrases that phrase rules have always skipped, including broad "one time" wording. */
  private static final Pattern LEGACY_EXCLUSION =
      Pattern.compile(
          "(?i)\\b(otp|passcode|one[ -]?time(?: password| code)?|verification code|authentication"
              + " code|login code)\\b");

  /**
   * Positive OTP detector. Bare "one time" stays out so a promotional offer is not forwarded; phrase
   * rules still skip that wording via {@link #LEGACY_EXCLUSION}.
   */
  private static final Pattern OTP_KEYWORD =
      Pattern.compile(
          "(?i)\\b(?:otp|passcode|one[\\s-]?time[\\s-]+(?:password|code|passcode|pin)|verification"
              + "\\s+code|authentication\\s+code|security\\s+code|login\\s+code)\\b");

  private static final Pattern CODE_WORD = Pattern.compile("(?i)\\b(?:code|pin)\\b");
  private static final Pattern DIGIT_CODE =
      Pattern.compile("\\b(?:\\d{4}[- ]\\d{4}|\\d{3}[- ]\\d{3}|\\d{4,8})\\b");
  private static final int CODE_WINDOW = 32;

  static String normalizeSender(String value, String region) {
    String s = value.trim();
    if (!s.matches("[+\\d() .-]+")) return s.toLowerCase(Locale.ROOT);
    String digits = s.replaceAll("[() .-]", "");
    if (digits.length() < 7) return digits;
    PhoneNumberUtil phones = PhoneNumberUtil.getInstance();
    try {
      var number = phones.parse(digits, region);
      if (phones.isPossibleNumber(number))
        return phones.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
    } catch (NumberParseException ignored) {
      /* Short codes and unusual sender IDs match literally. */
    }
    return digits;
  }

  static String match(String required, String phrases, String sender, String body, String region) {
    return match(required, phrases, sender, body, region, PHRASE_KIND);
  }

  static String match(
      String required, String phrases, String sender, String body, String region, String kind) {
    return checkMatch(required, phrases, sender, body, region, kind).phrase();
  }

  record MatchResult(String phrase, String explanation) {}

  static boolean hasPhrases(String phrases) {
    return phrases != null && !phrases.replace(",", "").trim().isEmpty();
  }

  static MatchResult checkMatch(
      String required, String phrases, String sender, String body, String region) {
    return checkMatch(required, phrases, sender, body, region, PHRASE_KIND);
  }

  static MatchResult checkMatch(
      String required, String phrases, String sender, String body, String region, String kind) {
    boolean otpRule = OTP_KIND.equals(kind);
    if (!otpRule && !hasPhrases(phrases))
      return new MatchResult("", "Enter a word or phrase in the rule first.");
    if (body == null || body.trim().isEmpty())
      return new MatchResult("", "Enter the SMS text to check.");
    if (required != null
        && !required.trim().isEmpty()
        && !normalizeSender(required, region).equals(normalizeSender(sender, region))) {
      return new MatchResult(
          "", "This rule expects sender “" + required.trim() + "”. The SMS sender was different.");
    }
    if (otpRule) {
      if (isOtp(body))
        return new MatchResult(OTP_MATCH, "Matches a verification code.");
      return new MatchResult("", "The SMS doesn’t look like a verification code.");
    }
    if (isOtp(body) || LEGACY_EXCLUSION.matcher(body).find())
      return new MatchResult("", "Skipped because the SMS contains verification-code wording.");
    String lower = body.toLowerCase(Locale.ROOT);
    for (String phrase : phrases.split("[,\\n]")) {
      String p = phrase.trim();
      if (!p.isEmpty() && lower.contains(p.toLowerCase(Locale.ROOT)))
        return new MatchResult(p, "Matches “" + p + "”.");
    }
    return new MatchResult("", "The SMS doesn’t contain any of the rule’s phrases.");
  }

  /** Case-insensitive OTP / verification-code check. Does not return the code itself. */
  static boolean isOtp(String body) {
    if (body == null || body.isEmpty()) return false;
    if (OTP_KEYWORD.matcher(body).find()) return true;
    return digitsNearCodeWord(body);
  }

  private static boolean digitsNearCodeWord(String body) {
    List<int[]> words = new ArrayList<>();
    Matcher word = CODE_WORD.matcher(body);
    while (word.find()) words.add(new int[] {word.start(), word.end()});
    if (words.isEmpty()) return false;
    Matcher nums = DIGIT_CODE.matcher(body);
    while (nums.find()) {
      if (touchesOtherDigits(body, nums.start(), nums.end())) continue;
      for (int[] span : words) {
        int gap = nums.start() >= span[1] ? nums.start() - span[1] : span[0] - nums.end();
        if (gap >= 0 && gap <= CODE_WINDOW) return true;
      }
    }
    return false;
  }

  /** A 4–8 digit token glued to another number is a phone or reference, not a passcode. */
  private static boolean touchesOtherDigits(String body, int start, int end) {
    int before = start - 1;
    while (before >= 0 && isDigitSeparator(body.charAt(before))) before--;
    if (before >= 0 && Character.isDigit(body.charAt(before))) return true;
    int after = end;
    while (after < body.length() && isDigitSeparator(body.charAt(after))) after++;
    return after < body.length() && Character.isDigit(body.charAt(after));
  }

  private static boolean isDigitSeparator(char c) {
    return c == ' ' || c == '-' || c == '(' || c == ')';
  }

  static String mailSubject(long ruleId, String kind, String sender) {
    if (ruleId == 0) return "MobilePA test email";
    String id = oneLine(sender);
    if (OTP_KIND.equals(kind)) return "MobilePA OTP from " + id;
    return "SMS from " + id;
  }

  static String mailBody(String sms, String sender, long received, String phrase) {
    String when = DateFormat.getDateTimeInstance().format(new Date(received));
    return sms
        + "\n\nFrom: "
        + oneLine(sender)
        + "\nReceived: "
        + when
        + "\nMatched: "
        + oneLine(phrase);
  }

  private static String oneLine(String value) {
    if (value == null) return "";
    return value.replace('\r', ' ').replace('\n', ' ').trim();
  }

  static List<String> quoted(String text) {
    Matcher m = Pattern.compile("[\"“‘']([^\"”’']+)[\"”’']").matcher(text);
    List<String> out = new ArrayList<>();
    while (m.find()) out.add(m.group(1));
    return out;
  }

  static String searchTerm(String text) {
    String s = text.toLowerCase(Locale.ROOT).replaceAll("[?!.]+$", "").trim();
    if (s.matches(".*\\b(park|parking|parked)\\b.*")) return "park";
    return s.replaceFirst(
            "^(show me my |show me |show my |find my |find |search for |search |where is my |where"
                + " did i )",
            "")
        .replaceFirst(" (collection|plans)$", "")
        .trim();
  }

  static final class Reminder {
    String body;
    LocalDateTime time;

    Reminder(String body, LocalDateTime time) {
      this.body = body;
      this.time = time;
    }
  }

  static Reminder reminder(String text, LocalDateTime now) {
    String input = text.replaceFirst("(?i)^remind me\\s*", "").trim();
    Matcher m =
        Pattern.compile(
                "(?i)\\b(?:(today|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s+)?at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b")
            .matcher(input);
    if (!m.find()) return new Reminder(input.replaceFirst("(?i)^to ", ""), null);
    int hour = Integer.parseInt(m.group(2)),
        minute = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
    if (hour > 23
        || minute > 59
        || (m.group(4) != null && (hour < 1 || hour > 12))
        || (m.group(4) == null && m.group(3) == null && hour <= 12))
      return new Reminder(input, null);
    if (m.group(4) != null) hour = hour % 12 + ("pm".equalsIgnoreCase(m.group(4)) ? 12 : 0);
    LocalDate day = now.toLocalDate();
    String qualifier = m.group(1);
    if ("tomorrow".equalsIgnoreCase(qualifier)) day = day.plusDays(1);
    else if (qualifier != null && !"today".equalsIgnoreCase(qualifier))
      day =
          day.with(
              TemporalAdjusters.nextOrSame(DayOfWeek.valueOf(qualifier.toUpperCase(Locale.ROOT))));
    LocalDateTime due = day.atTime(hour, minute);
    if (!due.isAfter(now) && qualifier == null) due = due.plusDays(1);
    if (!due.isAfter(now) && qualifier != null && !qualifier.equalsIgnoreCase("today"))
      due = due.plusWeeks(1);
    String body =
        (input.substring(0, m.start()) + " " + input.substring(m.end()))
            .trim()
            .replaceFirst("(?i)^to\\s+", "")
            .trim();
    return new Reminder(body, due);
  }
}
