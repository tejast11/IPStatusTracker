package com.tejast11.IPStatusTracker;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
    @Scheduled(fixedDelayString = "#{@timeoutConfig.getSchedulerTime()}")
    public void checkPingStatus() {
        log.info("Ping Started");
        int timeout = timeoutConfig.getPingTimeoutMs();
        for (Document doc : collection.find()) {
            String ip = doc.getString("IpNo");
            boolean oldStatus = doc.getBoolean("Status", false);

            Date individualPingTime = Date.from(Instant.now());
            boolean reachable = pingIP(ip, timeout);

        // Always update both status and LBTue
            Bson update = Updates.combine(
                Updates.set("Status",reachable),
                Updates.set("LBTimeStamp",individualPingTime));
                
        // Always update database to reflect the actual ping time for each IP
            collection.updateOne(Filters.eq("_id", doc.get("_id")), update);
            
            if (reachable != oldStatus) {
                log.info("[PING] IP {} status changed to {} - LBTimeStamp updated to {}", ip, reachable, individualPingTime);
            } else {
                log.debug("[PING] IP {} status unchanged ({}) - LBTimeStamp updated to {}", ip, reachable, individualPingTime);
            }
        }
    }
    private boolean pingIP(String ip, int timeoutMs) {
        try {
            log.debug("[PING] Starting ping for IP: {}", ip);
        
            // Use InetAddress to ping with timeout
           InetAddress ping = InetAddress.getByName(ip);
           boolean reachable = ping.isReachable(timeoutMs);
           log.info("[PING] IP {} is {}", ip, reachable ? "REACHABLE" : "UNREACHABLE"); 
           return reachable;  
        }catch(UnknownHostException e){
            log.info("Error: Unknown host - " + ip);
            return false;
        }catch (IOException e) {
            log.info("Error during ping operation: " + e.getMessage());
            return false;
        }
    }
}
