package com.ddd.d3.community.config;

import com.ddd.d3.community.adapter.persistence.JdbcCommunityRepository;
import com.ddd.d3.community.application.CommunityService;
import com.ddd.d3.community.domain.MarkdownPolicy;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

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
    CommunityService communityService(
            JdbcCommunityRepository repository,
            MarkdownPolicy markdownPolicy,
            @Value("${d3.community.prose-limit:2000}") int proseLimit,
            @Value("${d3.community.markdown-limit:20000}") int markdownLimit) {
        return new CommunityService(repository, markdownPolicy, UUID::randomUUID, proseLimit, markdownLimit);
    }
}
