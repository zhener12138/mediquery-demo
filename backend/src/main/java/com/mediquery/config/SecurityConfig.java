package com.mediquery.config;

import com.mediquery.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/illnesses/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/medicines/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/doctors/**").permitAll()
                .requestMatchers("/api/v1/files/upload").authenticated()
                .requestMatchers("/api/v1/feedbacks").authenticated()
                // Swagger/Knife4j
                .requestMatchers("/doc.html", "/swagger-ui/**", "/v3/api-docs/**", "/webjars/**").permitAll()
                // Admin only
                .requestMatchers(HttpMethod.POST, "/api/v1/illnesses/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/illnesses/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/illnesses/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/medicines/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/medicines/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/medicines/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/doctors/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/doctors/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/doctors/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/dashboard/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                // Everything else requires authentication
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
