package com.tejast11.IPStatusTracker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.Date;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

@Service
public class PingStatusService {

    private static final Logger log = LoggerFactory.getLogger(PingStatusService.class);
    private final TimeoutConfig timeoutConfig;
    private final MongoCollection<Document> collection;
    public PingStatusService(TimeoutConfig timeoutConfig, MongoDatabase database) {
        this.timeoutConfig = timeoutConfig;
        this.collection = database.getCollection("ESComStat");
    }
    @Scheduled(fixedDelayString = "#{@timeoutConfig.getPingSchedulerTime()}")
    public void checkPingStatus() {
        log.info("Ping Started");
        int timeout = timeoutConfig.getPingTimeoutMs();

        for (Document doc : collection.find()) {
            String ip = doc.getString("IpNo");
            boolean oldStatus = doc.getBoolean("Status", false);
            boolean reachable = pingIP(ip, timeout);

        // Always prepare the update object
            Bson update = Updates.set("Status", reachable);

            if (!reachable) {
                Date lbTimeStamp = Date.from(Instant.now()); // This is UTC
                update = Updates.combine(
                    Updates.set("Status", reachable),
                    Updates.set("LBTimeStamp", lbTimeStamp)
                );
                log.info("[PING] IP {} is unreachable - setting LBTimeStamp to {}", ip, lbTimeStamp);
            }

        // Perform update only if something changed
            if (reachable != oldStatus || !reachable) {
                collection.updateOne(Filters.eq("_id", doc.get("_id")), update);
                log.info("[PING] IP {} status changed to {} (update applied)", ip, reachable);
            } else {
                log.info("[PING] IP {} status unchanged (reachable: {})", ip, reachable);
            }
        }
    }
    private boolean pingIP(String ip, int timeoutMs) {
        try {
            log.debug("[PING] Starting ping for IP: {}", ip);
        
            // Use ping command with proper parameters
            String command = "ping -n 4 -w " + timeoutMs + " " + ip;
            Process process = Runtime.getRuntime().exec(command);
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            boolean hasReply = false;
            
            while ((line = reader.readLine()) != null) {
                log.debug("[PING] Output: {}", line);
                String lower = line.trim().toLowerCase();
                
                // Check for failure conditions first
                if (lower.contains("destination host unreachable") || 
                    lower.contains("request timed out") ||
                    lower.contains("could not find host") ||
                    lower.contains("ping request could not find host")) {
                    log.debug("[PING] IP {} unreachable: {}", ip, line);
                    process.waitFor();
                    return false;
                }

                // Check for successful ping replies
                if (lower.contains("reply from") || 
                    (lower.contains("bytes=") && lower.contains("time=")) ||
                    lower.contains("ttl=")) {
                    log.debug("[PING] IP {} replied: {}", ip, line);
                    hasReply = true;
                }
            }
            
            // Wait for process to complete and check exit code
            int exitCode = process.waitFor();
            log.debug("[PING] Process exit code for IP {}: {}", ip, exitCode);
            
            // For Windows ping: exit code 0 means success, 1 means failure
            boolean isReachable = (exitCode == 0) || hasReply;
            log.info("[PING] IP {} is {}", ip, isReachable ? "REACHABLE" : "UNREACHABLE");
            
            return isReachable;
            
        } catch (IOException | InterruptedException e) {
            log.error("[PING] Error pinging IP {}: {}", ip, e.getMessage());
            return false;
        }
    }
}
