package com.dev.semsearch.search.authority;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorityServiceTest {

    @Mock
    private AlfrescoAuthorityClient authorityClient;

    private AuthorityService authorityService;

    @BeforeEach
    void setUp() {
        authorityService = new AuthorityService(authorityClient);
    }

    @Test
    void testGetAuthoritiesForUserIncludesUsernameGroupsAndEveryone() {
        when(authorityClient.getUserGroups("alice"))
                .thenReturn(List.of("GROUP_ENGINEERING", "GROUP_SITE_DEV"));

        List<String> authorities = authorityService.getAuthoritiesForUser("alice");

        assertThat(authorities).containsExactly(
                "GROUP_ENGINEERING",
                "GROUP_EVERYONE",
                "GROUP_SITE_DEV",
                "alice"
        );
        verify(authorityClient).getUserGroups("alice");
    }

    @Test
    void testGetAuthoritiesForUserWhenNoGroupsReturnsUsernameAndEveryone() {
        when(authorityClient.getUserGroups("bob"))
                .thenReturn(List.of());

        List<String> authorities = authorityService.getAuthoritiesForUser("bob");

        assertThat(authorities).containsExactly(
                "GROUP_EVERYONE",
                "bob"
        );
    }

    @Test
    void testGetAuthoritiesForNullOrBlankUserReturnsOnlyEveryone() {
        List<String> nullAuth = authorityService.getAuthoritiesForUser(null);
        assertThat(nullAuth).containsExactly("GROUP_EVERYONE");

        List<String> blankAuth = authorityService.getAuthoritiesForUser("   ");
        assertThat(blankAuth).containsExactly("GROUP_EVERYONE");
    }

    @Test
    void testGetAuthoritiesDeduplicatesIfAlfrescoAlreadyReturnedEveryone() {
        when(authorityClient.getUserGroups("admin"))
                .thenReturn(List.of("GROUP_EVERYONE", "GROUP_ADMINISTRATORS"));

        List<String> authorities = authorityService.getAuthoritiesForUser("admin");

        assertThat(authorities).containsExactly(
                "GROUP_ADMINISTRATORS",
                "GROUP_EVERYONE",
                "admin"
        );
    }
}
