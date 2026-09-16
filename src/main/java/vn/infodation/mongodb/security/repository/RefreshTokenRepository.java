package vn.infodation.mongodb.security.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import vn.infodation.mongodb.security.domain.RefreshToken;

public interface RefreshTokenRepository extends MongoRepository<RefreshToken, String> {

    List<RefreshToken> findByUserEmail(String userEmail);
}
