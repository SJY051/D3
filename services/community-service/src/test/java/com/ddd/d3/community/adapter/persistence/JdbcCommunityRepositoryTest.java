package com.ddd.d3.community.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ddd.d3.community.application.CommunityService.NewPost;
import com.ddd.d3.community.domain.PostVisibility;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class JdbcCommunityRepositoryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    private static final UUID USER_ONE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID USER_TWO = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID POST_ONE = UUID.fromString("33333333-3333-4333-8333-333333333331");
    private static final UUID POST_TWO = UUID.fromString("33333333-3333-4333-8333-333333333332");
    private JdbcClient jdbc;
    private JdbcCommunityRepository repository;

    @BeforeEach
    void migrateAndReset() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        repository = new JdbcCommunityRepository(
                jdbc,
                Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void d3Com001RecognizesAllAudiencesButFeedsOnlyPublicPostsWithKeysetPagination() {
        repository.insertPost(new NewPost(POST_ONE, USER_ONE, PostVisibility.PUBLIC, "one", "<p>one</p>", 3));
        repository.insertPost(new NewPost(POST_TWO, USER_TWO, PostVisibility.PUBLIC, "two", "<p>two</p>", 3));
        jdbc.sql("""
                        insert into post (
                            id, author_user_id, visibility, prose_markdown, rendered_html,
                            prose_character_count, created_at, updated_at
                        )
                        values (:id, :authorUserId, 'PRIVATE', 'hidden', '<p>hidden</p>', 6, now(), now())
                        """)
                .param("id", UUID.fromString("33333333-3333-4333-8333-333333333333"))
                .param("authorUserId", USER_ONE)
                .update();

        var firstPage = repository.publicFeed(Optional.empty(), 1);
        assertEquals(1, firstPage.posts().size());
        assertEquals(POST_TWO, firstPage.posts().getFirst().id());
        assertFalse(firstPage.nextCursor().isBlank());

        var secondPage = repository.publicFeed(Optional.of(new com.ddd.d3.community.application.CommunityService.FeedCursor(
                firstPage.posts().getFirst().createdAt(), firstPage.posts().getFirst().id())), 2);
        assertEquals(List.of(POST_ONE), secondPage.posts().stream().map(post -> post.id()).toList());
        assertNull(secondPage.nextCursor());
    }
}
