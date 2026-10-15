package com.tokenledgercloud.api.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.tokenledgercloud.api.domain.member.service.CustomOAuth2UserService;
import com.tokenledgercloud.api.domain.apikey.service.ApiKeyService;
import com.tokenledgercloud.api.global.security.ApiKeyAuthenticationFilter;
import com.tokenledgercloud.api.global.security.JwtAuthenticationFilter;
import com.tokenledgercloud.api.global.security.JwtTokenProvider;
import com.tokenledgercloud.api.global.security.OAuth2SuccessHandler;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private final CustomOAuth2UserService customOAuth2UserService;
	private final JwtTokenProvider jwtTokenProvider;
	private final OAuth2SuccessHandler oAuth2SuccessHandler;
	private final ApiKeyService apiKeyService;

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			.cors(Customizer.withDefaults())
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.headers(headers -> headers.frameOptions(frame -> frame.disable()))
			.authorizeHttpRequests(auth -> auth
				
				.requestMatchers("/h2-console/**").permitAll()
				.requestMatchers("/actuator/**").permitAll()
				.requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/members").permitAll()
				.requestMatchers(HttpMethod.POST, "/internal/usage-logs").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/ingestion/**").permitAll()
				// [이슈 #11] Observe 수집 v2도 기존 수집 API처럼 프로젝트 API 키로 서비스에서 인증한다.
				.requestMatchers(HttpMethod.POST, "/api/v2/observe/events").permitAll()
				.requestMatchers("/api/dashboard/**").permitAll()
				.requestMatchers("/dashboard").permitAll()
				.requestMatchers("/login", "/oauth2/**", "/error").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/pricing-catalogs/**").permitAll()
				// [이슈 #11] Observe 원장 조회 API는 로그인한 사용자만 호출할 수 있다. (아래 개발용 전체 허용보다 먼저 와야 한다)
				.requestMatchers("/api/observe/**").authenticated()
				//.anyRequest().authenticated()
				.anyRequest().permitAll() //개발용

			)
			// [이슈 #11] /api/ 경로의 미인증 요청은 OAuth 로그인 페이지로 보내지 않고 401을 돌려준다.
			.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
				new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
				request -> request.getRequestURI().startsWith("/api/")
			))
			.oauth2Login(oauth2 -> oauth2
				.userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
				.successHandler(oAuth2SuccessHandler)
			)
			.addFilterBefore(new ApiKeyAuthenticationFilter(apiKeyService), UsernamePasswordAuthenticationFilter.class)
			.addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
