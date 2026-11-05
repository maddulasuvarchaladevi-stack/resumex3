package com.resumescreener;

import com.resumescreener.model.CandidateScore;
import com.resumescreener.model.JobDescription;
import com.resumescreener.model.ParsedResume;
import com.resumescreener.model.Weights;
import com.resumescreener.nlp.ExperienceCalculator;
import com.resumescreener.nlp.SkillTaxonomy;
import com.resumescreener.service.DemoDataService;
import com.resumescreener.service.JobDescriptionParser;
import com.resumescreener.service.MatchingEngine;
import com.resumescreener.service.ResumeParserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ResumeScreenerTests {

    @Autowired SkillTaxonomy taxonomy;
    @Autowired ResumeParserService parser;
    @Autowired JobDescriptionParser jdParser;
    @Autowired MatchingEngine engine;
    @Autowired DemoDataService demo;
    @Autowired ExperienceCalculator experience;
    @Autowired MockMvc mvc;

    @Test
    void taxonomyMapsSynonymsToOneSkill() {
        assertEquals(java.util.Set.of("GCP"), taxonomy.extract("Deployed on Google Cloud Platform"));
        assertTrue(taxonomy.extract("C++ and C# developer, Node.js, JavaScript").containsAll(
                List.of("C++", "C#", "Node.js", "JavaScript")));
        assertFalse(taxonomy.extract("I write JavaScript").contains("Java"), "Java must not match inside JavaScript");
        assertFalse(taxonomy.extract("MySQL").contains("SQL"), "SQL must not match inside MySQL");
        assertTrue(taxonomy.related("GCP").contains("Cloud Computing"));
    }

    @Test
    void experienceMergesOverlappingRanges() {
        String text = "Engineer, Jan 2020 - Dec 2021\nConsultant 06/2021 - Present\nIntern 2015 - 2016";
        double years = experience.calculateYears(text);
        // 2015-2016 = 2.0 yrs, Jan 2020 -> now (overlap merged) is at least 5 yrs
        assertTrue(years > 7.0, "got " + years);
        assertEquals(2.0, experience.calculateYears("Dev, 2015 - 2016"));
        assertEquals(0.0, experience.calculateYears("no dates here"));
        assertEquals(5.0, experience.calculateYears("I have 5+ years of professional experience"));
    }

    @Test
    void parsesEntitiesFromSampleResume() throws Exception {
        ParsedResume r = parser.parse("priya_sharma.txt", demo.resumes().get("priya_sharma.txt"), List.of());
        assertEquals("Priya Sharma", r.name());
        assertEquals("priya.sharma@example.com", r.email());
        assertNotNull(r.phone());
        assertTrue(r.skills().containsAll(List.of("Java", "Spring Boot", "Kafka", "Docker", "Kubernetes")));
        assertTrue(r.experienceYears() >= 8.0, "years " + r.experienceYears());
        assertEquals(2, r.degreeLevel());
        assertFalse(r.organizations().isEmpty());
        assertFalse(r.institutions().isEmpty());
        assertFalse(r.jobTitles().isEmpty());
        assertFalse(r.certifications().isEmpty());
    }

    @Test
    void rankingPutsRelevantCandidatesFirstAndUnrelatedLast() throws Exception {
        List<ParsedResume> resumes = new ArrayList<>();
        for (Map.Entry<String, String> e : demo.resumes().entrySet()) {
            resumes.add(parser.parse(e.getKey(), e.getValue(), List.of()));
        }
        JobDescription jd = jdParser.parse(demo.jobTitle(), demo.jobDescription(), demo.mustHaveSkills(), null);
        assertEquals(5.0, jd.requiredYears());
        assertEquals(2, jd.requiredDegreeLevel());

        List<CandidateScore> ranked = engine.rank(jd, resumes, Weights.DEFAULT, 50);
        assertEquals(5, ranked.size());
        assertEquals(java.util.Set.of("priya_sharma.txt", "david_miller.txt"),
                java.util.Set.of(ranked.get(0).resume().fileName(), ranked.get(1).resume().fileName()),
                "the two senior Java candidates must lead the ranking");
        assertEquals("sara_khan.txt", ranked.get(4).resume().fileName());
        assertTrue(ranked.get(0).overall() > ranked.get(4).overall() + 30);
        assertTrue(ranked.get(0).shortlisted());
        assertFalse(ranked.get(4).shortlisted());
        for (int i = 1; i < ranked.size(); i++) {
            assertTrue(ranked.get(i - 1).overall() >= ranked.get(i).overall());
            assertEquals(i + 1, ranked.get(i).rank());
        }
        // a candidate with Spring but not Spring Boot gets related credit
        CandidateScore rahul = ranked.stream().filter(c -> c.resume().fileName().equals("rahul_verma.txt"))
                .findFirst().orElseThrow();
        assertTrue(rahul.relatedSkills().containsKey("Spring Boot"));
    }

    @Test
    void weightsAreNormalised() {
        assertEquals(Weights.DEFAULT, new Weights(0, 0, 0, 0));
        assertEquals(100, new Weights(500, 0, 0, 0).skill());
    }

    @Test
    void endToEndDemoFlowRendersPages() throws Exception {
        MvcResult res = mvc.perform(post("/demo")).andExpect(status().is3xxRedirection()).andReturn();
        String location = res.getResponse().getRedirectedUrl();
        assertNotNull(location);

        String html = mvc.perform(get(location)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertTrue(html.contains("Priya Sharma"));
        assertTrue(html.contains("Senior Java Backend Engineer"));
        assertFalse(html.toLowerCase().contains("<script"), "pages must not contain JavaScript");

        mvc.perform(get(location + "/candidate/0")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Score breakdown")));
        mvc.perform(get(location + "/export.csv")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("priya_sharma.txt")));
        mvc.perform(get(location + "?view=shortlisted&t=90")).andExpect(status().isOk());
        mvc.perform(get("/results/does-not-exist")).andExpect(status().is3xxRedirection());
    }

    @Test
    void uploadFlowAcceptsTxtAndRejectsEmptyAndHtmlIsEscaped() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "<b>evil</b>.txt", "text/plain",
                "Jane Doe\njane@example.com\nSKILLS\nJava, Spring Boot, Docker\n".getBytes(StandardCharsets.UTF_8));
        MvcResult res = mvc.perform(multipart("/screen").file(file)
                        .param("jobTitle", "Java Dev").param("jobDescription", "We need Java and Docker."))
                .andExpect(status().is3xxRedirection()).andReturn();
        String html = mvc.perform(get(res.getResponse().getRedirectedUrl())).andReturn().getResponse()
                .getContentAsString();
        assertFalse(html.contains("<b>evil</b>"), "file names must be HTML-escaped");

        mvc.perform(multipart("/screen").param("jobTitle", "x").param("jobDescription", "Java"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Upload at least one resume")));
    }
}
