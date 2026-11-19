package dev.paypilot;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PayPilot {
    public static void main(String[] args) {
        SpringApplication.run(PayPilot.class, args);
    }

    @Bean
    @ConditionalOnProperty(name = "paypilot.demo", havingValue = "true")
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> protectDemo(Environment environment) {
        return factory -> {
            String host = environment.getProperty("server.address", "0.0.0.0");
            try {
                if (InetAddress.getByName(host).isLoopbackAddress()) return;
            } catch (UnknownHostException ignored) {
                // An unresolved address also fails the loopback requirement.
            }
            throw new IllegalStateException("PayPilot demo mode requires a loopback address.");
        };
    }
}
