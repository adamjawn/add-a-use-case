package team.projectpulse.student;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StudentRepository extends JpaRepository<Student, Integer>, JpaSpecificationExecutor<Student> {

    // Find all students in a section
    List<Student> findBySectionSectionId(Integer sectionId);

    List<Student> findByTeamTeamId(Integer teamId);

    /**
     * Deactivates the student only if she is still active, in one statement, so two concurrent deactivations
     * update one row between them (UC-STU-deactivate-student, 6b). Returns the number of rows updated.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Student s set s.enabled = false where s.id = :studentId and s.enabled = true")
    int deactivateIfActive(@Param("studentId") Integer studentId);
}
