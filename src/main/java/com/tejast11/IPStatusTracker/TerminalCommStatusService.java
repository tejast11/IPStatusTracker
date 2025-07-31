package com.tejast11.IPStatusTracker;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class TerminalCommStatusService {

    private static final Logger log = LoggerFactory.getLogger(TerminalCommStatusService.class);

    private final MongoCollection<Document> cdNuts;
    private final MongoCollection<Document> esComStat;
    private final TimeoutConfig timeoutConfig;

    public TerminalCommStatusService(TimeoutConfig timeoutConfig, MongoDatabase database) {
        this.timeoutConfig = timeoutConfig;
        this.cdNuts = database.getCollection("CDNuts");
        this.esComStat = database.getCollection("ESComStat");
    }

    @Scheduled(fixedRateString = "#{@timeoutConfig.getSchedulerTime()}")
    public void checkTerminalStatus() {
        log.info("[COMM] Terminal communication status check started...");
        long timeoutMs = timeoutConfig.getTerminalTimeoutMs();

        for (Document bridge : esComStat.find()) {
            Object bridgeId = bridge.get("_id");
            List<Document> terminals = (List<Document>) bridge.get("TerminalDetails");

            if (terminals == null || terminals.isEmpty()) {
                log.debug("[COMM] No TerminalDetails found for bridge _id: {}", bridgeId);
                continue;
            }

            boolean bridgeUpdated = false;

            for (Document terminal : terminals) {
                Integer terminalId = terminal.getInteger("TerminalId");
                if (terminalId == null) {
                    log.warn("[COMM] TerminalId is null in TerminalDetails for bridge _id: {}", bridgeId);
                    continue;
                }

                Long previousTs = terminal.getLong("TimeStampId");
                Long currentTs = getCurrentTimeStampId(terminalId);

                if (currentTs == null) {
                    log.warn("[COMM] TerminalId {} not found in CDNuts, skipping...", terminalId);
                    continue;
                }

                log.info("[COMM] Checking TerminalId {} for update (previous: {}, current: {})",
                        terminalId, previousTs, currentTs);

                Boolean currentStatus = terminal.getBoolean("Status", false);
                boolean updated = currentStatus; // Default to current status

                // For first parse (TimeStampId absent), only set TimeStampId
                if (previousTs == null) {
                    log.info("[COMM] TerminalId {} first parse, setting TimeStampId without changing Status", terminalId);
                    terminal.put("TimeStampId", currentTs);
                } else {
                    // For subsequent parses, check for TimeStampId update
                    updated = checkTimestampUpdate(terminalId, previousTs, currentTs, timeoutMs);
                    terminal.put("TimeStampId", currentTs);
                    terminal.put("Status", updated);

                    // Log status change
                    if (updated) {
                        log.info("[COMM] TerminalId {} updated within timeout → Status = true", terminalId);
                    } else {
                        log.info("[COMM] TerminalId {} did NOT update in time → Status = false", terminalId);
                    }
                }

                // Mark bridge as updated if Status or TimeStampId changed
                if (!currentStatus.equals(terminal.getBoolean("Status", false)) || !Objects.equals(previousTs, currentTs)) {
                    bridgeUpdated = true;
                }
            }

            if (bridgeUpdated) {
                esComStat.updateOne(
                    Filters.eq("_id", bridgeId),
                    Updates.set("TerminalDetails", terminals)
                );
                log.info("[COMM] Updated ESComStat bridge document with _id {}", bridgeId);
            } else {
                log.debug("[COMM] No updates required for bridge _id: {}", bridgeId);
            }
        }

        log.info("[COMM] Terminal communication check complete.");
    }

    private boolean checkTimestampUpdate(int terminalId, Long previousTs, Long currentTs, long timeoutMs) {
        // If timestamps differ, consider it updated
        if (!previousTs.equals(currentTs)) {
            log.info("[COMM] TerminalId {} TimeStampId updated (previous: {}, current: {})",
                    terminalId, previousTs, currentTs);
            return true;
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        long pollInterval = 1000; // Check every 1 second

        while (System.currentTimeMillis() < deadline) {
            Long newTs = getCurrentTimeStampId(terminalId);
            if (newTs != null && !newTs.equals(previousTs)) {
                log.info("[COMM] TerminalId {} TimeStampId updated from {} to {}", terminalId, previousTs, newTs);
                return true;
            }

            try {
                Thread.sleep(pollInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[COMM] Watcher interrupted for TerminalId {}", terminalId);
                return false;
            }
        }

        log.info("[COMM] TerminalId {} no TimeStampId update within timeout", terminalId);
        return false;
    }

    private Long getCurrentTimeStampId(int terminalId) {
        Document doc = cdNuts.find(Filters.eq("TerminalId", terminalId)).first();
        if (doc != null && doc.containsKey("TimeStampId")) {
            Object value = doc.get("TimeStampId");
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        }
        return null;
    }
}