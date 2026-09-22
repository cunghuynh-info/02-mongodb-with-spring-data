package vn.infodation.mongodb.analytics.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.analytics.domain.Transfer;
import vn.infodation.mongodb.analytics.service.BulkWriteService;
import vn.infodation.mongodb.analytics.service.RetryingTransferService;
import vn.infodation.mongodb.analytics.service.TransferService;
import vn.infodation.mongodb.common.async.AsyncJobRegistry;
import vn.infodation.mongodb.common.async.AsyncJobStatus;

/** Phases 5 and 6. */
@Tag(name = "5-6 - Bulk and transactions", description = "Bulk write modes and money transfers. Reads need ANALYST, writes need ADMIN.")
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final BulkWriteService bulkWriteService;
    private final TransferService transferService;
    private final RetryingTransferService retryingTransferService;
    private final AsyncJobRegistry jobRegistry;

    // ---- Phase 5 -----------------------------------------------------------------------

    @PostMapping("/bulk/raise-limits")
    public Map<String, Object> raiseLimits(@RequestBody List<Integer> accountIds,
                                           @RequestParam(defaultValue = "1000") int delta) {
        return bulkWriteService.raiseLimits(accountIds, delta);
    }

    @PostMapping("/bulk/upsert")
    public Map<String, Object> upsert(@RequestBody List<Integer> accountIds,
                                      @RequestParam(defaultValue = "100.00") BigDecimal openingBalance) {
        return bulkWriteService.upsertAccounts(accountIds, openingBalance);
    }

    /** Phase 5.2 - run it and compare the two rows. */
    @GetMapping("/bulk/duplicate-key")
    public Map<String, Object> duplicateKey(@RequestParam(defaultValue = "dup@example.com") String email) {
        return bulkWriteService.duplicateKeyBehaviour(email);
    }

    /** Phase 5.3 */
    @GetMapping("/bulk/benchmark")
    public Map<String, Object> benchmark(@RequestParam(defaultValue = "5000") int total,
                                         @RequestParam(defaultValue = "1000") int batchSize) {
        return bulkWriteService.benchmark(total, batchSize);
    }

    /** Phase 5.6 */
    @GetMapping("/bulk/stream-to-bulk")
    public Map<String, Object> streamToBulk(@RequestParam(defaultValue = "1000") int batchSize) {
        return bulkWriteService.denormaliseCommentAuthors(batchSize);
    }

    // ---- Phase 6 -----------------------------------------------------------------------

    @PostMapping("/transfer")
    public Transfer transfer(@RequestBody TransferRequest request) {
        return retryingTransferService.transfer(
                request.from(), request.to(), request.amount(), request.idempotencyKey());
    }

    /** Phase 6.2 - always returns 500; the point is what the balances look like afterwards. */
    @PostMapping("/transfer/rollback-demo")
    public void rollbackDemo(@RequestBody TransferRequest request) {
        transferService.transferThenFail(request.from(), request.to(), request.amount());
    }

    @GetMapping("/balances")
    public Map<String, Object> balances(@RequestParam int from, @RequestParam int to) {
        return Map.of(
                "from", Map.of("accountId", from, "balance", transferService.balanceOf(from)),
                "to", Map.of("accountId", to, "balance", transferService.balanceOf(to)),
                "totalAcrossAllAccounts", transferService.totalBalance());
    }

    @GetMapping("/transfers")
    public List<Transfer> transfers() {
        return transferService.transfers();
    }

    public record TransferRequest(int from, int to, BigDecimal amount, String idempotencyKey) {
    }

    // ---- Phase 9 -------------------------------------------------------------------------

    /**
     * Phase 9.4 - the same benchmark as {@link #benchmark}, started in the background. Returns
     * as soon as the job is registered; poll {@link #job} with the returned id for the result.
     */
    @PostMapping("/bulk/benchmark/async")
    public ResponseEntity<Map<String, Object>> benchmarkAsync(@RequestParam(defaultValue = "5000") int total,
                                                               @RequestParam(defaultValue = "1000") int batchSize) {
        String jobId = jobRegistry.start();
        bulkWriteService.runBenchmarkAsync(jobId, total, batchSize);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    /** Phase 9.4 - {@link #streamToBulk}, started in the background the same way. */
    @PostMapping("/bulk/stream-to-bulk/async")
    public ResponseEntity<Map<String, Object>> streamToBulkAsync(@RequestParam(defaultValue = "1000") int batchSize) {
        String jobId = jobRegistry.start();
        bulkWriteService.runDenormaliseCommentAuthorsAsync(jobId, batchSize);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    @GetMapping("/jobs/{jobId}")
    public AsyncJobStatus job(@PathVariable String jobId) {
        return jobRegistry.require(jobId);
    }
}
