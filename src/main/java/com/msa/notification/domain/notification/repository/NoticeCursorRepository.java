package com.msa.notification.domain.notification.repository;

import com.msa.notification.domain.notification.entity.NoticeCursor;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/** PostgreSQL 원자적 최대 버전 갱신으로 중복과 역순 이벤트를 처리합니다. */
public interface NoticeCursorRepository extends JpaRepository<NoticeCursor, String> {
    @Modifying
    @Query(
            value =
                    "insert into notification.notice_cursor(id,latest_version) values"
                        + " (:id,:version) on conflict(id) do update set"
                        + " latest_version=greatest(notification.notice_cursor.latest_version,excluded.latest_version)",
            nativeQuery = true)
    void advance(@Param("id") String id, @Param("version") long version);
}
