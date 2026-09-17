package br.com.scrawler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class Security {
  @Bean
  UserDetailsService users(
      @Value("${scrawler.user}") String user,
      @Value("${scrawler.password}") String password,
      @Value("${scrawler.worker-password}") String workerPassword) {
    if (password.length() < 12 || workerPassword.length() < 12 || user.equals("worker"))
      throw new IllegalArgumentException(
          "Use senhas com pelo menos 12 caracteres e usuario diferente de worker");
    var encoder = new BCryptPasswordEncoder();
    return new InMemoryUserDetailsManager(
        User.withUsername(user)
            .password("{bcrypt}" + encoder.encode(password))
            .roles("REVIEWER")
            .build(),
        User.withUsername("worker")
            .password("{bcrypt}" + encoder.encode(workerPassword))
            .roles("WORKER")
            .build());
  }

  @Bean
  SecurityFilterChain chain(HttpSecurity http) throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/worker/**")
                    .hasRole("WORKER")
                    .requestMatchers("/api/**")
                    .hasRole("REVIEWER")
                    .anyRequest()
                    .permitAll())
        .httpBasic(Customizer.withDefaults())
        .build();
  }
}
