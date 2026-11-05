package com.resumescreener.model;

/**
 * Relative importance of each scoring component.
 * Total = w1*Skill + w2*Semantic + w3*Experience + w4*Education (weights normalised to sum to 1).
 */
public record Weights(int skill, int semantic, int experience, int education) {

    public static final Weights DEFAULT = new Weights(40, 25, 20, 15);

    public Weights {
        skill = clamp(skill);
        semantic = clamp(semantic);
        experience = clamp(experience);
        education = clamp(education);
        if (skill + semantic + experience + education == 0) {
            skill = DEFAULT.skill;
            semantic = DEFAULT.semantic;
            experience = DEFAULT.experience;
            education = DEFAULT.education;
        }
    }

    public double sum() {
        return skill + semantic + experience + education;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }
}
