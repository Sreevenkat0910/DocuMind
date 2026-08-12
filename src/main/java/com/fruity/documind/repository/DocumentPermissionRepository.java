package com.fruity.documind.repository;

import com.fruity.documind.entity.DocumentPermission;
import com.fruity.documind.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocumentPermissionRepository extends JpaRepository<DocumentPermission, UUID> {

    /**
     * The set of document ids a user may access, resolving BOTH grant kinds:
     *  - user-specific grants (permission.user = this user), and
     *  - role-based grants   (permission.role = this user's role).
     *
     * Every access level (VIEW/EDIT/OWNER) implies read access, so no level filter here.
     * This is the authoritative permission query — it is run both as the read-path
     * pre-filter (step 2) and again as the post-search re-verification gate (step 4).
     */
    @Query("""
            select distinct p.document.id
            from DocumentPermission p
            where p.user.id = :userId or p.role = :role
            """)
    List<UUID> findAccessibleDocumentIds(@Param("userId") UUID userId, @Param("role") Role role);
}
