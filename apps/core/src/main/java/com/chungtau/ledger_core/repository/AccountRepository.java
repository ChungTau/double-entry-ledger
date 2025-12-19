package com.chungtau.ledger_core.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.chungtau.ledger_core.entity.Account;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    //Finds all accounts by their associated User ID.
    List<Account> findAllByUserId(String userId);
}