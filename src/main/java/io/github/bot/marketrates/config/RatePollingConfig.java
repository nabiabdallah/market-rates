package io.github.bot.marketrates.config;

import io.github.bot.marketrates.application.port.in.PollRatesUseCase;
import io.github.bot.marketrates.application.port.out.RateGateway;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * One fixed-delay poll task per provider, each on its own thread, so a slow provider cannot delay another and
 * one provider's polls never overlap (the single-writer-per-provider assumption of the service).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "market-rates.polling.enabled", havingValue = "true", matchIfMissing = true)
class RatePollingConfig implements SchedulingConfigurer, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RatePollingConfig.class);

    private ThreadPoolTaskScheduler scheduler;
    private final PollRatesUseCase poll;
    private final Set<String> providers;
    private final Duration interval;

    RatePollingConfig(PollRatesUseCase poll, List<RateGateway> gateways, MarketRatesProperties props) {
        this.poll = poll;
        this.providers =
                gateways.stream().map(RateGateway::provider).collect(Collectors.toCollection(LinkedHashSet::new));
        this.interval = props.pollInterval();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, providers.size()));
        scheduler.setThreadNamePrefix("rate-poll-");
        scheduler.initialize();
        registrar.setTaskScheduler(scheduler);
        providers.forEach(p -> registrar.addFixedDelayTask(task(poll, p), interval));
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /** A task that never throws, so nothing reaches the scheduler's error handler (which logs messages). */
    static Runnable task(PollRatesUseCase useCase, String provider) {
        return () -> {
            try {
                useCase.poll(provider);
            } catch (RuntimeException e) {
                log.warn(
                        "Rate poll task failed provider={} error={}",
                        provider,
                        e.getClass().getSimpleName());
            }
        };
    }
}
