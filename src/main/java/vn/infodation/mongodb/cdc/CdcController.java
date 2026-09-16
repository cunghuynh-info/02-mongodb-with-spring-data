package vn.infodation.mongodb.cdc;

import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** Phase 7. Start the listener, write to accounts elsewhere, then read the events back. */
@RestController
@RequestMapping("/api/cdc")
@RequiredArgsConstructor
public class CdcController {

    private final AccountChangeListener listener;
    private final ResumeTokenStore tokenStore;

    @PostMapping("/start")
    public Map<String, Object> start(@RequestParam(defaultValue = "true") boolean resume) {
        listener.start(resume);
        return status();
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        listener.stop();
        return status();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "running", listener.isRunning(),
                "eventCount", listener.eventCount(),
                "hasResumeToken", tokenStore.load(AccountChangeListener.LISTENER_ID).isPresent());
    }

    @GetMapping("/events")
    public List<Document> events() {
        return listener.observed();
    }

    /** Phase 7.4 - before-images are off by default and must be enabled per collection. */
    @PostMapping("/enable-pre-images")
    public Map<String, Object> enablePreImages() {
        listener.enablePreAndPostImages("accounts");
        return Map.of("enabled", true, "collection", "accounts");
    }

    /** Phase 7.5 - drop the token to prove a restart without one loses everything in between. */
    @DeleteMapping("/resume-token")
    public Map<String, Object> clearToken() {
        tokenStore.clear(AccountChangeListener.LISTENER_ID);
        listener.reset();
        return status();
    }
}
