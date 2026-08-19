package com.ddd.d3.community.config;

import com.ddd.d3.community.adapter.persistence.JdbcCommunityRepository;
import com.ddd.d3.community.adapter.persistence.JdbcMatchProjectionStore;
import com.ddd.d3.community.adapter.persistence.JdbcProfileIdentityStore;
import com.ddd.d3.community.adapter.persistence.JdbcProfileRatingStore;
import com.ddd.d3.community.application.CommunityService;
import com.ddd.d3.community.application.MatchFinishedProjectionService;
import com.ddd.d3.community.domain.MarkdownPolicy;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class CommunityServiceConfiguration {

    @Bean
    Clock communityClock() {
        return Clock.systemUTC();
    }

    @Bean
    MarkdownPolicy markdownPolicy() {
        return new MarkdownPolicy();
    }

    @Bean
    JdbcCommunityRepository communityRepository(DataSource dataSource, Clock communityClock, ObjectMapper objectMapper) {
        return new JdbcCommunityRepository(JdbcClient.create(dataSource), communityClock, objectMapper);
    }

    @Bean
    JdbcMatchProjectionStore matchProjectionStore(DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcMatchProjectionStore(JdbcClient.create(dataSource), objectMapper);
    }

    @Bean
    MatchFinishedProjectionService matchFinishedProjectionService(
            JdbcMatchProjectionStore store,
            CommunityService communityService,
            PlatformTransactionManager transactionManager) {
        return new MatchFinishedProjectionService(
                store, new TransactionTemplate(transactionManager), communityService::createResultPost);
    }

    @Bean
    JdbcProfileRatingStore profileRatingStore(
            DataSource dataSource, PlatformTransactionManager transactionManager) {
        return new JdbcProfileRatingStore(
                JdbcClient.create(dataSource), new TransactionTemplate(transactionManager));
    }

    @Bean
    JdbcProfileIdentityStore profileIdentityStore(
            DataSource dataSource, PlatformTransactionManager transactionManager) {
        return new JdbcProfileIdentityStore(
                JdbcClient.create(dataSource), new TransactionTemplate(transactionManager));
    }

    @Bean
    CommunityService communityService(
            JdbcCommunityRepository repository,
            MarkdownPolicy markdownPolicy,
            @Value("${d3.community.prose-limit:2000}") int proseLimit,
            @Value("${d3.community.markdown-limit:20000}") int markdownLimit) {
        return new CommunityService(repository, markdownPolicy, UUID::randomUUID, proseLimit, markdownLimit);
    }
}
