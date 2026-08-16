package com.ddd.d3.community.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ddd.d3.community.adapter.persistence.JdbcCommunityRepository;
import com.ddd.d3.community.domain.MarkdownPolicy;
import com.ddd.d3.community.domain.PostVisibility;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommunityServiceTest {

    private final JdbcCommunityRepository repository = mock(JdbcCommunityRepository.class);
    private final CommunityService service =
            new CommunityService(repository, new MarkdownPolicy(), UUID::randomUUID, 2_000, 20_000);

    @Test
    void d3Sec001RejectsOversizedMarkdownEvenWhenAnUnclosedFenceMakesProseCountSmall() {
        String markdown = "```\n" + "x".repeat(20_001);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.createPublicPost(
                        UUID.fromString("11111111-1111-4111-8111-111111111111"),
                        markdown,
                        PostVisibility.PUBLIC));
        verify(repository, never()).insertPost(any());
    }

    @Test
    void d3Com001RejectsInvalidFeedCursorsInsteadOfFallingBackToTheFirstPage() {
        assertThrows(IllegalArgumentException.class, () -> service.publicFeed(Optional.of("not-a-cursor"), 20));
        verify(repository, never()).publicFeed(any(), anyInt());
    }

    @Test
    void d3Stat001RejectsInvalidMatchRecordCursorsInsteadOfFallingBackToTheFirstPage() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.playerMatches(
                        UUID.fromString("11111111-1111-4111-8111-111111111111"),
                        Optional.of("not-a-cursor"),
                        20));
        verify(repository, never()).playerMatches(any(), any(), anyInt());
    }
}
