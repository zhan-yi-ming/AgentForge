package com.agentforge.core.security;

import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;


import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityProblemWriter problemWriter,
            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/internal/v1/rag/sources").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint((request, response, exception) -> problemWriter.write(
                                request,
                                response,
                                HttpStatus.UNAUTHORIZED,
                                "A valid Bearer access token is required."))
                        .accessDeniedHandler((request, response, exception) -> problemWriter.write(
                                request,
                                response,
                                HttpStatus.FORBIDDEN,
                                "The authenticated user is not allowed to perform this action.")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> problemWriter.write(
                                request,
                                response,
                                HttpStatus.UNAUTHORIZED,
                                "A valid Bearer access token is required."))
                        .accessDeniedHandler((request, response, exception) -> problemWriter.write(
                                request,
                                response,
                                HttpStatus.FORBIDDEN,
                                "The authenticated user is not allowed to perform this action.")));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    SecretKey jwtSecretKey(JwtProperties properties) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(properties.secret());
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalStateException("AGENTFORGE_JWT_SECRET must be valid Base64.", exception);
        }
        if (decoded.length < 32) {
            throw new IllegalStateException("AGENTFORGE_JWT_SECRET must decode to at least 32 bytes.");
        }
        return new SecretKeySpec(decoded, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey secretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(secretKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey secretKey, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
