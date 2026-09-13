package com.shiva.wallet.repository;

import com.shiva.wallet.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Lookup of the calling user by bearer token. Users are seeded by Flyway, never created here. */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByBearerToken(String bearerToken);

    Optional<User> findByExternalId(String externalId);
}
