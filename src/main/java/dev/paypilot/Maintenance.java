package dev.paypilot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "paypilot.worker.enabled", havingValue = "true")
class Maintenance {
    private static final Logger log = LoggerFactory.getLogger(Maintenance.class);
    private final ApplicationService service;

    Maintenance(ApplicationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${paypilot.worker.interval}", initialDelayString = "${paypilot.worker.interval}")
    void tick() {
        service.prune();
        for (Long id : service.activeOwners()) {
            try {
                service.processOwner(id);
            } catch (Problem e) {
                log.warn("Could not deliver a reminder for account {}. It will be retried.", id);
            } catch (RuntimeException e) {
                log.error("Could not process workspace {}.", id, e);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Admin {
        @Bean
        ApplicationRunner bootstrapAdmin(AccessService access, Environment env) {
            return args -> access.bootstrap(env.getProperty("PAYPILOT_ADMIN_USERNAME", ""),
                    env.getProperty("PAYPILOT_ADMIN_EMAIL", ""),
                    env.getProperty("PAYPILOT_ADMIN_PASSWORD", ""));
        }
    }
}
