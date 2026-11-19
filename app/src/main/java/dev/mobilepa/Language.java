package dev.mobilepa;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.*;

final class Language {
  private static final Pattern CODE =
      Pattern.compile(
          "(?i)\\b(otp|passcode|one[ -]?time(?: password| code)?|verification code|authentication"
              + " code|login code)\\b");

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
    return checkMatch(required, phrases, sender, body, region).phrase();
  }

  record MatchResult(String phrase, String explanation) {}

  static boolean hasPhrases(String phrases) {
    return !phrases.replace(",", "").trim().isEmpty();
  }

  static MatchResult checkMatch(
      String required, String phrases, String sender, String body, String region) {
    if (!hasPhrases(phrases))
      return new MatchResult("", "Enter a word or phrase in the rule first.");
    if (body.trim().isEmpty()) return new MatchResult("", "Enter the SMS text to check.");
    if (!required.trim().isEmpty()
        && !normalizeSender(required, region).equals(normalizeSender(sender, region))) {
      return new MatchResult(
          "", "This rule expects sender “" + required.trim() + "”. The SMS sender was different.");
    }
    if (CODE.matcher(body).find())
      return new MatchResult("", "Skipped because the SMS contains verification-code wording.");
    String lower = body.toLowerCase(Locale.ROOT);
    for (String phrase : phrases.split("[,\\n]")) {
      String p = phrase.trim();
      if (!p.isEmpty() && lower.contains(p.toLowerCase(Locale.ROOT)))
        return new MatchResult(p, "Matches “" + p + "”.");
    }
    return new MatchResult("", "The SMS doesn’t contain any of the rule’s phrases.");
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
