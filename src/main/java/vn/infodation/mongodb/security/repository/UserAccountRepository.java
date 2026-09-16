package vn.infodation.mongodb.security.repository;

import java.util.Optional;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

import vn.infodation.mongodb.security.domain.UserAccount;

public interface UserAccountRepository extends MongoRepository<UserAccount, ObjectId> {

    /** Backed by {@code users_email_unique} from {@code IndexConfig}. */
    Optional<UserAccount> findByEmail(String email);
}
