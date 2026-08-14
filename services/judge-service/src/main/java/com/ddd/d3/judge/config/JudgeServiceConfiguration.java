package com.ddd.d3.judge.config;

import com.ddd.d3.judge.adapter.async.AsyncJudgeEvaluationScheduler;
import com.ddd.d3.judge.adapter.async.QueuedSubmissionDispatcher;
import com.ddd.d3.judge.adapter.fake.DeterministicFakeJudgeAdapter;
import com.ddd.d3.judge.adapter.judge0.DemoJudgeProblemCatalog;
import com.ddd.d3.judge.adapter.judge0.HttpJudge0Client;
import com.ddd.d3.judge.adapter.judge0.Judge0Client;
import com.ddd.d3.judge.adapter.judge0.Judge0ExecutionAdapter;
import com.ddd.d3.judge.adapter.judge0.Judge0HttpSettings;
import com.ddd.d3.judge.adapter.judge0.JudgeProblemCatalog;
import com.ddd.d3.judge.adapter.messaging.KafkaJudgeEventPublisher;
import com.ddd.d3.judge.adapter.messaging.ScheduledJudgeOutboxPublisher;
import com.ddd.d3.judge.adapter.persistence.JdbcJudgeOutboxStore;
import com.ddd.d3.judge.adapter.persistence.JdbcJudgeSubmissionRepository;
import com.ddd.d3.judge.application.JudgeEventPublisher;
import com.ddd.d3.judge.application.JudgeEvaluationScheduler;
import com.ddd.d3.judge.application.JudgeExecutionAdapter;
import com.ddd.d3.judge.application.JudgeOutboxDispatcher;
import com.ddd.d3.judge.application.JudgeOutboxStore;
import com.ddd.d3.judge.application.JudgeSubmissionRepository;
import com.ddd.d3.judge.application.JudgeSubmissionService;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableScheduling
public class JudgeServiceConfiguration {

    @Bean
    Clock judgeClock() {
        return Clock.systemUTC();
    }

    @Bean
    JudgeProblemCatalog judgeProblemCatalog() {
        return new DemoJudgeProblemCatalog();
    }

    @Bean
    @ConditionalOnProperty(name = "d3.judge.adapter", havingValue = "judge0")
    HttpClient judge0HttpClient(@Value("${d3.judge0.connect-timeout:2s}") Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Bean
    @ConditionalOnProperty(name = "d3.judge.adapter", havingValue = "judge0")
    Judge0HttpSettings judge0HttpSettings(
            @Value("${d3.judge0.base-url}") URI baseUri,
            @Value("${d3.judge0.allowed-origin}") URI allowedOrigin,
            @Value("${d3.judge0.authentication-header:X-Auth-Token}") String authenticationHeader,
            @Value("${d3.judge0.authentication-token}") String authenticationToken,
            @Value("${d3.judge0.request-timeout:10s}") Duration requestTimeout,
            @Value("${d3.judge0.poll-interval:100ms}") Duration pollInterval,
            @Value("${d3.judge0.poll-timeout:30s}") Duration pollTimeout) {
        return new Judge0HttpSettings(
                baseUri,
                allowedOrigin,
                authenticationHeader,
                authenticationToken,
                requestTimeout,
                pollInterval,
                pollTimeout);
    }

    @Bean
    @ConditionalOnProperty(name = "d3.judge.adapter", havingValue = "judge0")
    Judge0Client judge0Client(
            HttpClient judge0HttpClient, ObjectMapper objectMapper, Judge0HttpSettings settings) {
        return new HttpJudge0Client(judge0HttpClient, objectMapper, settings);
    }

    @Bean
    @ConditionalOnProperty(name = "d3.judge.adapter", havingValue = "judge0")
    JudgeExecutionAdapter judgeExecutionAdapter(
            Judge0Client judge0Client, JudgeProblemCatalog problemCatalog, Clock judgeClock) {
        return new Judge0ExecutionAdapter(judge0Client, problemCatalog, judgeClock);
    }

    @Bean
    @ConditionalOnProperty(name = "d3.judge.adapter", havingValue = "fake", matchIfMissing = true)
    JudgeExecutionAdapter deterministicFakeJudgeExecutionAdapter(Clock judgeClock, Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalStateException("the fake Judge adapter is restricted to local and test profiles");
        }
        return new DeterministicFakeJudgeAdapter(judgeClock);
    }

    @Bean
    JudgeSubmissionRepository judgeSubmissionRepository(DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcJudgeSubmissionRepository(
                JdbcClient.create(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                objectMapper);
    }

    @Bean
    JudgeOutboxStore judgeOutboxStore(DataSource dataSource) {
        return new JdbcJudgeOutboxStore(JdbcClient.create(dataSource));
    }

    @Bean
    JudgeEventPublisher judgeEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${d3.judge.submission-judged-topic:submission.judged.v1}") String topic) {
        return new KafkaJudgeEventPublisher(kafkaTemplate, topic);
    }

    @Bean
    JudgeOutboxDispatcher judgeOutboxDispatcher(
            JudgeOutboxStore store, JudgeEventPublisher publisher, Clock judgeClock) {
        return new JudgeOutboxDispatcher(store, publisher, judgeClock);
    }

    @Bean
    ScheduledJudgeOutboxPublisher scheduledJudgeOutboxPublisher(JudgeOutboxDispatcher dispatcher) {
        return new ScheduledJudgeOutboxPublisher(dispatcher);
    }

    @Bean
    JudgeSubmissionService judgeSubmissionService(
            JudgeSubmissionRepository repository, JudgeExecutionAdapter executionAdapter, Clock judgeClock) {
        return new JudgeSubmissionService(repository, executionAdapter, judgeClock, UUID::randomUUID);
    }

    @Bean
    TaskExecutor judgeTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("judge-evaluation-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean
    JudgeEvaluationScheduler judgeEvaluationScheduler(
            JudgeSubmissionService submissionService, TaskExecutor judgeTaskExecutor) {
        return new AsyncJudgeEvaluationScheduler(submissionService, judgeTaskExecutor);
    }

    @Bean
    QueuedSubmissionDispatcher queuedSubmissionDispatcher(
            JudgeSubmissionRepository repository, JudgeEvaluationScheduler evaluationScheduler) {
        return new QueuedSubmissionDispatcher(repository, evaluationScheduler);
    }
}
