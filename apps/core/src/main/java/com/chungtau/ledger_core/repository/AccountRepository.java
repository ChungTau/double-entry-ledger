package com.chungtau.ledger_core.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.chungtau.ledger_core.entity.Account;

import jakarta.persistence.LockModeType;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    //Finds an account by ID with pessimistic write lock for transactional updates.
    @Transactional
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Override
    @NonNull
    Optional<Account> findById(@NonNull UUID id);

    //Finds all accounts by their associated User ID.
    List<Account> findAllByUserId(String userId);
}