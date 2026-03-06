package com.vijay.userservice.security;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import java.util.Date;
import java.security.Key;
@Component
public class JwtUtil {
    private final String secret = "vjsecretkey-1234567890-vjsecretkey-1234567890";
    private final Key key = Keys.hmacShaKeyFor(secret.getBytes());
    public String generateToken(String email) {
        return Jwts.builder()
                .setSubject(email)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 1000 * 60 * 60 * 10))
                .signWith(key)
                .compact();
    }
    public String extractEmail(String token) {
        Claims claims = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
        return claims.getSubject();
    }
    public boolean validateToken(String token, String email){
        return extractEmail(token).equals(email);
    }
}
