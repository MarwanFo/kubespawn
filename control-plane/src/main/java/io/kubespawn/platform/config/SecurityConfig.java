package io.kubespawn.platform.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {

    @Bean
    @Profile("local")
    SecurityFilterChain localSecurity(HttpSecurity http,
            @Value("${KUBESPAWN_DEV_TOKEN}") String token) throws Exception {
        if (token.length() < 32 || token.startsWith("replace-")) {
            throw new IllegalArgumentException("Generate a local token with scripts/setup.ps1 first");
        }
        configureBase(http);
        http.addFilterBefore(new LocalTokenFilter(token), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Profile("!local")
    SecurityFilterChain oidcSecurity(HttpSecurity http) throws Exception {
        configureBase(http);
        http.oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    @Profile("!local")
    JwtDecoder jwtDecoder(@Value("${OIDC_ISSUER_URI}") String issuer) {
        return JwtDecoders.fromIssuerLocation(issuer);
    }

    private void configureBase(HttpSecurity http) throws Exception {
        // Stateless Authorization-header tokens only; no cookie or HTTP Basic authentication.
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/api/v1/**").hasAuthority("SCOPE_environments:read")
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors.authenticationEntryPoint(
                (request, response, exception) -> response.sendError(401)));
    }

    private static final class LocalTokenFilter extends OncePerRequestFilter {
        private final byte[] expected;

        LocalTokenFilter(String token) {
            expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            String supplied = request.getHeader("Authorization");
            if (supplied != null && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
                var authentication = new UsernamePasswordAuthenticationToken("local-developer", null,
                    List.of(new SimpleGrantedAuthority("SCOPE_environments:read")));
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            }
            chain.doFilter(request, response);
        }
    }
}
