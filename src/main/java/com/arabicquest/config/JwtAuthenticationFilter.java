package com.arabicquest.config;

import com.arabicquest.model.User;
import com.arabicquest.repository.UserRepository;
import com.arabicquest.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = null;
            boolean fromCookie = false;

            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring(7).trim();
            } else if (request.getCookies() != null) {
                for (Cookie cookie : request.getCookies()) {
                    if (JwtService.AUTH_COOKIE_NAME.equals(cookie.getName())) {
                        token = cookie.getValue();
                        fromCookie = true;
                        break;
                    }
                }
            }

            if (token != null && !token.isEmpty()) {
                boolean csrfSafe = true;
                if (fromCookie && !SAFE_METHODS.contains(request.getMethod())) {
                    String xrw = request.getHeader("X-Requested-With");
                    String contentType = request.getContentType();
                    boolean hasCustomHeader = xrw != null && !xrw.isBlank();
                    boolean isJsonBody = contentType != null && contentType.toLowerCase().contains("application/json");
                    if (!hasCustomHeader && !isJsonBody) {
                        csrfSafe = false;
                    }
                }

                if (csrfSafe) {
                    Optional<JwtService.JwtClaims> claimsOpt = jwtService.verifyToken(token);
                    if (claimsOpt.isPresent()) {
                        JwtService.JwtClaims claims = claimsOpt.get();
                        User user = userRepository.findByUsername(claims.username()).orElse(null);
                        if (user != null && user.isEnabled() && user.getTokenVersion() == claims.tokenVersion()) {
                            UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                                    user.getUsername(),
                                    "",
                                    user.isEnabled(),
                                    true,
                                    true,
                                    true,
                                    Collections.singletonList(new SimpleGrantedAuthority(user.getRole().name()))
                            );
                            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,
                                    userDetails.getAuthorities()
                            );
                            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                            SecurityContextHolder.getContext().setAuthentication(auth);
                        }
                    }
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
