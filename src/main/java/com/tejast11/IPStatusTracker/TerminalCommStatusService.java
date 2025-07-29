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

import java.util.*;

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

    @Scheduled(fixedRateString = "#{@timeoutConfig.getTerminalSchedulerTime()}")
    public void checkTerminalStatus() {
        log.info("[COMM] Terminal communication status check started...");
        long timeoutMs = timeoutConfig.getTerminalTimeoutMs();

        for (Document bridge : esComStat.find()) {
            Object bridgeId = bridge.get("_id");
            List<Document> terminals = (List<Document>) bridge.get("TerminalDetails");

            if (terminals == null || terminals.isEmpty()) continue;

            boolean bridgeUpdated = false;

            for (Document terminal : terminals) {
                Integer terminalId = terminal.getInteger("TerminalId");
                if (terminalId == null) continue;

                Boolean currentStatus = terminal.getBoolean("Status", false);
                Long previousTs = terminal.getLong("TimeStampId");
                Long currentTs = getCurrentTimeStampId(terminalId);

                if (currentTs == null) {
                    log.warn("[COMM] TerminalId {} not found in CDNuts, skipping...", terminalId);
                    continue;
                }

                log.info("[COMM] Watching TerminalId {} for update (previous: {}, current: {})",
                        terminalId, previousTs, currentTs);

                boolean updated = waitForUpdateWithinTimeout(terminalId, previousTs, timeoutMs);

                // Always update the TimeStampId field in TerminalDetails
                terminal.put("TimeStampId", currentTs);

                if (updated) {
                    terminal.put("Status", true);
                    log.info("[COMM] TerminalId {} updated within timeout → Status = true", terminalId);
                } else {
                    terminal.put("Status", false);
                    log.info("[COMM] TerminalId {} did NOT update in time → Status = false", terminalId);
                }

                // Mark bridge as updated if terminal's status changed
                if (!Objects.equals(currentStatus, terminal.getBoolean("Status"))) {
                    bridgeUpdated = true;
                }
            }

            if (bridgeUpdated) {
                esComStat.updateOne(Filters.eq("_id", bridgeId), Updates.set("TerminalDetails", terminals));
                log.info("[COMM] Updated ESComStat bridge document with _id {}", bridgeId);
            }
        }

        log.info("[COMM] Terminal communication check complete.");
    }

    private boolean waitForUpdateWithinTimeout(int terminalId, Long lastTsId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long pollInterval = 1000; // Check every 1 second

        while (System.currentTimeMillis() < deadline) {
            Long currentTsId = getCurrentTimeStampId(terminalId);

            if (currentTsId != null && (lastTsId == null || !currentTsId.equals(lastTsId))) {
                return true; // Update found
            }

            try {
                Thread.sleep(pollInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[COMM] Watcher interrupted for TerminalId {}", terminalId);
                break;
            }
        }
        return false; // Timeout expired
    }

    private Long getCurrentTimeStampId(int terminalId) {
        Document doc = cdNuts.find(Filters.eq("TerminalId", terminalId)).first();
        if (doc != null && doc.containsKey("TimeStampId")) {
            Object value = doc.get("TimeStampId");
            if (value instanceof Number) {
                return ((Number) value).longValue();  // Safely handles Integer, Long, etc.
            }
        }
        return null;
    }
}
