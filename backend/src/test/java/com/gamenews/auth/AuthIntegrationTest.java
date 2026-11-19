package com.gamenews.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired SecretKey key;
    @Autowired jakarta.validation.Validator validator;
    private static final String PASSWORD = "valid-password-123";

    @BeforeEach void reset() { users.deleteAll(); }

    MvcResult credentials(String path, String email, String password, int status) throws Exception {
        return mvc.perform(post("/api/auth/" + path).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", email, "password", password))))
            .andExpect(status().is(status)).andReturn();
    }

    UserAccount account(UserAccount.Role role) {
        return users.saveAndFlush(new UserAccount("member@example.com", encoder.encode(PASSWORD), role));
    }

    String login() throws Exception {
        return json.readTree(credentials("login", "MEMBER@example.com", PASSWORD, 200).getResponse().getContentAsString()).get("accessToken").asText();
    }

    String signed(UserAccount user, String variant) throws Exception {
        Instant now = Instant.now();
        var builder = new JWTClaimsSet.Builder().subject(user.getId().toString()).issuer("game-news-api")
            .audience("game-news-web").issueTime(Date.from(now.minusSeconds(60))).expirationTime(Date.from(now.plusSeconds(600)));
        switch (variant) {
            case "expired" -> builder.expirationTime(Date.from(now.minusSeconds(1)));
            case "issuer" -> builder.issuer("other-api");
            case "audience" -> builder.audience("other-web");
            case "no-audience" -> builder.audience((List<String>) null);
            case "no-expiry" -> builder.expirationTime(null);
            case "no-issued-at" -> builder.issueTime(null);
            case "future" -> builder.notBeforeTime(Date.from(now.plusSeconds(60)));
            case "future-issued-at" -> builder.issueTime(Date.from(now.plusSeconds(60)));
            case "bad-subject" -> builder.subject("9223372036854775808");
            case "forged-role" -> builder.claim("role", "ADMIN");
        }
        var algorithm = variant.equals("algorithm") ? JWSAlgorithm.HS512 : JWSAlgorithm.HS256;
        byte[] secret = variant.equals("algorithm") ? new byte[64] : variant.equals("signature") ? new byte[32] : key.getEncoded();
        var token = new SignedJWT(new JWSHeader(algorithm), builder.build());
        token.sign(new MACSigner(secret));
        return token.serialize();
    }

    @Test void signupNormalizesEmailHashesPasswordAndReturnsNoSecrets() throws Exception {
        var response = credentials("signup", "  Member@Example.com  ", PASSWORD, 201).getResponse();
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("email").asText()).isEqualTo("member@example.com");
        assertThat(body.get("role").asText()).isEqualTo("USER");
        assertThat(body.has("passwordHash")).isFalse();
        var user = users.findByEmail("member@example.com").orElseThrow();
        assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
        String token = login();
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("member@example.com"))
            .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test void duplicateEmailIs409() throws Exception {
        account(UserAccount.Role.USER);
        credentials("signup", "MEMBER@EXAMPLE.COM", PASSWORD, 409);
        assertThat(users.count()).isEqualTo(1);
    }

    @Test void simultaneousSignupCreatesExactlyOneAccount() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> signup = () -> {
                ready.countDown(); start.await();
                return mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("email", "same@example.com", "password", PASSWORD))))
                    .andReturn().getResponse().getStatus();
            };
            var first = executor.submit(signup);
            var second = executor.submit(signup);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
            assertThat(users.count()).isEqualTo(1);
        }
    }

    @Test void roleInjectionIsRejected() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"admin@example.com\",\"password\":\"valid-password\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isBadRequest());
        assertThat(users.count()).isZero();
    }

    @Test void malformedInputIs400() throws Exception {
        credentials("signup", "invalid", PASSWORD, 400);
        credentials("signup", "valid@example.com", "short", 400);
        credentials("signup", "valid@example.com", "가".repeat(25), 400);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.email").exists());
        assertThat(users.count()).isZero();
    }

    @Test void wrongPasswordAndUnknownUserHaveSameResponse() throws Exception {
        account(UserAccount.Role.USER);
        String wrong = credentials("login", "member@example.com", "wrong-password", 401).getResponse().getContentAsString();
        String missing = credentials("login", "missing@example.com", "wrong-password", 401).getResponse().getContentAsString();
        assertThat(wrong).isEqualTo(missing);
    }

    @Test void anonymousProtectedRequestsAre401() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/access")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Bearer nonsense")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "issuer", "audience", "no-audience", "no-expiry", "no-issued-at", "future", "future-issued-at", "bad-subject", "signature", "algorithm"})
    void invalidTokensAre401(String variant) throws Exception {
        var user = account(UserAccount.Role.USER);
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + signed(user, variant)))
            .andExpect(status().isUnauthorized());
    }

    @Test void regularUserCannotAccessAdminEvenWithSignedRoleClaim() throws Exception {
        var user = account(UserAccount.Role.USER);
        mvc.perform(get("/api/admin/access").header("Authorization", "Bearer " + login())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/access").header("Authorization", "Bearer " + signed(user, "forged-role"))).andExpect(status().isForbidden());
    }

    @Test void currentDatabaseRoleOverridesPreviouslyIssuedToken() throws Exception {
        var admin = account(UserAccount.Role.ADMIN);
        String token = login();
        mvc.perform(get("/api/admin/access").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        admin.setRole(UserAccount.Role.USER); users.saveAndFlush(admin);
        mvc.perform(get("/api/admin/access").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @Test void suspensionImmediatelyBlocksExistingTokenAndNewLogin() throws Exception {
        var user = account(UserAccount.Role.USER);
        String token = login();
        user.suspend("반복적인 운영 정책 위반"); users.saveAndFlush(user);
        var blocked = mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"))
            .andExpect(jsonPath("$.suspension.reason").value("반복적인 운영 정책 위반"))
            .andExpect(jsonPath("$.suspension.suspendedAt").isNotEmpty()).andReturn();
        var deniedLogin = credentials("login", "member@example.com", PASSWORD, 403);
        assertThat(json.readTree(deniedLogin.getResponse().getContentAsString()).get("suspension"))
            .isEqualTo(json.readTree(blocked.getResponse().getContentAsString()).get("suspension"));
        var wrongPassword = credentials("login", "member@example.com", "incorrect-password", 401);
        assertThat(json.readTree(wrongPassword.getResponse().getContentAsString()).has("suspension")).isFalse();
        mvc.perform(get("/api/admin/access").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
    }

    @Test void 정지해제시안내정보가제거되고기존유효토큰도다시사용한다() throws Exception {
        var user = account(UserAccount.Role.USER);
        String token = login();
        user.suspend("운영 정책 위반"); users.saveAndFlush(user);
        user.restore(); users.saveAndFlush(user);
        var restored = users.findById(user.getId()).orElseThrow();
        assertThat(restored.getSuspensionReason()).isNull();
        assertThat(restored.getSuspendedAt()).isNull();
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test void 정지사유가없거나너무길면상태를바꾸지않는다() {
        var user = account(UserAccount.Role.USER);
        for (String reason : List.of("", "  ", "가".repeat(501))) {
            assertThatThrownBy(() -> user.suspend(reason)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(user.getStatus()).isEqualTo(UserAccount.Status.ACTIVE);
    }

    @Test void 새로고침용내정보는현재DB역할을반환한다() throws Exception {
        var user = account(UserAccount.Role.ADMIN);
        String token = login();
        user.setRole(UserAccount.Role.USER); users.saveAndFlush(user);
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("USER"));
    }

    @Test void removedUserCannotUseExistingToken() throws Exception {
        var user = account(UserAccount.Role.USER);
        String token = login(); users.delete(user);
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test void administratorBootstrapCreatesAdminButDoesNotResetExistingPassword() {
        var bootstrap = new AdminBootstrap("admin@example.com", PASSWORD, users, encoder, validator);
        bootstrap.run(null);
        var admin = users.findByEmail("admin@example.com").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(UserAccount.Role.ADMIN);
        assertThat(encoder.matches(PASSWORD, admin.getPasswordHash())).isTrue();
        new AdminBootstrap("admin@example.com", "a-different-password", users, encoder, validator).run(null);
        assertThat(users.findById(admin.getId()).orElseThrow().getPasswordHash()).isEqualTo(admin.getPasswordHash());
    }

    @Test void administratorBootstrapCannotPromoteExistingMember() {
        account(UserAccount.Role.USER);
        var bootstrap = new AdminBootstrap("member@example.com", PASSWORD, users, encoder, validator);
        assertThatThrownBy(() -> bootstrap.run(null)).isInstanceOf(IllegalStateException.class);
        assertThat(users.findByEmail("member@example.com").orElseThrow().getRole()).isEqualTo(UserAccount.Role.USER);
    }

    @Test void incompleteAdministratorConfigurationFailsClosed() {
        assertThatThrownBy(() -> new AdminBootstrap("admin@example.com", "", users, encoder, validator).run(null))
            .isInstanceOf(IllegalStateException.class);
        assertThat(users.count()).isZero();
    }
}
