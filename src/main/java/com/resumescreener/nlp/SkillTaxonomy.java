package com.resumescreener.nlp;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Skills ontology: maps aliases / abbreviations ("GCP", "Google Cloud Platform") to a canonical
 * skill and knows which skills are related ("Cloud Architecture" ~ "GCP").
 */
@Component
public class SkillTaxonomy {

    public record Skill(String name, String category, Set<String> related) {
    }

    private final Map<String, Skill> byKey = new LinkedHashMap<>();
    private final Map<String, String> aliasToKey = new HashMap<>();
    private Pattern pattern;

    public SkillTaxonomy() {
    }

    @PostConstruct
    public void load() throws IOException {
        load(new ClassPathResource("skills.txt"));
    }

    void load(ClassPathResource resource) throws IOException {
        Map<String, List<String>> relatedRaw = new HashMap<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\|", -1);
                if (parts.length < 3) {
                    throw new IllegalStateException("Bad taxonomy line: " + line);
                }
                String name = parts[0].strip();
                String category = parts[1].strip();
                String key = name.toLowerCase(Locale.ROOT);
                byKey.put(key, new Skill(name, category, new LinkedHashSet<>()));
                aliasToKey.putIfAbsent(key, key);
                for (String alias : parts[2].split(",")) {
                    String a = alias.strip().toLowerCase(Locale.ROOT);
                    if (!a.isEmpty()) {
                        aliasToKey.putIfAbsent(a, key);
                    }
                }
                if (parts.length > 3) {
                    relatedRaw.put(key, Arrays.stream(parts[3].split(","))
                            .map(s -> s.strip().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList());
                }
            }
        }
        // make relations symmetric and validate them
        relatedRaw.forEach((key, rels) -> {
            for (String rel : rels) {
                Skill other = byKey.get(rel);
                if (other == null) {
                    throw new IllegalStateException("Unknown related skill '" + rel + "' for '" + key + "'");
                }
                byKey.get(key).related().add(other.name());
                other.related().add(byKey.get(key).name());
            }
        });
        List<String> aliases = new ArrayList<>(aliasToKey.keySet());
        aliases.sort(Comparator.comparingInt(String::length).reversed());
        String alternation = aliases.stream().map(Pattern::quote)
                .collect(Collectors.joining("|"));
        pattern = Pattern.compile("(?<![\\p{L}\\p{N}+#])(" + alternation + ")(?![\\p{L}\\p{N}+#])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Collapse all whitespace so multi-word aliases match across line breaks. */
    public static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\u00A0]+", " ");
    }

    /** All canonical skills mentioned in the text, in order of first appearance. */
    public Set<String> extract(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = pattern.matcher(normalize(text));
        while (m.find()) {
            String key = aliasToKey.get(m.group(1).toLowerCase(Locale.ROOT));
            if (key != null) {
                found.add(byKey.get(key).name());
            }
        }
        return found;
    }

    /** Canonical skill name for a free-form term, or {@code null} if unknown. */
    public String canonicalize(String term) {
        if (term == null) {
            return null;
        }
        String key = aliasToKey.get(term.strip().toLowerCase(Locale.ROOT));
        return key == null ? null : byKey.get(key).name();
    }

    public Set<String> related(String canonicalName) {
        Skill s = canonicalName == null ? null : byKey.get(canonicalName.toLowerCase(Locale.ROOT));
        return s == null ? Set.of() : s.related();
    }

    public String category(String canonicalName) {
        Skill s = canonicalName == null ? null : byKey.get(canonicalName.toLowerCase(Locale.ROOT));
        return s == null ? "Other" : s.category();
    }

    public int size() {
        return byKey.size();
    }

    /**
     * Replace every skill mention by a single canonical token (e.g. {@code sk_google_cloud_platform}
     * becomes {@code sk_gcp}) so that synonyms share one dimension in the vector space.
     */
    public String annotate(String text) {
        return pattern.matcher(normalize(text)).replaceAll(r -> {
            String key = aliasToKey.get(r.group(1).toLowerCase(Locale.ROOT));
            return " sk_" + key.replaceAll("[^a-z0-9]+", "_") + " ";
        });
    }
}
