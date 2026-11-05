package com.resumescreener.nlp;

import com.resumescreener.model.SectionType;
import org.springframework.stereotype.Component;

import java.util.*;

/** Splits raw resume text into semantic sections by detecting heading lines. */
@Component
public class SectionSegmenter {

    private static final Map<String, SectionType> HEADINGS = new HashMap<>();

    static {
        put(SectionType.SUMMARY, "summary", "professional summary", "profile", "professional profile",
                "objective", "career objective", "about me", "about", "career summary");
        put(SectionType.EXPERIENCE, "experience", "work experience", "professional experience",
                "employment history", "work history", "employment", "career history", "internships",
                "internship experience", "relevant experience");
        put(SectionType.EDUCATION, "education", "academic background", "academics", "qualifications",
                "education and training", "academic qualifications", "educational qualifications");
        put(SectionType.SKILLS, "skills", "technical skills", "core competencies", "key skills", "technologies",
                "skills and tools", "tech stack", "technical proficiencies", "areas of expertise", "competencies");
        put(SectionType.CERTIFICATIONS, "certifications", "certificates", "licenses", "courses",
                "licenses and certifications", "certifications and courses", "training");
        put(SectionType.PROJECTS, "projects", "personal projects", "academic projects", "key projects",
                "selected projects");
        put(SectionType.OTHER, "achievements", "awards", "honors", "interests", "hobbies", "languages",
                "references", "publications", "volunteering", "extracurricular activities", "activities");
    }

    private static void put(SectionType type, String... names) {
        for (String n : names) {
            HEADINGS.put(n, type);
        }
    }

    public Map<SectionType, String> segment(String text) {
        Map<SectionType, StringBuilder> buckets = new EnumMap<>(SectionType.class);
        SectionType current = SectionType.HEADER;
        for (String line : text.split("\\R")) {
            SectionType heading = detectHeading(line);
            if (heading != null) {
                current = heading;
                continue;
            }
            buckets.computeIfAbsent(current, k -> new StringBuilder()).append(line).append('\n');
        }
        Map<SectionType, String> result = new EnumMap<>(SectionType.class);
        buckets.forEach((k, v) -> {
            String s = v.toString().strip();
            if (!s.isEmpty()) {
                result.put(k, s);
            }
        });
        return result;
    }

    static SectionType detectHeading(String line) {
        String t = line.strip();
        if (t.isEmpty() || t.length() > 40) {
            return null;
        }
        String norm = t.toLowerCase(Locale.ROOT).replace("&", " and ")
                .replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").strip();
        return HEADINGS.get(norm);
    }
}
