package com.fvd.cookie.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserCookieRepository extends JpaRepository<UserCookie, Long> {

    Optional<UserCookie> findByUserIdAndPlatform(Long userId, String platform);

    List<UserCookie> findByUserId(Long userId);
}
