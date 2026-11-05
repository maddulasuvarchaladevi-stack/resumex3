package com.resumescreener.nlp;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based Named Entity Recognition for resumes: person name, contact details, job titles,
 * organisations, institutions and certifications. Pure Java, no model download required.
 */
@Component
public class EntityExtractor {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE_CANDIDATE = Pattern.compile("\\+?\\(?\\d[\\d\\s().-]{8,16}\\d");
    private static final Pattern YEAR_RANGE = Pattern.compile("(19|20)\\d{2}\\D+(19|20)\\d{2}");
    private static final Pattern LINK = Pattern.compile(
            "(?i)\\b(?:https?://)?(?:www\\.)?(?:linkedin\\.com|github\\.com|gitlab\\.com)/[A-Za-z0-9_./%-]+");
    private static final Pattern NAME_LINE = Pattern.compile(
            "^\\p{Lu}[\\p{L}.'-]*(?:\\s+\\p{Lu}?[\\p{L}.'-]+){1,3}$");

    private static final Pattern TITLE = Pattern.compile(
            "\\b((?:(?:Senior|Sr\\.?|Junior|Jr\\.?|Lead|Principal|Staff|Associate|Chief|Head|Assistant|Software|Data|Machine Learning|ML|AI|Full[- ]Stack|Full Stack|Backend|Back-End|Frontend|Front-End|DevOps|Cloud|QA|Java|Python|Systems?|Product|Project|Business|Research|Security|Mobile|Web|UI/UX|Technical|Quality)\\s+){0,3}"
                    + "(?:Engineer|Developer|Architect|Manager|Analyst|Scientist|Consultant|Designer|Administrator|Director|Specialist|Intern|Officer|Programmer|Researcher|Tester|Technician|Executive))\\b");

    private static final Pattern ORG_KEYWORD = Pattern.compile(
            "\\b(Inc|LLC|Ltd|Limited|Corp|Corporation|Technologies|Technology|Solutions|Systems|Labs|Pvt|Software|"
                    + "Services|Consulting|Bank|Group|Company|Co\\.|Networks|Industries|Enterprises|Analytics|Softech|Infotech)\\b\\.?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INSTITUTION_KEYWORD = Pattern.compile(
            "(?i)\\b(University|College|Institute|School|Academy|Polytechnic|IIT|NIT|IIIT|BITS)\\b");
    private static final Pattern CERT_LINE = Pattern.compile(
            "(?i)\\b(certified|certification|certificate|professional)\\b.*|\\b(PMP|CISSP|CKA|CKAD|CCNA|OCP)\\b");

    public record Contact(String name, String email, String phone, List<String> links) {
    }

    public Contact extractContact(String fullText, String headerText, String fileName) {
        String email = firstMatch(EMAIL, fullText);
        String phone = extractPhone(fullText);
        List<String> links = new ArrayList<>();
        Matcher lm = LINK.matcher(fullText);
        while (lm.find()) {
            String l = lm.group().replaceAll("[.,;)]+$", "");
            if (!links.contains(l) && links.size() < 4) {
                links.add(l);
            }
        }
        String name = extractName(headerText != null && !headerText.isBlank() ? headerText : fullText, fileName);
        return new Contact(name, email, phone, links);
    }

    private static String firstMatch(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group() : null;
    }

    private static String extractPhone(String text) {
        Matcher m = PHONE_CANDIDATE.matcher(text);
        while (m.find()) {
            String s = m.group().strip();
            int digits = s.replaceAll("\\D", "").length();
            if (digits >= 10 && digits <= 13 && !YEAR_RANGE.matcher(s).find()) {
                return s;
            }
        }
        return null;
    }

    private static String extractName(String text, String fileName) {
        int seen = 0;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (++seen > 6) {
                break;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (line.contains("@") || line.matches(".*\\d.*") || lower.contains("resume")
                    || lower.contains("curriculum") || lower.contains("http") || lower.contains("linkedin")) {
                continue;
            }
            String candidate = line.replaceAll("^(?i)name\\s*[:-]\\s*", "");
            if (candidate.equals(candidate.toUpperCase(Locale.ROOT))) {
                candidate = toTitleCase(candidate);
            }
            if (candidate.split("\\s+").length <= 4 && NAME_LINE.matcher(candidate).matches()
                    && !TITLE.matcher(candidate).find()) {
                return candidate;
            }
        }
        // fall back to the file name
        String base = fileName == null ? "Unknown" : fileName.replaceAll("\\.[^.]+$", "");
        base = base.replaceAll("(?i)[_-]?(resume|cv)[_-]?", " ").replaceAll("[_\\-.]+", " ").strip();
        return base.isEmpty() ? "Unknown candidate" : toTitleCase(base);
    }

    static String toTitleCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    public List<String> extractJobTitles(String text) {
        Set<String> titles = new LinkedHashSet<>();
        Matcher m = TITLE.matcher(text);
        while (m.find() && titles.size() < 6) {
            String t = m.group(1).strip();
            // reject lowercase running prose such as "an engineer" (pattern requires capitals anyway)
            titles.add(t);
        }
        return new ArrayList<>(titles);
    }

    public List<String> extractOrganizations(String experienceText) {
        return extractByKeyword(experienceText, ORG_KEYWORD, 8);
    }

    public List<String> extractInstitutions(String educationText) {
        return extractByKeyword(educationText, INSTITUTION_KEYWORD, 4);
    }

    private static List<String> extractByKeyword(String text, Pattern keyword, int limit) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return List.of();
        }
        for (String line : text.split("\\R")) {
            // split a line like "Senior Engineer | Acme Technologies Pvt Ltd, Pune (2019 - 2022)"
            for (String part : line.split("\\||,|\\s[-–—]\\s|\\s@\\s|\\sat\\s|\\(|\\)")) {
                String p = part.strip().replaceAll("^[•*·\\-\\s]+", "");
                p = p.replaceAll("\\b(19|20)\\d{2}\\b.*$", "").strip();
                if (p.length() < 3 || p.length() > 70 || p.split("\\s+").length > 7) {
                    continue;
                }
                if (keyword.matcher(p).find() && Character.isUpperCase(p.charAt(0))) {
                    out.add(p);
                    if (out.size() >= limit) {
                        return new ArrayList<>(out);
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    public List<String> extractCertifications(String certSection, String fullText) {
        Set<String> out = new LinkedHashSet<>();
        if (certSection != null) {
            for (String line : certSection.split("\\R")) {
                String l = line.strip().replaceAll("^[•*·\\-\\s]+", "");
                if (l.length() >= 3 && l.length() <= 120) {
                    out.add(l);
                }
                if (out.size() >= 8) {
                    break;
                }
            }
        }
        if (out.isEmpty() && fullText != null) {
            for (String line : fullText.split("\\R")) {
                String l = line.strip().replaceAll("^[•*·\\-\\s]+", "");
                if (l.length() <= 100 && CERT_LINE.matcher(l).find() && out.size() < 5
                        && l.matches("(?i).*\\b(certified|certification|certificate)\\b.*")) {
                    out.add(l);
                }
            }
        }
        return new ArrayList<>(out);
    }
}
