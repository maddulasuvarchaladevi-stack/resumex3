package com.resumescreener.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structured information extracted from a single resume file. */
public record ParsedResume(
        String fileName,
        String rawText,
        String name,
        String email,
        String phone,
        List<String> links,
        Map<SectionType, String> sections,
        List<String> jobTitles,
        List<String> organizations,
        List<String> institutions,
        List<String> certifications,
        Set<String> skills,
        double experienceYears,
        int degreeLevel,
        String degreeLabel,
        List<String> warnings) {
}
