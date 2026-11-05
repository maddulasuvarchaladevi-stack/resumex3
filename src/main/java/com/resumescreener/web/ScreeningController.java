package com.resumescreener.web;

import com.resumescreener.model.CandidateScore;
import com.resumescreener.model.JobDescription;
import com.resumescreener.model.ParsedResume;
import com.resumescreener.model.Weights;
import com.resumescreener.service.*;
import com.resumescreener.service.ScreeningStore.ScreeningSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Controller
public class ScreeningController {

    private static final Logger log = LoggerFactory.getLogger(ScreeningController.class);
    private static final int MAX_FILES = 100;
    private static final int MAX_JD_CHARS = 50_000;

    private final TextExtractionService extraction;
    private final ResumeParserService parser;
    private final JobDescriptionParser jdParser;
    private final MatchingEngine engine;
    private final ScreeningStore store;
    private final DemoDataService demo;

    public ScreeningController(TextExtractionService extraction, ResumeParserService parser,
                               JobDescriptionParser jdParser, MatchingEngine engine, ScreeningStore store,
                               DemoDataService demo) {
        this.extraction = extraction;
        this.parser = parser;
        this.jdParser = jdParser;
        this.engine = engine;
        this.store = store;
        this.demo = demo;
    }

    // ------------------------------------------------------------------ upload form

    @GetMapping("/")
    public String index(Model model, @RequestParam(required = false) String expired) {
        form(model, "", "", "", null, Weights.DEFAULT, 50);
        if (expired != null) {
            model.addAttribute("error", "That screening session has expired or was deleted. Start a new one.");
        }
        return "index";
    }

    @PostMapping("/screen")
    public String screen(@RequestParam String jobTitle,
                         @RequestParam String jobDescription,
                         @RequestParam(defaultValue = "") String mustHave,
                         @RequestParam(required = false) Double minYears,
                         @RequestParam(defaultValue = "40") int ws,
                         @RequestParam(defaultValue = "25") int wm,
                         @RequestParam(defaultValue = "20") int we,
                         @RequestParam(defaultValue = "15") int wd,
                         @RequestParam(defaultValue = "50") int threshold,
                         @RequestParam(name = "files", required = false) List<MultipartFile> files,
                         Model model) {
        Weights weights = new Weights(ws, wm, we, wd);
        int thr = Math.max(0, Math.min(100, threshold));

        String error = null;
        if (jobDescription.isBlank()) {
            error = "Please paste the job description.";
        } else if (jobDescription.length() > MAX_JD_CHARS) {
            error = "The job description is too long.";
        }
        List<MultipartFile> uploads = files == null ? List.of()
                : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (error == null && uploads.isEmpty()) {
            error = "Upload at least one resume (PDF, DOCX or TXT).";
        } else if (error == null && uploads.size() > MAX_FILES) {
            error = "Please upload at most " + MAX_FILES + " resumes at a time.";
        }

        List<ParsedResume> resumes = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (error == null) {
            for (MultipartFile f : uploads) {
                String name = safeName(f.getOriginalFilename());
                try {
                    TextExtractionService.Extraction ex = extraction.extract(name, f.getBytes());
                    resumes.add(parser.parse(name, ex.text(), ex.warnings()));
                } catch (IOException | RuntimeException e) {
                    log.warn("Could not parse {}: {}", name, e.toString());
                    skipped.add(name + " - " + (e instanceof IOException ? e.getMessage() : "unreadable file"));
                }
            }
            if (resumes.isEmpty()) {
                error = "None of the uploaded files could be read: " + String.join("; ", skipped);
            }
        }
        if (error != null) {
            form(model, jobTitle, jobDescription, mustHave, minYears, weights, thr);
            model.addAttribute("error", error);
            return "index";
        }

        JobDescription job = jdParser.parse(jobTitle, jobDescription, mustHave, minYears);
        ScreeningSession session = store.create(job, resumes, weights, thr);
        return "redirect:/results/" + session.id() + (skipped.isEmpty() ? "" : "?skipped=" + skipped.size());
    }

    @PostMapping("/demo")
    public String demo() throws IOException {
        List<ParsedResume> resumes = new ArrayList<>();
        for (Map.Entry<String, String> e : demo.resumes().entrySet()) {
            resumes.add(parser.parse(e.getKey(), e.getValue(), List.of()));
        }
        JobDescription job = jdParser.parse(demo.jobTitle(), demo.jobDescription(), demo.mustHaveSkills(), null);
        ScreeningSession session = store.create(job, resumes, Weights.DEFAULT, 50);
        return "redirect:/results/" + session.id();
    }

    // ------------------------------------------------------------------ dashboard

