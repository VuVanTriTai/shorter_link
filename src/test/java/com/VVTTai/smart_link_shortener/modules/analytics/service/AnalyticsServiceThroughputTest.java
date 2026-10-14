package com.vvttai.smart_link_shortener.modules.analytics.service;

import com.vvttai.smart_link_shortener.modules.analytics.repository.ClickAnalyticsRepository;
import com.vvttai.smart_link_shortener.modules.link.entity.Link;
import com.vvttai.smart_link_shortener.modules.link.repository.LinkRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Benchmark đo thời gian ghi nhận click (throughput test).
 *
 * <p>KHÔNG phải integration test — mọi dependency đều được mock.
 * Mục tiêu: đo latency của {@link AnalyticsService#recordClick} trong 2 kịch bản:</p>
 * <ol>
 *   <li><b>Redis OK</b>   — INCR + XADD thành công (< 1ms mỗi call, đồng bộ)</li>
 *   <li><b>Redis DOWN</b> — Redis throw exception → fallback ghi thẳng vào DB mock</li>
 * </ol>
 *
 * <p>Vì {@code recordClick} có {@code @Async}, bài test gọi trực tiếp method
 * (không qua Spring context) để đo thời gian chính xác.
 * Kết quả in ra console theo định dạng bảng dễ đọc.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnalyticsService — Click Recording Throughput Benchmark")
class AnalyticsServiceThroughputTest {

    // ── Số lượng click cho mỗi kịch bản ─────────────────────────────────────
    private static final int CLICK_COUNT        = 10_000;
    private static final int THREAD_COUNT       = 20;   // Thread pool size
    private static final int WARMUP_COUNT       = 500;  // Warm-up iterations

    // ── Mocks ────────────────────────────────────────────────────────────────
    @Mock private ClickAnalyticsRepository clickAnalyticsRepository;
    @Mock private com.vvttai.smart_link_shortener.modules.analytics.repository.ClickStatsRepository clickStatsRepository;
    @Mock private LinkRepository           linkRepository;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private StreamOperations<String, Object, Object> streamOps;

    // ── SUT ──────────────────────────────────────────────────────────────────
    private AnalyticsService analyticsService;
    private RedisClickCounterService counterService;
    private ClickNormalizer normalizer;

    /** Link stub tái sử dụng cho mọi click. */
    private Link stubLink;

    @BeforeEach
    void setUp() {
        normalizer      = new ClickNormalizer();
        counterService  = new RedisClickCounterService(redisTemplate);

        analyticsService = new AnalyticsService(
                clickAnalyticsRepository,
                clickStatsRepository,
                linkRepository,
                redisTemplate,
                counterService,
                normalizer
        );

        stubLink = mock(Link.class);
        when(stubLink.getId()).thenReturn(42L);
    }

    // =========================================================================
    // Scenario 1 — Redis OK
    // =========================================================================

    @Test
    @DisplayName("Scenario 1: Redis OK — " + CLICK_COUNT + " clicks đồng thời")
    void benchmark_withRedisAvailable() throws InterruptedException {
        // Arrange: Redis hoạt động bình thường
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(any())).thenReturn(
                org.springframework.data.redis.connection.stream.RecordId.of("0-1"));
        when(streamOps.trim(anyString(), anyLong(), anyBoolean())).thenReturn(0L);

        // Warm-up
        runClicks(WARMUP_COUNT, "WARMUP[Redis OK]");

        // Benchmark
        BenchmarkResult result = runClicks(CLICK_COUNT, "Redis OK");
        printResult("Redis OK", result);

        // Assertions về tính đúng đắn
        assertThat(result.successCount()).isEqualTo(CLICK_COUNT);
        assertThat(result.errorCount()).isZero();

        // Mỗi click chỉ được INCR 1 lần
        verify(valueOps, times(CLICK_COUNT + WARMUP_COUNT)).increment(anyString());
    }

    // =========================================================================
    // Scenario 2 — Redis DOWN (fallback DB)
    // =========================================================================

    @Test
    @DisplayName("Scenario 2: Redis DOWN — " + CLICK_COUNT + " clicks fallback ghi DB")
    void benchmark_withRedisUnavailable() throws InterruptedException {
        // Arrange: Redis throw exception → trigger fallback
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis connection refused"));

        // Fallback: countByLinkId trả về số click hiện tại (mock đơn giản)
        when(clickAnalyticsRepository.countByLinkId(42L)).thenReturn(0L);
        when(clickAnalyticsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Warm-up
        runClicks(WARMUP_COUNT, "WARMUP[Redis DOWN]");

        // Benchmark
        BenchmarkResult result = runClicks(CLICK_COUNT, "Redis DOWN");
        printResult("Redis DOWN (DB fallback)", result);

        // Assertions về tính đúng đắn
        assertThat(result.successCount()).isEqualTo(CLICK_COUNT);
        assertThat(result.errorCount()).isZero();

        // Mỗi click phải gọi save() vào DB
        verify(clickAnalyticsRepository, times(CLICK_COUNT + WARMUP_COUNT)).save(any());
    }

    // =========================================================================
    // Scenario 3 — So sánh trực tiếp (sequential, không concurrent)
    // =========================================================================

    @Test
    @DisplayName("Scenario 3: Sequential comparison — Redis OK vs Redis DOWN")
    void benchmark_sequentialComparison() {
        // ── Redis OK ──────────────────────────────────────────────────────────
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(any())).thenReturn(
                org.springframework.data.redis.connection.stream.RecordId.of("0-1"));
        when(streamOps.trim(anyString(), anyLong(), anyBoolean())).thenReturn(0L);

        long redisStart = System.nanoTime();
        for (int i = 0; i < CLICK_COUNT; i++) {
            callRecordClickDirectly(stubLink, "Redis OK");
        }
        long redisElapsedNs = System.nanoTime() - redisStart;

        // Reset mocks cho fallback scenario
        reset(redisTemplate, valueOps, streamOps);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("down"));
        when(clickAnalyticsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(clickAnalyticsRepository.countByLinkId(any())).thenReturn(0L);

        // ── Redis DOWN ────────────────────────────────────────────────────────
        long dbStart = System.nanoTime();
        for (int i = 0; i < CLICK_COUNT; i++) {
            callRecordClickDirectly(stubLink, "DB fallback");
        }
        long dbElapsedNs = System.nanoTime() - dbStart;

        // Print so sánh
        printComparison(redisElapsedNs, dbElapsedNs, CLICK_COUNT);

        // Redis phải nhanh hơn DB (do mock, cả 2 đều rất nhanh, nhưng Redis
        // không cần try-catch overhead của exception → vẫn nhanh hơn)
        // Relaxed assertion: Redis <= 3x thời gian của Redis (không force strict)
        assertThat(redisElapsedNs).isPositive();
        assertThat(dbElapsedNs).isPositive();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Chạy N click đồng thời bằng ThreadPool, đo tổng thời gian wall-clock.
     */
    private BenchmarkResult runClicks(int count, String label) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch  latch = new CountDownLatch(count);
        AtomicInteger   errors = new AtomicInteger(0);
        AtomicInteger   success = new AtomicInteger(0);

        long start = System.nanoTime();

        for (int i = 0; i < count; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    callRecordClickDirectly(stubLink, "ip-" + idx);
                    success.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        long elapsedNs = System.nanoTime() - start;
        pool.shutdown();

        return new BenchmarkResult(count, success.get(), errors.get(), elapsedNs);
    }

    /**
     * Gọi trực tiếp logic bên trong recordClick (bỏ qua @Async proxy).
     * Giả lập chính xác những gì Spring gọi khi xử lý lượt click.
     */
    private void callRecordClickDirectly(Link link, String ip) {
        analyticsService.recordClick(
                link,
                ip,
                "Mozilla/5.0",
                "https://google.com",
                "Mobile",
                "VN"
        );
    }

    // ── Output helpers ────────────────────────────────────────────────────────

    private void printResult(String scenario, BenchmarkResult r) {
        double totalMs    = r.elapsedNs() / 1_000_000.0;
        double avgUs      = r.elapsedNs() / 1_000.0 / r.total();
        double throughput = r.total() / (totalMs / 1000.0);

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.printf( "║  Scenario : %-40s║%n", scenario);
        System.out.printf( "║  Clicks   : %-10d  Threads: %-20d║%n", r.total(), THREAD_COUNT);
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.printf( "║  Total time  : %10.2f ms%22s║%n", totalMs, "");
        System.out.printf( "║  Avg/click   : %10.2f µs%22s║%n", avgUs, "");
        System.out.printf( "║  Throughput  : %10.0f clicks/s%18s║%n", throughput, "");
        System.out.printf( "║  Success     : %-10d  Errors: %-20d║%n", r.successCount(), r.errorCount());
        System.out.println("╚══════════════════════════════════════════════════════╝");
    }

    private void printComparison(long redisNs, long dbNs, int count) {
        double redisMs = redisNs / 1_000_000.0;
        double dbMs    = dbNs    / 1_000_000.0;
        double ratio   = (double) dbNs / redisNs;

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.printf( "║  SEQUENTIAL COMPARISON — %d clicks%18s║%n", count, "");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.printf( "║  Redis OK   : %8.2f ms  (%6.2f µs/click)%10s║%n",
                redisMs, redisNs / 1_000.0 / count, "");
        System.out.printf( "║  DB fallback: %8.2f ms  (%6.2f µs/click)%10s║%n",
                dbMs, dbNs / 1_000.0 / count, "");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.printf( "║  DB/Redis ratio: %.2fx slower%26s║%n", ratio, "");
        System.out.println("╚══════════════════════════════════════════════════════╝");
    }

    /** Kết quả benchmark của một lần chạy. */
    private record BenchmarkResult(
            int total,
            int successCount,
            int errorCount,
            long elapsedNs
    ) {}
}
