package vn.aimtt.trello;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/**
 * Rule-based deadline suggestions (SDS §5.12). Relative phrases need the meeting date — never the upload date.
 * A suggestion is not a decision: the user still confirms date, time and timezone; "trước X" is flagged because
 * "before" and "on" a day are different policies.
 */
public final class DeadlineSuggester {
    private DeadlineSuggester() {}
    public record Suggestion(String dueLocal, String rule, String note) {}
    private static final LocalTime DEFAULT_TIME = LocalTime.of(17, 0);
    private static final Map<String, DayOfWeek> WEEKDAYS = new LinkedHashMap<>();
    static {
        WEEKDAYS.put("thu hai", DayOfWeek.MONDAY); WEEKDAYS.put("thu 2", DayOfWeek.MONDAY); WEEKDAYS.put("monday", DayOfWeek.MONDAY);
        WEEKDAYS.put("thu ba", DayOfWeek.TUESDAY); WEEKDAYS.put("thu 3", DayOfWeek.TUESDAY); WEEKDAYS.put("tuesday", DayOfWeek.TUESDAY);
        WEEKDAYS.put("thu tu", DayOfWeek.WEDNESDAY); WEEKDAYS.put("thu 4", DayOfWeek.WEDNESDAY); WEEKDAYS.put("wednesday", DayOfWeek.WEDNESDAY);
        WEEKDAYS.put("thu nam", DayOfWeek.THURSDAY); WEEKDAYS.put("thu 5", DayOfWeek.THURSDAY); WEEKDAYS.put("thursday", DayOfWeek.THURSDAY);
        WEEKDAYS.put("thu sau", DayOfWeek.FRIDAY); WEEKDAYS.put("thu 6", DayOfWeek.FRIDAY); WEEKDAYS.put("friday", DayOfWeek.FRIDAY);
        WEEKDAYS.put("thu bay", DayOfWeek.SATURDAY); WEEKDAYS.put("thu 7", DayOfWeek.SATURDAY); WEEKDAYS.put("saturday", DayOfWeek.SATURDAY);
        WEEKDAYS.put("chu nhat", DayOfWeek.SUNDAY); WEEKDAYS.put("sunday", DayOfWeek.SUNDAY);
    }

    public static Optional<Suggestion> suggest(String raw, LocalDate meetingDate) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String text = fold(raw);
        boolean before = text.matches(".*\\b(truoc|before|by)\\b.*");
        String note = before ? "Câu gốc nói \"trước\": kiểm tra hạn là chính ngày này hay ngày liền trước." : null;
        LocalTime time = time(text).orElse(null);
        Optional<LocalDate> date = explicitDate(text, meetingDate);
        String rule;
        if (date.isPresent()) rule = "EXPLICIT_DATE";
        else {
            if (meetingDate == null) return Optional.empty();
            if (text.matches(".*\\b(hom nay|today|trong ngay)\\b.*")) { date = Optional.of(meetingDate); rule = "TODAY"; }
            else if (text.matches(".*\\b(ngay mai|tomorrow)\\b.*")) { date = Optional.of(meetingDate.plusDays(1)); rule = "TOMORROW"; }
            else if (text.matches(".*\\b(ngay kia|ngay mot)\\b.*")) { date = Optional.of(meetingDate.plusDays(2)); rule = "DAY_AFTER_TOMORROW"; }
            else {
                DayOfWeek day = null;
                for (var entry : WEEKDAYS.entrySet()) if (text.matches(".*\\b" + entry.getKey() + "\\b.*")) { day = entry.getValue(); break; }
                if (day == null) return Optional.empty(); // "cuối tuần", "sớm", "ASAP" stay ambiguous
                boolean nextWeek = text.matches(".*\\b(tuan sau|tuan toi|next week)\\b.*");
                LocalDate candidate = meetingDate.with(java.time.temporal.TemporalAdjusters.next(day));
                if (nextWeek) candidate = meetingDate.plusWeeks(1).with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).with(java.time.temporal.TemporalAdjusters.nextOrSame(day));
                date = Optional.of(candidate); rule = nextWeek ? "WEEKDAY_NEXT_WEEK" : "NEXT_WEEKDAY";
                String weekdayNote = "Hiểu là " + (nextWeek ? "thứ đó của tuần sau" : "lần gần nhất sau ngày họp") + "; kiểm tra lại.";
                note = note == null ? weekdayNote : note + " " + weekdayNote;
            }
        }
        LocalDateTime local = LocalDateTime.of(date.get(), time == null ? DEFAULT_TIME : time);
        if (time == null) note = (note == null ? "" : note + " ") + "Chưa nêu giờ: dùng 17:00 mặc định.";
        return Optional.of(new Suggestion(local.toString(), rule, note));
    }

    /** Lowercase without diacritics, keeping digits, ':' and '/' that carry date/time meaning. */
    static String fold(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT).replace('đ', 'd');
        return java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replaceAll("\\s+", " ").trim();
    }
    private static Optional<LocalTime> time(String text) {
        var m = Pattern.compile("(?<![\\d/])(\\d{1,2})\\s*(?::|h|gio)\\s*(\\d{2})?\\b(?!\\s*/)").matcher(text);
        if (m.find()) {
            int hour = Integer.parseInt(m.group(1)), minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            if (text.matches(".*\\b" + m.group(1) + "\\s*(?::\\d{2})?\\s*pm\\b.*") && hour < 12) hour += 12;
            if (hour <= 23 && minute <= 59) return Optional.of(LocalTime.of(hour, minute));
        }
        var pm = Pattern.compile("\\b(\\d{1,2})\\s*(am|pm)\\b").matcher(text);
        if (pm.find()) {
            int hour = Integer.parseInt(pm.group(1)) % 12 + ("pm".equals(pm.group(2)) ? 12 : 0);
            if (hour <= 23) return Optional.of(LocalTime.of(hour, 0));
        }
        return Optional.empty();
    }
    private static Optional<LocalDate> explicitDate(String text, LocalDate meetingDate) {
        var full = Pattern.compile("(?<!\\d)(\\d{1,2})/(\\d{1,2})/(\\d{4})(?!\\d)").matcher(text);
        try {
            if (full.find()) return Optional.of(LocalDate.of(Integer.parseInt(full.group(3)), Integer.parseInt(full.group(2)), Integer.parseInt(full.group(1))));
            var iso = Pattern.compile("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)").matcher(text);
            if (iso.find()) return Optional.of(LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3))));
            var shortDate = Pattern.compile("(?<![\\d/])(\\d{1,2})/(\\d{1,2})(?![\\d/])").matcher(text);
            if (shortDate.find() && meetingDate != null) {
                var date = LocalDate.of(meetingDate.getYear(), Integer.parseInt(shortDate.group(2)), Integer.parseInt(shortDate.group(1)));
                return Optional.of(date.isBefore(meetingDate.minusMonths(6)) ? date.plusYears(1) : date);
            }
        } catch (DateTimeException ignored) { }
        return Optional.empty();
    }
}
