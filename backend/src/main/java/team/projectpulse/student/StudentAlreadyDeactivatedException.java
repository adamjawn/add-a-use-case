package team.projectpulse.student;

/**
 * UC-STU-deactivate-student extension 6b: the student's account was already deactivated, typically by another
 * course admin or instructor after this one opened her details. {@code ExceptionHandlerAdvice} answers it with
 * {@code 409}.
 */
public class StudentAlreadyDeactivatedException extends RuntimeException {

    public StudentAlreadyDeactivatedException() {
        super("This student is already deactivated.");
    }

}
