package com.acme.salary.repository;

import com.acme.salary.model.PayBand;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayBandRepository extends JpaRepository<PayBand, Long> {
}
