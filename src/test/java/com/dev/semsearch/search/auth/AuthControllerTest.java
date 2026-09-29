package com.dev.semsearch.search.auth;

import com.dev.semsearch.common.alfresco.AlfrescoTicketClient;
import com.dev.semsearch.common.alfresco.AlfrescoUserClient;
import com.dev.semsearch.common.alfresco.AlfrescoUserClient.AlfrescoPerson;
import com.dev.semsearch.common.alfresco.UserAlreadyExistsException;
import com.dev.semsearch.search.authority.AuthorityService;
import com.dev.semsearch.search.config.SearchSecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import(SearchSecurityConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AlfrescoTicketClient ticketClient;

    @MockitoBean
    private AlfrescoUserClient userClient;

    @MockitoBean
    private AuthorityService authorityService;

    @Test
    void loginWithValidCredentialsReturnsJwtToken() throws Exception {
        when(ticketClient.createTicket("abc", "password123")).thenReturn(Optional.of("TICKET_12345"));
        when(userClient.isAdmin("abc")).thenReturn(false);
        when(authorityService.getAuthoritiesForUser("abc")).thenReturn(List.of("abc", "GROUP_EVERYONE"));
        when(userClient.getUser("abc")).thenReturn(Optional.of(
                new AlfrescoPerson("abc", "Abc", "User", "abc@example.com", true)
        ));

        AuthDtos.LoginRequest request = new AuthDtos.LoginRequest("abc", "password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.username").value("abc"))
                .andExpect(jsonPath("$.user.firstName").value("Abc"))
                .andExpect(jsonPath("$.user.isAdmin").value(false));
    }

    @Test
    void loginWithInvalidCredentialsReturns401() throws Exception {
        when(ticketClient.createTicket("wrong", "password")).thenReturn(Optional.empty());

        AuthDtos.LoginRequest request = new AuthDtos.LoginRequest("wrong", "password");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username or password"));
    }

    @Test
    void loginWithEmptyFieldsReturns400() throws Exception {
        AuthDtos.LoginRequest request = new AuthDtos.LoginRequest("", "");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signupWithValidDetailsReturns201AndToken() throws Exception {
        when(userClient.createUser("newuser", "password123", "New", "User", "new@example.com"))
                .thenReturn(new AlfrescoPerson("newuser", "New", "User", "new@example.com", true));

        AuthDtos.SignupRequest request = new AuthDtos.SignupRequest(
                "newuser", "password123", "New", "User", "new@example.com");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.username").value("newuser"))
                .andExpect(jsonPath("$.user.email").value("new@example.com"));
    }

    @Test
    void signupWithExistingUsernameReturns409() throws Exception {
        when(userClient.createUser(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new UserAlreadyExistsException("User 'existing' or email already exists"));

        AuthDtos.SignupRequest request = new AuthDtos.SignupRequest(
                "existing", "password123", "Existing", "User", "existing@example.com");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("User 'existing' or email already exists"));
    }

    @Test
    void signupWithWeakPasswordReturns400() throws Exception {
        AuthDtos.SignupRequest request = new AuthDtos.SignupRequest(
                "user", "123", "User", "Name", "user@example.com");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Password must be at least 6 characters long"));
    }

    @Test
    void getCurrentUserWhenAuthenticatedReturnsProfile() throws Exception {
        when(userClient.isAdmin("abc")).thenReturn(false);
        when(userClient.getUser("abc")).thenReturn(Optional.of(
                new AlfrescoPerson("abc", "Abc", "User", "abc@example.com", true)
        ));

        mockMvc.perform(get("/api/auth/me").with(user("abc").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("abc"))
                .andExpect(jsonPath("$.email").value("abc@example.com"));
    }

    @Test
    void getCurrentUserWhenUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
