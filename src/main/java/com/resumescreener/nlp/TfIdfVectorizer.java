package com.resumescreener.nlp;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sparse TF-IDF vectoriser over the corpus {job description + all resumes}.
 * Skill synonyms are collapsed to one canonical token first, so "GCP" and "Google Cloud Platform"
 * land on the same dimension (a lightweight stand-in for dense semantic embeddings).
 */
@Component
public class TfIdfVectorizer {

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9_]{2,}");
    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "that", "this", "from", "are", "was",
            "were", "have", "has", "had", "you", "your", "our", "will", "can", "all", "any", "not", "but", "per",
            "etc", "using", "used", "use", "such", "into", "over", "also", "their", "they", "them", "its", "who",
            "work", "working", "team", "teams", "experience", "years", "year", "ability", "strong", "good",
            "looking", "role", "join", "company", "responsibilities", "requirements", "candidate");

    private final SkillTaxonomy taxonomy;

    public TfIdfVectorizer(SkillTaxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    List<String> tokenize(String text) {
        String annotated = taxonomy.annotate(text == null ? "" : text).toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(annotated);
        while (m.find()) {
            String t = m.group();
            if (!STOP.contains(t) && !t.matches("\\d+")) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /** Returns L2-normalised TF-IDF vectors, one per document, in input order. */
    public List<Map<String, Double>> vectorize(List<String> docs) {
        List<List<String>> tokenized = docs.stream().map(this::tokenize).toList();
        Map<String, Integer> df = new HashMap<>();
        for (List<String> toks : tokenized) {
            for (String t : new HashSet<>(toks)) {
                df.merge(t, 1, Integer::sum);
            }
        }
        int n = docs.size();
        List<Map<String, Double>> vectors = new ArrayList<>();
        for (List<String> toks : tokenized) {
            Map<String, Integer> tf = new HashMap<>();
            toks.forEach(t -> tf.merge(t, 1, Integer::sum));
            Map<String, Double> vec = new HashMap<>();
            double norm = 0;
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                double idf = Math.log((1.0 + n) / (1.0 + df.get(e.getKey()))) + 1.0;
                double w = (1.0 + Math.log(e.getValue())) * idf;
                vec.put(e.getKey(), w);
                norm += w * w;
            }
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (Map.Entry<String, Double> e : vec.entrySet()) {
                    e.setValue(e.getValue() / norm);
                }
            }
            vectors.add(vec);
        }
        return vectors;
    }

    /** Cosine similarity of two (already normalised) sparse vectors. */
    public static double cosine(Map<String, Double> a, Map<String, Double> b) {
        Map<String, Double> small = a.size() <= b.size() ? a : b;
        Map<String, Double> large = small == a ? b : a;
        double dot = 0;
        for (Map.Entry<String, Double> e : small.entrySet()) {
            Double o = large.get(e.getKey());
            if (o != null) {
                dot += e.getValue() * o;
            }
        }
        return Math.max(0, Math.min(1, dot));
    }
}
