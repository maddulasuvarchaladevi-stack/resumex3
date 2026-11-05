package com.resumescreener.model;

import java.util.List;
import java.util.Map;

/** Scoring result of one candidate; all scores are integer percentages (0-100). */
public record CandidateScore(
        int index,
        int rank,
        ParsedResume resume,
        int overall,
        int skill,
        int semantic,
        int experience,
        int education,
        List<String> matchedSkills,
        Map<String, String> relatedSkills,
        List<String> missingSkills,
        List<String> niceToHaveMatched,
        List<String> extraSkills,
        String tier,
        boolean shortlisted,
        List<String> explanation) {
}
