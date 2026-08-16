package com.ddd.d3.community.config;

import com.ddd.d3.community.adapter.persistence.JdbcCommunityRepository;
import com.ddd.d3.community.adapter.persistence.JdbcMatchProjectionStore;
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
    JdbcCommunityRepository communityRepository(DataSource dataSource, Clock communityClock) {
        return new JdbcCommunityRepository(JdbcClient.create(dataSource), communityClock);
    }

    @Bean
    JdbcMatchProjectionStore matchProjectionStore(DataSource dataSource) {
        return new JdbcMatchProjectionStore(JdbcClient.create(dataSource));
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
    CommunityService communityService(
            JdbcCommunityRepository repository,
            MarkdownPolicy markdownPolicy,
            @Value("${d3.community.prose-limit:2000}") int proseLimit,
            @Value("${d3.community.markdown-limit:20000}") int markdownLimit) {
        return new CommunityService(repository, markdownPolicy, UUID::randomUUID, proseLimit, markdownLimit);
    }
}
