package com.resumescreener.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bundled demo job description and resumes so the app can be tried without uploading files. */
@Service
public class DemoDataService {

    private static final List<String> RESUMES = List.of("priya_sharma.txt", "rahul_verma.txt", "ananya_iyer.txt",
            "david_miller.txt", "sara_khan.txt");

    public String jobTitle() {
        return "Senior Java Backend Engineer";
    }

    public String jobDescription() throws IOException {
        return read("samples/job_description.txt");
    }

    public String mustHaveSkills() {
        return "Java, Spring Boot, REST APIs, SQL, Docker, Cloud Computing, Microservices";
    }

    /** file name -> content */
    public Map<String, String> resumes() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String f : RESUMES) {
            out.put(f, read("samples/" + f));
        }
        return out;
    }

    private static String read(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
