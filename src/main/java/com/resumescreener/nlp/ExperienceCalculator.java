package com.resumescreener.nlp;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Computes total professional experience from the date ranges found in a resume. */
@Component
public class ExperienceCalculator {

    private static final String MONTH_NAMES = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec";
    private static final String START = "(?:(?:(?:" + MONTH_NAMES + ")[a-z]*)\\.?,?\\s+|\\d{1,2}[/.-])?(?:19|20)\\d{2}";
    private static final Pattern RANGE = Pattern.compile(
            "(?i)(" + START + ")\\s*(?:-|–|—|to|until|till)\\s*(" + START
                    + "|present|current|now|till date|ongoing|today)");
    private static final Pattern DATE_PARTS = Pattern.compile(
            "(?i)(?:(" + MONTH_NAMES + ")[a-z]*\\.?,?\\s+|(\\d{1,2})[/.-])?((?:19|20)\\d{2})");
    private static final Pattern STATED_YEARS = Pattern.compile(
            "(?i)(\\d{1,2})\\s*\\+?\\s*(?:years?|yrs?)\\s+(?:of\\s+)?(?:(?:[\\w+#.-]+)\\s+){0,3}?experience");
    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct",
            "nov", "dec"};

    /** @return years of (non-overlapping) experience, rounded to one decimal. */
    public double calculateYears(String experienceText) {
        return calculateYears(experienceText, LocalDate.now());
    }

    double calculateYears(String experienceText, LocalDate today) {
        if (experienceText == null || experienceText.isBlank()) {
            return 0;
        }
        int nowIndex = today.getYear() * 12 + today.getMonthValue() - 1;
        List<int[]> intervals = new ArrayList<>();
        Matcher m = RANGE.matcher(experienceText);
        while (m.find()) {
            int start = toIndex(m.group(1), true, nowIndex);
            String endRaw = m.group(2);
            int end = endRaw.matches("(?i)present|current|now|till date|ongoing|today")
                    ? nowIndex : toIndex(endRaw, false, nowIndex);
            if (start >= 0 && end >= start) {
                intervals.add(new int[]{start, Math.min(end, nowIndex)});
            }
        }
        intervals.sort(Comparator.comparingInt(a -> a[0]));
        int months = 0;
        int curStart = -1;
        int curEnd = -1;
        for (int[] iv : intervals) {
            if (curStart < 0) {
                curStart = iv[0];
                curEnd = iv[1];
            } else if (iv[0] <= curEnd + 1) {
                curEnd = Math.max(curEnd, iv[1]);
            } else {
                months += curEnd - curStart + 1;
                curStart = iv[0];
                curEnd = iv[1];
            }
        }
        if (curStart >= 0) {
            months += curEnd - curStart + 1;
        }
        double computed = Math.round(months / 12.0 * 10) / 10.0;
        if (computed > 0) {
            return computed;
        }
        // no dated roles -> trust an explicit "N years of experience" statement
        Matcher s = STATED_YEARS.matcher(experienceText);
        double stated = 0;
        while (s.find()) {
            stated = Math.max(stated, Double.parseDouble(s.group(1)));
        }
        return stated;
    }

    private static int toIndex(String raw, boolean isStart, int nowIndex) {
        Matcher m = DATE_PARTS.matcher(raw);
        if (!m.find()) {
            return -1;
        }
        int year = Integer.parseInt(m.group(3));
        int month;
        if (m.group(1) != null) {
            String mon = m.group(1).toLowerCase(Locale.ROOT);
            month = 1;
            for (int i = 0; i < MONTHS.length; i++) {
                if (MONTHS[i].equals(mon)) {
                    month = i + 1;
                }
            }
        } else if (m.group(2) != null) {
            month = Math.max(1, Math.min(12, Integer.parseInt(m.group(2))));
        } else {
            month = isStart ? 1 : 12;
        }
        return year * 12 + month - 1;
    }

    /** Explicit "N years of experience" statements, used for job descriptions. */
    public double statedYears(String text) {
        Matcher s = STATED_YEARS.matcher(text == null ? "" : text);
        double v = 0;
        if (s.find()) {
            v = Double.parseDouble(s.group(1));
        }
        return v;
    }
}
