package com.resumescreener.service;

import com.resumescreener.model.*;
import com.resumescreener.nlp.SkillTaxonomy;
import com.resumescreener.nlp.TfIdfVectorizer;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Steps 3-5: vectorise, score and rank.
 * <pre>
 * Total = w1*SkillMatch + w2*SemanticSimilarity + w3*ExperienceDepth + w4*EducationAlignment
 * </pre>
 */
@Service
public class MatchingEngine {

    /** Raw TF-IDF cosine at or above this value counts as a 100% semantic match. */
    static final double COSINE_FULL_SCORE = 0.6;
    static final double RELATED_CREDIT = 0.5;
    static final double NICE_TO_HAVE_SHARE = 0.15;

    private final SkillTaxonomy taxonomy;
    private final TfIdfVectorizer vectorizer;

    public MatchingEngine(SkillTaxonomy taxonomy, TfIdfVectorizer vectorizer) {
        this.taxonomy = taxonomy;
        this.vectorizer = vectorizer;
    }

    public List<CandidateScore> rank(JobDescription jd, List<ParsedResume> resumes, Weights weights,
                                     int shortlistThreshold) {
        List<String> docs = new ArrayList<>();
        docs.add(jd.text());
        resumes.forEach(r -> docs.add(r.rawText()));
        List<Map<String, Double>> vectors = vectorizer.vectorize(docs);
        Map<String, Double> jdVec = vectors.get(0);

        List<CandidateScore> unranked = new ArrayList<>();
        for (int i = 0; i < resumes.size(); i++) {
            double cos = TfIdfVectorizer.cosine(jdVec, vectors.get(i + 1));
            unranked.add(score(i, jd, resumes.get(i), cos, weights, shortlistThreshold));
        }

        List<CandidateScore> sorted = new ArrayList<>(unranked);
        sorted.sort(Comparator.comparingInt(CandidateScore::overall).reversed()
                .thenComparing(Comparator.comparingInt(CandidateScore::skill).reversed())
                .thenComparingInt(CandidateScore::index));

        List<CandidateScore> ranked = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            CandidateScore s = sorted.get(i);
            ranked.add(new CandidateScore(s.index(), i + 1, s.resume(), s.overall(), s.skill(), s.semantic(),
                    s.experience(), s.education(), s.matchedSkills(), s.relatedSkills(), s.missingSkills(),
                    s.niceToHaveMatched(), s.extraSkills(), s.tier(), s.shortlisted(), s.explanation()));
        }
        return ranked;
    }

    private CandidateScore score(int index, JobDescription jd, ParsedResume r, double cosine, Weights w,
                                 int threshold) {
        List<String> explanation = new ArrayList<>();
        Set<String> candidate = new HashSet<>();
        r.skills().forEach(s -> candidate.add(s.toLowerCase(Locale.ROOT)));
        String text = SkillTaxonomy.normalize(r.rawText()).toLowerCase(Locale.ROOT);

        // --- skills
        List<String> matched = new ArrayList<>();
        Map<String, String> related = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        double credit = 0;
        for (String req : jd.requiredSkills()) {
            if (has(req, candidate, text)) {
                matched.add(req);
                credit += 1;
                continue;
            }
            String via = relatedVia(req, candidate);
            if (via != null) {
                related.put(req, via);
                credit += RELATED_CREDIT;
            } else {
                missing.add(req);
            }
        }
        List<String> niceMatched = new ArrayList<>();
        for (String n : jd.niceToHaveSkills()) {
            if (has(n, candidate, text)) {
                niceMatched.add(n);
            }
        }

        double semantic = Math.min(1.0, cosine / COSINE_FULL_SCORE);
        double skillScore;
        if (jd.requiredSkills().isEmpty()) {
            skillScore = semantic;
            explanation.add("No recognisable skills in the job description - skill score falls back to semantic similarity.");
        } else {
            double must = credit / jd.requiredSkills().size();
            if (jd.niceToHaveSkills().isEmpty()) {
                skillScore = must;
            } else {
                double nice = (double) niceMatched.size() / jd.niceToHaveSkills().size();
                skillScore = (1 - NICE_TO_HAVE_SHARE) * must + NICE_TO_HAVE_SHARE * nice;
            }
            explanation.add(String.format(Locale.ROOT, "%d of %d required skills matched directly%s.",
                    matched.size(), jd.requiredSkills().size(),
                    related.isEmpty() ? "" : ", " + related.size() + " more via related skills (50% credit)"));
        }

        // --- experience
        double expScore;
        double years = r.experienceYears();
        if (jd.requiredYears() > 0) {
            expScore = Math.min(1.0, years / jd.requiredYears());
            explanation.add(String.format(Locale.ROOT, "%.1f years of experience vs %.1f required.", years,
                    jd.requiredYears()));
        } else {
            expScore = Math.min(1.0, years / 2.0);
            explanation.add(String.format(Locale.ROOT,
                    "%.1f years of experience (no minimum stated; 2+ years scores 100%%).", years));
        }

        // --- education
        double eduScore;
        if (jd.requiredDegreeLevel() == 0) {
            eduScore = 1.0;
        } else if (r.degreeLevel() >= jd.requiredDegreeLevel()) {
            eduScore = 1.0;
            explanation.add("Education meets the " + jd.requiredDegreeLabel() + " requirement.");
        } else {
            eduScore = (double) r.degreeLevel() / jd.requiredDegreeLevel();
            explanation.add("Highest degree (" + r.degreeLabel() + ") is below the required "
                    + jd.requiredDegreeLabel() + ".");
        }

        double total = (w.skill() * skillScore + w.semantic() * semantic + w.experience() * expScore
                + w.education() * eduScore) / w.sum();
        int overall = (int) Math.round(total * 100);

        Set<String> reqLower = new HashSet<>();
        jd.requiredSkills().forEach(s -> reqLower.add(s.toLowerCase(Locale.ROOT)));
        jd.niceToHaveSkills().forEach(s -> reqLower.add(s.toLowerCase(Locale.ROOT)));
        List<String> extra = r.skills().stream().filter(s -> !reqLower.contains(s.toLowerCase(Locale.ROOT)))
                .limit(12).toList();

        return new CandidateScore(index, 0, r, overall, pct(skillScore), pct(semantic), pct(expScore),
                pct(eduScore), List.copyOf(matched), Map.copyOf(related), List.copyOf(missing),
                List.copyOf(niceMatched), extra, tier(overall), overall >= threshold, List.copyOf(explanation));
    }

    private boolean has(String skill, Set<String> candidate, String lowerText) {
        String key = skill.toLowerCase(Locale.ROOT);
        if (candidate.contains(key)) {
            return true;
        }
        if (taxonomy.canonicalize(skill) != null) {
            return false; // known skill: the taxonomy extraction is authoritative
        }
        // custom skill typed by the recruiter: plain whole-word search
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(key) + "(?![\\p{L}\\p{N}])")
                .matcher(lowerText).find();
    }

    private String relatedVia(String req, Set<String> candidate) {
        for (String rel : taxonomy.related(req)) {
            if (candidate.contains(rel.toLowerCase(Locale.ROOT))) {
                return rel;
            }
        }
        return null;
    }

    static int pct(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 100);
    }

    static String tier(int overall) {
        if (overall >= 75) {
            return "Strong";
        }
        if (overall >= 55) {
            return "Good";
        }
        if (overall >= 35) {
            return "Fair";
        }
        return "Weak";
    }
}
