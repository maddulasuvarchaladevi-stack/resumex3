package com.resumescreener.nlp;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** Detects the highest academic degree level mentioned in a text (0 none .. 4 doctorate). */
@Component
public class EducationDetector {

    public record Degree(int level, String label) {
    }

    private static final String[] LABELS = {"Not specified", "Associate / Diploma", "Bachelor's", "Master's",
            "Doctorate"};

    // word forms are case-insensitive
    private static final Pattern L4 = Pattern.compile("(?i)\\b(ph\\.?\\s?d|doctorate|doctor of philosophy)\\b");
    private static final Pattern L3 = Pattern.compile(
            "(?i)\\b(masters?|master's|m\\.?sc|m\\.?tech|mba|mca|m\\.?eng|post[- ]?graduate)\\b");
    private static final Pattern L2 = Pattern.compile(
            "(?i)\\b(bachelors?|bachelor's|b\\.?sc|b\\.?tech|bca|b\\.?eng|b\\.?com|undergraduate)\\b");
    private static final Pattern L1 = Pattern.compile("(?i)\\b(associate'?s?\\s+degree|diploma)\\b");
    // short abbreviations are case-sensitive to avoid matching ordinary words ("be", "ms")
    private static final Pattern L3_ABBR = Pattern.compile("\\b(M\\.S\\.?|MS|M\\.E\\.?|ME|M\\.A\\.?|MA)\\b");
    private static final Pattern L2_ABBR = Pattern.compile("\\b(B\\.S\\.?|BS|B\\.E\\.?|BE|B\\.A\\.?|BA)\\b");

    public Degree detect(String text) {
        if (text == null || text.isBlank()) {
            return new Degree(0, LABELS[0]);
        }
        int level = 0;
        if (L4.matcher(text).find()) {
            level = 4;
        } else if (L3.matcher(text).find() || L3_ABBR.matcher(text).find()) {
            level = 3;
        } else if (L2.matcher(text).find() || L2_ABBR.matcher(text).find()) {
            level = 2;
        } else if (L1.matcher(text).find()) {
            level = 1;
        }
        return new Degree(level, LABELS[level]);
    }

    /** Lowest degree level mentioned in a job description (the minimum requirement). */
    public Degree minimumRequired(String jdText) {
        if (jdText == null) {
            return new Degree(0, LABELS[0]);
        }
        int min = 0;
        int[] levels = {1, 2, 3, 4};
        Pattern[][] pats = {{L1}, {L2, L2_ABBR}, {L3, L3_ABBR}, {L4}};
        for (int i = 0; i < levels.length && min == 0; i++) {
            for (Pattern p : pats[i]) {
                if (p.matcher(jdText).find()) {
                    min = levels[i];
                    break;
                }
            }
        }
        return new Degree(min, LABELS[min]);
    }
}
