package com.bkstudio.admin.auth;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Rejects reuse of a TOTP step (RFC 6238 section 5.2): only a step newer than the last accepted one passes.
 * shortcut: in memory, so it is lost on restart and not shared between instances; move to the DB if we ever
 * run more than one backend instance.
 */
@Component
public class TotpReplayGuard {

    private final ConcurrentHashMap<Long, Long> lastStep = new ConcurrentHashMap<>();

    /** True if the step is new for this admin (and records it); false on replay. */
    public boolean accept(long adminId, long step) {
        boolean[] accepted = {false};
        lastStep.compute(adminId, (id, last) -> {
            if (last == null || step > last) {
                accepted[0] = true;
                return step;
            }
            return last;
        });
        return accepted[0];
    }
}
