package com.resumescreener.service;

import com.resumescreener.model.ParsedResume;
import com.resumescreener.model.SectionType;
import com.resumescreener.nlp.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Steps 1-2: raw text -> sections -> entities & skills. */
@Service
public class ResumeParserService {

    private final SectionSegmenter segmenter;
    private final EntityExtractor entities;
    private final SkillTaxonomy taxonomy;
    private final ExperienceCalculator experience;
    private final EducationDetector education;

    public ResumeParserService(SectionSegmenter segmenter, EntityExtractor entities, SkillTaxonomy taxonomy,
                               ExperienceCalculator experience, EducationDetector education) {
        this.segmenter = segmenter;
        this.entities = entities;
        this.taxonomy = taxonomy;
        this.experience = experience;
        this.education = education;
    }

    public ParsedResume parse(String fileName, String text, List<String> extractionWarnings) {
        Map<SectionType, String> sections = segmenter.segment(text);
        EntityExtractor.Contact contact = entities.extractContact(text, sections.get(SectionType.HEADER), fileName);

        String expText = sections.get(SectionType.EXPERIENCE);
        String eduText = sections.get(SectionType.EDUCATION);
        String expSource = expText != null ? expText : textWithout(sections, SectionType.EDUCATION,
                SectionType.CERTIFICATIONS);

        Set<String> skills = taxonomy.extract(text);
        EducationDetector.Degree degree = education.detect(eduText != null ? eduText : text);

        List<String> warnings = new ArrayList<>(extractionWarnings);
        if (contact.email() == null) {
            warnings.add("No email address detected.");
        }
        if (skills.isEmpty() && text.length() >= TextExtractionService.MIN_TEXT_LENGTH) {
            warnings.add("No known skills recognised in this resume.");
        }

        return new ParsedResume(
                fileName,
                text,
                contact.name(),
                contact.email(),
                contact.phone(),
                contact.links(),
                sections,
                entities.extractJobTitles(expSource),
                entities.extractOrganizations(expText != null ? expText : expSource),
                entities.extractInstitutions(eduText != null ? eduText : ""),
                entities.extractCertifications(sections.get(SectionType.CERTIFICATIONS), text),
                skills,
                experience.calculateYears(expSource),
                degree.level(),
                degree.label(),
                warnings);
    }

    private static String textWithout(Map<SectionType, String> sections, SectionType... excluded) {
        List<SectionType> ex = List.of(excluded);
        StringBuilder sb = new StringBuilder();
        sections.forEach((k, v) -> {
            if (!ex.contains(k)) {
                sb.append(v).append('\n');
            }
        });
        return sb.toString();
    }
}
