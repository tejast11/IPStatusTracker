package com.tejast11.IPStatusTracker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TimeoutConfig {
    private static final Logger log = LoggerFactory.getLogger(TimeoutConfig.class);

    private int getTimeoutFromFile(String key) {
        try {
            File file = new File("config.txt");
            List<String> lines = Files.readAllLines(file.toPath());
            for (String line : lines) {
                if (line.startsWith(key + "=")) {
                    return Integer.parseInt(line.split("=")[1].trim()) * 1000;
                }
            }
        } catch (IOException | NumberFormatException e) {
            log.error("Failed to read timeout from configfile.txt for key '{}'.", key, e);
        }
        return 30000;
    }

    public int getPingTimeoutMs() {
        return getTimeoutFromFile("bridge.timeout");
    }
    public int getTerminalTimeoutMs() {
        return getTimeoutFromFile("terminal.timeout");
    }
    public int getPingSchedulerTime(){
        return getTimeoutFromFile("scheduler.ping");
    }
    public int getTerminalSchedulerTime(){
        return getTimeoutFromFile("scheduler.terminal");
    }
}
