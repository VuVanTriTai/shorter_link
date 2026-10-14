package com.vvttai.smart_link_shortener.modules.analytics.service;

import com.vvttai.smart_link_shortener.modules.analytics.repository.ClickAnalyticsRepository;
import com.vvttai.smart_link_shortener.modules.analytics.repository.ClickStatsRepository;
import com.vvttai.smart_link_shortener.modules.link.repository.LinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Background job đọc events từ Redis Stream, chuẩn hoá, ghi vào DB theo batch.
 *
 * <h3>Luồng xử lý (mỗi 5 giây):</h3>
 * <ol>
 *   <li>Đọc tối đa {@value #BATCH_SIZE} messages mới từ Stream bằng XREADGROUP.</li>
 *   <li>Retry các pending messages bị stale > {@value #PENDING_TIMEOUT_MS}ms bằng XAUTOCLAIM.</li>
 *   <li>Chuẩn hoá dữ liệu (device, referrer, country, daily label).</li>
 *   <li>Batch INSERT vào {@code click_analytics} với ON CONFLICT DO NOTHING
 *       — đảm bảo idempotency khi retry.</li>
 *   <li>UPSERT vào {@code click_stats} cho DEVICE / COUNTRY / REFERRER / DAILY.</li>
 *   <li>Sync {@code links.click_count} từ Redis counter.</li>
 *   <li>XACK — chỉ sau khi transaction DB commit thành công.</li>
 * </ol>
 *
 * <p>Nếu bước 4 hoặc 5 thất bại, XACK <strong>không</strong> được gửi
 * → message nằm lại PEL → tự retry ở chu kỳ sau.</p>
 */
@Component
public class AnalyticsBatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsBatchProcessor.class);

    static final String STREAM_KEY      = "analytics:click-stream";
    static final String GROUP_NAME      = "analytics-workers";
    static final String CONSUMER_NAME   = "processor-1";
    static final int    BATCH_SIZE      = 500;
    static final long   PENDING_TIMEOUT_MS = 30_000; // 30s

    private final RedisTemplate<String, String>  redisTemplate;
    private final ClickAnalyticsRepository       clickAnalyticsRepository;
    private final ClickStatsRepository           clickStatsRepository;
    private final LinkRepository                 linkRepository;
    private final RedisClickCounterService       counterService;
    private final ClickNormalizer                normalizer;

    public AnalyticsBatchProcessor(
            RedisTemplate<String, String>  redisTemplate,
            ClickAnalyticsRepository       clickAnalyticsRepository,
            ClickStatsRepository           clickStatsRepository,
            LinkRepository                 linkRepository,
            RedisClickCounterService       counterService,
            ClickNormalizer                normalizer) {
        this.redisTemplate            = redisTemplate;
        this.clickAnalyticsRepository = clickAnalyticsRepository;
        this.clickStatsRepository     = clickStatsRepository;
        this.linkRepository           = linkRepository;
        this.counterService           = counterService;
        this.normalizer               = normalizer;
    }

    // =========================================================================
    // Scheduled Flush
    // =========================================================================

    @Scheduled(fixedDelay = 5000)
    public void flush() {
        try {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(STREAM_KEY))) {
                return; // Chưa có click nào được gửi vào stream -> bỏ qua chu kỳ này
            }
        } catch (Exception e) {
            return; // Redis không khả dụng
        }

        ensureConsumerGroup();

        List<ParsedEvent> events = new ArrayList<>(BATCH_SIZE);
        List<RecordId>    ids    = new ArrayList<>(BATCH_SIZE);

        // 1. Đọc messages mới
        readNewMessages(events, ids);

        // 2. Retry pending messages stale > 30s
        reclaimPendingMessages(events, ids);

        if (events.isEmpty()) return;

        // 3. Ghi vào DB trong transaction — XACK chỉ chạy nếu commit thành công
        List<RecordId> ackedIds = persistBatch(events);

        // 4. XACK các message đã xử lý thành công
        if (!ackedIds.isEmpty()) {
            try {
                redisTemplate.opsForStream().acknowledge(
                        STREAM_KEY, GROUP_NAME,
                        ackedIds.toArray(new RecordId[0]));
            } catch (Exception e) {
                log.warn("XACK failed for {} messages (will retry next cycle): {}", ackedIds.size(), e.getMessage());
            }
        }
    }

    // =========================================================================
    // Read helpers
    // =========================================================================

    private void readNewMessages(List<ParsedEvent> events, List<RecordId> ids) {
        try {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .read(Consumer.from(GROUP_NAME, CONSUMER_NAME),
                            StreamReadOptions.empty().count(BATCH_SIZE),
                            StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));

            if (records == null) return;
            for (MapRecord<String, Object, Object> r : records) {
                ParsedEvent ev = parse(r);
                if (ev != null) {
                    events.add(ev);
                    ids.add(r.getId());
                }
            }
        } catch (Exception e) {
            log.debug("XREADGROUP status: {}", e.getMessage());
        }
    }

    private void reclaimPendingMessages(List<ParsedEvent> events, List<RecordId> ids) {
        try {
            // XPENDING summary: kiểm tra có pending message không
            PendingMessagesSummary summary = redisTemplate.opsForStream()
                    .pending(STREAM_KEY, GROUP_NAME);
            if (summary == null || summary.getTotalPendingMessages() == 0) return;

            // Lấy danh sách pending messages với min delivery time > PENDING_TIMEOUT_MS
            org.springframework.data.redis.connection.stream.PendingMessages pending =
                    redisTemplate.opsForStream().pending(
                            STREAM_KEY,
                            org.springframework.data.redis.connection.stream.Consumer.from(GROUP_NAME, CONSUMER_NAME),
                            org.springframework.data.domain.Range.unbounded(),
                            100L);

            if (pending == null || !pending.iterator().hasNext()) return;

            for (org.springframework.data.redis.connection.stream.PendingMessage pm : pending) {
                // Chỉ reclaim nếu message đã pending quá PENDING_TIMEOUT_MS
                if (pm.getElapsedTimeSinceLastDelivery().toMillis() < PENDING_TIMEOUT_MS) continue;
                if (ids.contains(pm.getId())) continue;

                List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                        .claim(STREAM_KEY, GROUP_NAME, CONSUMER_NAME,
                                Duration.ofMillis(PENDING_TIMEOUT_MS), pm.getId());

                if (claimed == null) continue;
                for (MapRecord<String, Object, Object> r : claimed) {
                    ParsedEvent ev = parse(r);
                    if (ev != null) {
                        events.add(ev);
                        ids.add(r.getId());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Pending message reclaim status: {}", e.getMessage());
        }
    }

    // =========================================================================
    // DB persistence — trong một @Transactional
    // =========================================================================

    /**
     * Ghi toàn bộ batch vào DB trong 1 transaction.
     *
     * @return danh sách RecordId đã ghi thành công (để XACK)
     */
    @Transactional
    protected List<RecordId> persistBatch(List<ParsedEvent> events) {
        if (events.isEmpty()) return List.of();

        // ── Chuẩn hoá ────────────────────────────────────────────────────────
        List<NormalizedEvent> normalized = new ArrayList<>(events.size());
        for (ParsedEvent ev : events) {
            String device   = normalizer.normalizeDeviceType(ev.userAgent());
            String referrer = normalizer.normalizeReferrer(ev.referrer());
            String country  = normalizer.normalizeCountry(ev.country());
            String daily    = normalizer.dailyLabel(ev.clickedAt());
            normalized.add(new NormalizedEvent(ev, device, referrer, country, daily));
        }

        // ── Arrays cho batch INSERT ───────────────────────────────────────────
        int n = normalized.size();
        Long[]   linkIds      = new Long[n];
        String[] clickedAts   = new String[n];
        String[] ipAddresses  = new String[n];
        String[] userAgents   = new String[n];
        String[] referrers    = new String[n];
        String[] deviceTypes  = new String[n];
        String[] countries    = new String[n];
        String[] streamMsgIds = new String[n];

        for (int i = 0; i < n; i++) {
            NormalizedEvent ne = normalized.get(i);
            linkIds[i]      = ne.raw().linkId();
            clickedAts[i]   = ne.raw().clickedAt() != null ? ne.raw().clickedAt().toString() : Instant.now().toString();
            ipAddresses[i]  = ne.raw().ipAddress();
            userAgents[i]   = ne.raw().userAgent();
            referrers[i]    = ne.referrer();
            deviceTypes[i]  = ne.device();
            countries[i]    = ne.country();
            streamMsgIds[i] = ne.raw().streamMsgId();
        }

        // ── Batch INSERT click_analytics (ON CONFLICT DO NOTHING) ─────────────
        try {
            int inserted = clickAnalyticsRepository.batchInsertWithIdempotency(
                    linkIds, clickedAts, ipAddresses, userAgents,
                    referrers, deviceTypes, countries, streamMsgIds);
            log.debug("Inserted {} / {} click_analytics rows.", inserted, n);
        } catch (DataIntegrityViolationException e) {
            // Partial duplicate — vẫn tiếp tục (ON CONFLICT DO NOTHING đã xử lý)
            log.warn("batchInsert partial conflict (expected on retry): {}", e.getMessage());
        } catch (Exception e) {
            log.error("batchInsert click_analytics failed — batch NOT acked: {}", e.getMessage(), e);
            return List.of(); // Không XACK → toàn bộ batch sẽ retry
        }

        // ── UPSERT click_stats ────────────────────────────────────────────────
        // Gom nhóm: (linkId, statType, label) → delta count
        Map<String, Long> deltas = new LinkedHashMap<>();
        for (NormalizedEvent ne : normalized) {
            Long lid = ne.raw().linkId();
            accumulate(deltas, statsKey(lid, "DEVICE",   ne.device()));
            accumulate(deltas, statsKey(lid, "COUNTRY",  ne.country()));
            accumulate(deltas, statsKey(lid, "REFERRER", ne.referrer()));
            accumulate(deltas, statsKey(lid, "DAILY",    ne.daily()));
        }

        try {
            for (Map.Entry<String, Long> entry : deltas.entrySet()) {
                String[] parts = entry.getKey().split("\\|", 3);
                clickStatsRepository.upsertStat(
                        Long.parseLong(parts[0]), parts[1], parts[2], entry.getValue());
            }
        } catch (Exception e) {
            log.error("UPSERT click_stats failed — batch NOT acked: {}", e.getMessage(), e);
            return List.of();
        }

        // ── Sync click_count từ Redis counter về DB ───────────────────────────
        Set<Long> affectedLinks = new LinkedHashSet<>();
        for (ParsedEvent ev : events) affectedLinks.add(ev.linkId());

        for (Long linkId : affectedLinks) {
            try {
                Long redisCount = counterService.get(linkId);
                if (redisCount != null) {
                    linkRepository.syncClickCount(linkId, redisCount);
                }
            } catch (Exception e) {
                log.warn("Failed to sync click_count for linkId={}: {}", linkId, e.getMessage());
            }
        }

        // Trả về IDs để XACK sau khi commit (method này @Transactional —
        // Spring sẽ commit trước khi return, sau đó caller gọi XACK)
        List<RecordId> successIds = new ArrayList<>(events.size());
        for (ParsedEvent ev : events) successIds.add(RecordId.of(ev.streamMsgId()));
        log.info("Flushed batch: {} events, {} stats upserted.", n, deltas.size());
        return successIds;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void ensureConsumerGroup() {
        try {
            redisTemplate.opsForStream()
                    .createGroup(STREAM_KEY, ReadOffset.from("0-0"), GROUP_NAME);
        } catch (Exception e) {
            // Group đã tồn tại — bỏ qua (expected exception)
        }
    }

    private ParsedEvent parse(MapRecord<String, Object, Object> record) {
        try {
            Map<Object, Object> body = record.getValue();
            String msgId = record.getId().getValue();

            Long linkId = Long.parseLong(str(body, "linkId"));

            String rawTs = str(body, "clickedAt");
            Instant clickedAt = (rawTs != null && !rawTs.isBlank())
                    ? Instant.parse(rawTs)
                    : Instant.now();

            return new ParsedEvent(
                    msgId,
                    linkId,
                    clickedAt,
                    str(body, "ip"),
                    str(body, "ua"),
                    str(body, "referrer"),
                    str(body, "device"),
                    str(body, "country")
            );
        } catch (Exception e) {
            log.warn("Failed to parse stream record {}: {}", record.getId(), e.getMessage());
            return null;
        }
    }

    private static String str(Map<Object, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private static String statsKey(Long linkId, String type, String label) {
        return linkId + "|" + type + "|" + label;
    }

    private static void accumulate(Map<String, Long> map, String key) {
        map.merge(key, 1L, Long::sum);
    }

    // ── Inner records ─────────────────────────────────────────────────────────

    /** Raw event parsed từ Redis Stream message. */
    private record ParsedEvent(
            String  streamMsgId,
            Long    linkId,
            Instant clickedAt,
            String  ipAddress,
            String  userAgent,
            String  referrer,
            String  device,
            String  country
    ) {}

    /** Event sau khi đã qua ClickNormalizer. */
    private record NormalizedEvent(
            ParsedEvent raw,
            String device,
            String referrer,
            String country,
            String daily
    ) {}
}
