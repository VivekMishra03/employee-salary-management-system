package com.acme.salary.repository;

import com.acme.salary.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    // FR-1.1: sign-in lookup. Backed by the unique index behind uq_app_user_email.
    Optional<AppUser> findByEmail(String email);
}
