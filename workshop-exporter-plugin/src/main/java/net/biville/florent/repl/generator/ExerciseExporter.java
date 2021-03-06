package net.biville.florent.repl.generator;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Output;
import org.neo4j.driver.v1.AuthToken;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.Transaction;
import org.neo4j.driver.v1.exceptions.ClientException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static java.lang.String.format;

public class ExerciseExporter implements BiConsumer<File, Collection<JsonExercise>> {

    private final Kryo kryo;
    private final Base64.Encoder encoder;
    private final String boltUri;
    private final AuthToken authToken;

    public ExerciseExporter(String boltUri, AuthToken authToken) {
        this.boltUri = boltUri;
        this.authToken = authToken;
        kryo = new Kryo();
        encoder = Base64.getEncoder();
    }

    @Override
    public void accept(File file, Collection<JsonExercise> jsonExercises) {
        try (Driver driver = GraphDatabase.driver(boltUri, authToken)) {
            Collection<String> cypherQueries = cypherQueries(driver, jsonExercises);
            Files.write(file.toPath(), cypherQueries);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private Collection<String> cypherQueries(Driver driver, Collection<JsonExercise> jsonExercises) {
        AtomicInteger rank = new AtomicInteger(1);
        Collection<String> result = jsonExercises.stream()
                .map(exercise -> tryConvertQuery(driver, exercise, rank.getAndIncrement()))
                .collect(Collectors.toList());

        if (jsonExercises.size() > 1) {
            result.add(linkQuery());
        }
        return result;
    }

    private String tryConvertQuery(Driver driver, JsonExercise exercise, int rank) {
        try (Session session = driver.session(); Transaction tx = session.beginTransaction()) {
            String query = convertToQuery(tx, exercise, rank);
            tx.failure();// always roll back, so nothing is persisted in remote database
            return query;
        } catch (ClientException e) {
            throw new RuntimeException(String.format("This exercise fails: %s%s", System.lineSeparator(), exercise.toString()), e);
        }
    }

    private String convertToQuery(Transaction transaction, JsonExercise exercise, int rank) {
        String instructions = exercise.getInstructions();

        if (exercise.requiresWrite()) {
            transaction.run(exercise.getWriteQuery()); // needs to run before the solution query
            byte[] expectedResult = serialize(transaction.run(exercise.getSolutionQuery()));
            return insertWriteExercise(instructions, rank, exercise.getSolutionQuery(), expectedResult);
        }
        byte[] expectedResult = serialize(transaction.run(exercise.getSolutionQuery()));
        return insertReadExercise(instructions, expectedResult, rank);
    }

    private String insertWriteExercise(String instructions, int rank, String solutionQuery, byte[] serializedResult) {
        return format(
                "MERGE (e:Exercise {id: {id}}) " +
                        "ON CREATE SET e.instructions = '%s', e.rank = %d, e.validationQuery = '%s', e.result = '%s' " +
                        "ON MATCH SET e.instructions = '%1$s', e.rank = %2$d, e.validationQuery = '%3$s', e.result = '%4$s'",
                escape(instructions),
                rank,
                escape(solutionQuery),
                encoder.encodeToString(serializedResult));
    }

    private String insertReadExercise(String statement, byte[] serializedResult, int rank) {
        return format("MERGE (e:Exercise {id: {id}}) " +
                        "ON CREATE SET e.instructions = '%s', e.rank = %d, e.result = '%s' " +
                        "ON MATCH SET e.instructions = '%1$s', e.rank = %2$d, e.result = '%3$s'",
                escape(statement),
                rank,
                encoder.encodeToString(serializedResult));
    }

    private String linkQuery() {
        return "MATCH (e:Exercise) WHERE EXISTS(e.rank) WITH e ORDER BY e.rank ASC WITH collect(e) AS exercises FOREACH (i IN range(0, length(exercises)-2) | FOREACH (first IN [exercises[i]] | FOREACH (second IN [exercises[i+1]] | MERGE (first)-[:NEXT]->(second) REMOVE first.rank REMOVE second.rank)))";
    }


    private byte[] serialize(StatementResult result) {
        List<Map<String, Object>> rows = makeSerializable(result.list(Record::asMap));
        try (Output output = new Output(new ByteArrayOutputStream())) {
            kryo.writeObject(output, rows);
            return output.toBytes();
        }
    }

    private List<Map<String, Object>> makeSerializable(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> entries = new HashMap<>();
            row.forEach(entries::put);
            result.add(entries);
        }
        return result;
    }

    private static String escape(String text) {
        return text.replaceAll("'", "\\\\'").replaceAll("\n", "\\\\n");
    }
}
