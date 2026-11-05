package com.resumescreener.service;

import com.resumescreener.model.JobDescription;
import com.resumescreener.model.ParsedResume;
import com.resumescreener.model.Weights;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory store of screening sessions (resumes are never written to disk). */
@Component
public class ScreeningStore {

    public record ScreeningSession(String id, JobDescription job, List<ParsedResume> resumes, Weights weights,
                                   int threshold, Instant createdAt) {
    }

    private static final int MAX_SESSIONS = 50;

    private final Map<String, ScreeningSession> sessions = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ScreeningSession> eldest) {
            return size() > MAX_SESSIONS;
        }
    };

    public synchronized ScreeningSession create(JobDescription job, List<ParsedResume> resumes, Weights weights,
                                                int threshold) {
        ScreeningSession s = new ScreeningSession(UUID.randomUUID().toString(), job, List.copyOf(resumes), weights,
                threshold, Instant.now());
        sessions.put(s.id(), s);
        return s;
    }

    public synchronized Optional<ScreeningSession> get(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public synchronized void delete(String id) {
        sessions.remove(id);
    }
}
