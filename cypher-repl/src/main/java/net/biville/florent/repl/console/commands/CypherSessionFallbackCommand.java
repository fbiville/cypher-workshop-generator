package net.biville.florent.repl.console.commands;

import net.biville.florent.repl.exercises.Exercise;
import net.biville.florent.repl.exercises.ExerciseValidation;
import net.biville.florent.repl.exercises.TraineeSession;
import net.biville.florent.repl.graph.cypher.CypherError;
import net.biville.florent.repl.graph.cypher.CypherQueryExecutor;
import net.biville.florent.repl.graph.cypher.CypherStatementValidator;
import net.biville.florent.repl.logging.ConsoleLogger;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.exceptions.ClientException;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static java.lang.String.format;

/**
 * Default command meant to be executed if and only if other commands do not match.
 * This validates the expression against the embedded grammar and rollbacks it if valid.
 */
public class CypherSessionFallbackCommand implements Command {

    private final ConsoleLogger logger;
    private final CypherQueryExecutor cypherQueryExecutor;
    private final CypherStatementValidator statementValidator;

    public CypherSessionFallbackCommand(ConsoleLogger logger,
                                        CypherQueryExecutor cypherQueryExecutor,
                                        CypherStatementValidator statementValidator) {

        this.logger = logger;
        this.cypherQueryExecutor = cypherQueryExecutor;
        this.statementValidator = statementValidator;
    }

    @Override
    public boolean matches(String query) {
        return false;
    }

    @Override
    public String help() {
        return "";
    }

    @Override
    public String name() {
        return "";
    }

    @Override
    public void accept(TraineeSession session, String statement) {
        Collection<CypherError> errors = statementValidator.validate(statement);
        if (!errors.isEmpty()) {
            logger.error("An error occurred with your query. See details below:");
            errors.forEach(err -> logger.error(err.toString()));
            return;
        }

        ExerciseValidation validation = validate(session, statement);
        if (!validation.isSuccessful()) {
            logger.failure(validation.getReport());
            return;
        }
        if (session.isCompleted()) {
            logger.success("Congrats, you're done!!!");
            return;
        }
        logger.success(validation.getReport());
        logger.information("Now moving on to next exercise! See instructions below...");
        session.getCurrentExercise().accept(logger);
    }

    private ExerciseValidation validate(TraineeSession session, String statement) {
        Exercise currentExercise = session.getCurrentExercise();
        return tryValidate(session, statement, currentExercise);
    }

    private ExerciseValidation tryValidate(TraineeSession session, String statement, Exercise currentExercise) {
        try {
            List<Map<String, Object>> actualResult = computeActualResult(statement, currentExercise);
            return session.validate(actualResult);
        } catch (RuntimeException e) {
            String message = e.getMessage();
            Throwable cause = e.getCause();
            if (cause instanceof ClientException) {
                message = cause.getMessage();
            }
            return new ExerciseValidation(false, format("An execution error occurred:%n%s", message));
        }
    }

    private List<Map<String, Object>> computeActualResult(String statement, Exercise currentExercise) {
        if (currentExercise.requiresWrites()) {
            return cypherQueryExecutor.rollback(tx -> {
                tx.run(statement);
                return tx.run(currentExercise.getWriteValidationQuery()).list(Record::asMap);
            });
        }
        return cypherQueryExecutor.rollback(tx -> {
            return tx.run(statement).list(Record::asMap);
        });
    }

}