    @GetMapping("/results/{id}")
    public String results(@PathVariable String id,
                          @RequestParam(required = false) Integer ws,
                          @RequestParam(required = false) Integer wm,
                          @RequestParam(required = false) Integer we,
                          @RequestParam(required = false) Integer wd,
                          @RequestParam(required = false) Integer t,
                          @RequestParam(defaultValue = "all") String view,
                          @RequestParam(required = false) Integer skipped,
                          Model model) {
        Optional<ScreeningSession> opt = store.get(id);
        if (opt.isEmpty()) {
            return "redirect:/?expired=1";
        }
        ScreeningSession s = opt.get();
        Weights weights = resolveWeights(s, ws, wm, we, wd);
        int threshold = t == null ? s.threshold() : Math.max(0, Math.min(100, t));
        List<CandidateScore> all = engine.rank(s.job(), s.resumes(), weights, threshold);
        boolean onlyShortlisted = "shortlisted".equals(view);
        List<CandidateScore> shown = onlyShortlisted ? all.stream().filter(CandidateScore::shortlisted).toList() : all;

        model.addAttribute("id", id);
        model.addAttribute("job", s.job());
        model.addAttribute("scores", shown);
        model.addAttribute("total", all.size());
        model.addAttribute("shortlistedCount", all.stream().filter(CandidateScore::shortlisted).count());
        model.addAttribute("average", (int) Math.round(all.stream().mapToInt(CandidateScore::overall).average().orElse(0)));
        model.addAttribute("weights", weights);
        model.addAttribute("threshold", threshold);
        model.addAttribute("view", onlyShortlisted ? "shortlisted" : "all");
        model.addAttribute("skipped", skipped);
        return "results";
    }

    @GetMapping("/results/{id}/candidate/{index}")
    public String candidate(@PathVariable String id, @PathVariable int index,
                            @RequestParam(required = false) Integer ws,
                            @RequestParam(required = false) Integer wm,
                            @RequestParam(required = false) Integer we,
                            @RequestParam(required = false) Integer wd,
                            @RequestParam(required = false) Integer t,
                            Model model) {
        Optional<ScreeningSession> opt = store.get(id);
        if (opt.isEmpty()) {
            return "redirect:/?expired=1";
        }
        ScreeningSession s = opt.get();
        Weights weights = resolveWeights(s, ws, wm, we, wd);
        int threshold = t == null ? s.threshold() : Math.max(0, Math.min(100, t));
        Optional<CandidateScore> score = engine.rank(s.job(), s.resumes(), weights, threshold).stream()
                .filter(c -> c.index() == index).findFirst();
        if (score.isEmpty()) {
            return "redirect:/results/" + id;
        }
        model.addAttribute("id", id);
        model.addAttribute("job", s.job());
        model.addAttribute("c", score.get());
        model.addAttribute("weights", weights);
        model.addAttribute("threshold", threshold);
        return "candidate";
    }

    @GetMapping("/results/{id}/export.csv")
    public ResponseEntity<byte[]> export(@PathVariable String id,
                                         @RequestParam(required = false) Integer ws,
                                         @RequestParam(required = false) Integer wm,
                                         @RequestParam(required = false) Integer we,
                                         @RequestParam(required = false) Integer wd,
                                         @RequestParam(required = false) Integer t) {
        Optional<ScreeningSession> opt = store.get(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ScreeningSession s = opt.get();
        Weights weights = resolveWeights(s, ws, wm, we, wd);
        int threshold = t == null ? s.threshold() : Math.max(0, Math.min(100, t));
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("Rank,Name,Email,Phone,File,Overall %,Skills %,Semantic %,Experience %,Education %,")
                .append("Experience (yrs),Degree,Tier,Shortlisted,Matched skills,Missing skills\n");
        for (CandidateScore c : engine.rank(s.job(), s.resumes(), weights, threshold)) {
            ParsedResume r = c.resume();
            sb.append(csv(String.valueOf(c.rank()), r.name(), r.email(), r.phone(), r.fileName(),
                    String.valueOf(c.overall()), String.valueOf(c.skill()), String.valueOf(c.semantic()),
                    String.valueOf(c.experience()), String.valueOf(c.education()),
                    String.valueOf(r.experienceYears()), r.degreeLabel(), c.tier(),
                    c.shortlisted() ? "Yes" : "No", String.join("; ", c.matchedSkills()),
                    String.join("; ", c.missingSkills()))).append('\n');
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("candidate-ranking.csv").build().toString())
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/results/{id}/delete")
    public String delete(@PathVariable String id) {
        store.delete(id);
        return "redirect:/";
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooLarge(Model model) {
        form(model, "", "", "", null, Weights.DEFAULT, 50);
        model.addAttribute("error", "Upload too large: each file may be up to 10 MB (100 MB in total).");
        return "index";
    }

    // ------------------------------------------------------------------ helpers

    private static void form(Model model, String title, String jd, String mustHave, Double minYears,
                             Weights weights, int threshold) {
        model.addAttribute("jobTitle", title);
        model.addAttribute("jobDescription", jd);
        model.addAttribute("mustHave", mustHave);
        model.addAttribute("minYears", minYears);
        model.addAttribute("weights", weights);
        model.addAttribute("threshold", threshold);
    }

    private static Weights resolveWeights(ScreeningSession s, Integer ws, Integer wm, Integer we, Integer wd) {
        Weights d = s.weights();
        return new Weights(ws == null ? d.skill() : ws, wm == null ? d.semantic() : wm,
                we == null ? d.experience() : we, wd == null ? d.education() : wd);
    }

    private static String safeName(String original) {
        String n = original == null ? "resume" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).strip();
        return n.isEmpty() ? "resume" : n;
    }

    /** CSV-escape and neutralise spreadsheet formula injection. */
    static String csv(String... values) {
        StringJoiner j = new StringJoiner(",");
        for (String v : values) {
            String s = v == null ? "" : v;
            if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
                s = "'" + s;
            }
            j.add('"' + s.replace("\"", "\"\"") + '"');
        }
        return j.toString();
    }
}
