package vn.infodation.mongodb.security;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import lombok.extern.slf4j.Slf4j;
import vn.infodation.mongodb.security.service.TokenService;

/**
 * Phase 2.2 and 2.3 - the signing key, and the two objects that use it.
 * <p>
 * Tokens are signed by this application and verified by this application. That is not an
 * authorization server, and is not pretending to be one: there is a single service, so the
 * indirection of a second one would buy nothing. The shape is deliberately the standard one
 * though - RS256, {@code iss}/{@code sub}/{@code exp}, a resource server reading it - so moving
 * to a real issuer later is a change of {@code JwtDecoder} and nothing else.
 */
@Slf4j
@Configuration
public class JwtConfig {

    /**
     * One property rather than two: a PKCS#8 RSA private key carries the modulus and public
     * exponent, so the public key is derived rather than configured, and the pair cannot be
     * mismatched.
     */
    @Bean
    public RsaKeyPair labJwtKeyPair(LabSecurityProperties properties) {
        String configured = properties.getJwt().getPrivateKey();
        if (configured != null && !configured.isBlank()) {
            return fromPrivateKey(configured);
        }
        log.warn("no lab.security.jwt.private-key configured - generating an ephemeral RSA keypair. "
                + "Every restart (devtools included) invalidates every token already issued.");
        return generate();
    }

    @Bean
    public JwtEncoder jwtEncoder(RsaKeyPair keys) {
        return NimbusJwtEncoder.withKeyPair(keys.publicKey(), keys.privateKey()).build();
    }

    @Bean
    public JwtDecoder jwtDecoder(RsaKeyPair keys, LabSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();
        // The default validator checks exp and nbf; adding the issuer means a token signed by
        // some other deployment sharing this key is still rejected.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.getIssuer()));
        return decoder;
    }

    /**
     * Phase 3.2 - {@code roles: ["ADMIN"]} becomes {@code ROLE_ADMIN}, which is what
     * {@code hasRole('ADMIN')} compiles down to. Without the prefix every role check silently
     * fails: the authority is there, it just is not the string the expression is looking for.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        // authentication.getName() is then the email, matching what UserDetails.getUsername()
        // returns at login. Phase 4's SpEL leans on the two agreeing.
        converter.setPrincipalClaimName("sub");
        return converter;
    }

    private static RsaKeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RsaKeyPair((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
        } catch (Exception ex) {
            throw new IllegalStateException("could not generate an RSA keypair", ex);
        }
    }

    private static RsaKeyPair fromPrivateKey(String pem) {
        try {
            String base64 = pem.replaceAll("-----(BEGIN|END)[^-]*-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);

            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey privateKey =
                    (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));

            return new RsaKeyPair(publicKey, privateKey);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "lab.security.jwt.private-key is not a base64 PKCS#8 RSA private key", ex);
        }
    }

    public record RsaKeyPair(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
    }
}
