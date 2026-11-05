package com.resumescreener.service;

import com.resumescreener.model.JobDescription;
import com.resumescreener.nlp.EducationDetector;
import com.resumescreener.nlp.ExperienceCalculator;
import com.resumescreener.nlp.SkillTaxonomy;
import org.springframework.stereotype.Service;

import java.util.*;

/** Turns a free-text job posting (plus optional overrides) into structured requirements. */
@Service
public class JobDescriptionParser {

    private final SkillTaxonomy taxonomy;
    private final ExperienceCalculator experience;
    private final EducationDetector education;

    public JobDescriptionParser(SkillTaxonomy taxonomy, ExperienceCalculator experience,
                                EducationDetector education) {
        this.taxonomy = taxonomy;
        this.experience = experience;
        this.education = education;
    }

    /**
     * @param mustHaveCsv optional comma separated must-have skills; when blank every skill found in the
     *                    description is treated as required
     * @param minYears    optional override for required years of experience
     */
    public JobDescription parse(String title, String text, String mustHaveCsv, Double minYears) {
        Set<String> inText = taxonomy.extract(text);
        List<String> required = new ArrayList<>();
        List<String> nice = new ArrayList<>();

        if (mustHaveCsv != null && !mustHaveCsv.isBlank()) {
            for (String raw : mustHaveCsv.split("[,;\\n]")) {
                String term = raw.strip();
                if (term.isEmpty()) {
                    continue;
                }
                String canon = taxonomy.canonicalize(term);
                String skill = canon != null ? canon : term;
                if (required.stream().noneMatch(s -> s.equalsIgnoreCase(skill)) && required.size() < 40) {
                    required.add(skill);
                }
            }
            for (String s : inText) {
                if (required.stream().noneMatch(r -> r.equalsIgnoreCase(s))) {
                    nice.add(s);
                }
            }
        } else {
            required.addAll(inText);
        }

        double years = minYears != null && minYears >= 0 ? minYears : experience.statedYears(text);
        EducationDetector.Degree degree = education.minimumRequired(text);
        return new JobDescription(title == null || title.isBlank() ? "Untitled role" : title.strip(), text,
                List.copyOf(required), List.copyOf(nice), years, degree.level(), degree.label());
    }
}
