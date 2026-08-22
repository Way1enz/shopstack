package com.ecommerce.user.service;

import com.ecommerce.user.entity.RefreshToken;
import com.ecommerce.user.entity.User;
import com.ecommerce.user.exception.ApiException;
import com.ecommerce.user.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private RefreshTokenService refreshTokenService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(1L).username("alice").build();
        // @Value fields aren't populated outside a Spring context, so it's set directly,
        // matching the application.yml default (jwt.refresh-expiration-ms: 604800000, 7 days).
        ReflectionTestUtils.setField(refreshTokenService, "refreshExpirationMs", 604_800_000L);
    }

    @Test
    void createRefreshToken_deletesAnyExistingTokenForTheUserFirst() {
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        refreshTokenService.createRefreshToken(user);

        verify(refreshTokenRepository).deleteByUser(user);
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void createRefreshToken_setsExpiryRoughlySevenDaysOut() {
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant before = Instant.now();
        RefreshToken token = refreshTokenService.createRefreshToken(user);
        Instant after = Instant.now();

        assertThat(token.getUser()).isEqualTo(user);
        assertThat(token.isRevoked()).isFalse();
        assertThat(token.getToken()).isNotBlank();
        // Allow the small window between "before" and "after" being captured around the call.
        assertThat(token.getExpiryDate()).isAfter(before.plusSeconds(604_799));
        assertThat(token.getExpiryDate()).isBefore(after.plusSeconds(604_801));
    }

    @Test
    void createRefreshToken_generatesADifferentTokenEachTime() {
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        RefreshToken first = refreshTokenService.createRefreshToken(user);
        RefreshToken second = refreshTokenService.createRefreshToken(user);

        assertThat(first.getToken()).isNotEqualTo(second.getToken());
    }

    @Test
    void findByToken_found_returnsIt() {
        RefreshToken token = RefreshToken.builder().token("abc123").user(user).build();
        when(refreshTokenRepository.findByToken("abc123")).thenReturn(Optional.of(token));

        RefreshToken result = refreshTokenService.findByToken("abc123");

        assertThat(result).isSameAs(token);
    }

    @Test
    void findByToken_notFound_throwsUnauthorized() {
        when(refreshTokenRepository.findByToken("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.findByToken("missing"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void verify_validToken_returnsItUnchanged() {
        RefreshToken token = RefreshToken.builder()
                .token("abc123").user(user).revoked(false)
                .expiryDate(Instant.now().plusSeconds(3600))
                .build();

        RefreshToken result = refreshTokenService.verify(token);

        assertThat(result).isSameAs(token);
        verify(refreshTokenRepository, never()).delete(any(RefreshToken.class));
    }

    @Test
    void verify_revokedToken_deletesItAndThrowsUnauthorized() {
        RefreshToken token = RefreshToken.builder()
                .token("abc123").user(user).revoked(true)
                .expiryDate(Instant.now().plusSeconds(3600))
                .build();

        assertThatThrownBy(() -> refreshTokenService.verify(token))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(refreshTokenRepository, times(1)).delete(token);
    }

    @Test
    void verify_expiredToken_deletesItAndThrowsUnauthorized() {
        RefreshToken token = RefreshToken.builder()
                .token("abc123").user(user).revoked(false)
                .expiryDate(Instant.now().minusSeconds(1))
                .build();

        assertThatThrownBy(() -> refreshTokenService.verify(token))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(refreshTokenRepository, times(1)).delete(token);
    }

    @Test
    void revoke_deletesTheToken() {
        RefreshToken token = RefreshToken.builder().token("abc123").user(user).build();

        refreshTokenService.revoke(token);

        verify(refreshTokenRepository).delete(token);
    }
}
