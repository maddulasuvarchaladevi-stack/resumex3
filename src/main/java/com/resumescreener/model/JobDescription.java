package com.resumescreener.model;

import java.util.List;

/** A job posting and the requirements derived from it. */
public record JobDescription(
        String title,
        String text,
        List<String> requiredSkills,
        List<String> niceToHaveSkills,
        double requiredYears,
        int requiredDegreeLevel,
        String requiredDegreeLabel) {
}
