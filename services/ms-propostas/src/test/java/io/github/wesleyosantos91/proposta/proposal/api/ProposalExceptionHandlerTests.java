package io.github.wesleyosantos91.proposta.proposal.api;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import static org.junit.jupiter.api.Assertions.*;

class ProposalExceptionHandlerTests {
    @ParameterizedTest
    @MethodSource("wrappedTimeouts")
    void wrappedTimeoutsUse504WithAmbiguousPublicationExplanation(Exception failure) {
        var problem = new ProposalExceptionHandler().publicationFailed(failure);
        assertEquals(504, problem.getStatus());
        assertEquals("Confirmação não recebida no prazo. A publicação pode ter ocorrido.", problem.getDetail());
    }

    @ParameterizedTest
    @MethodSource("wrappersWithoutCause")
    void wrappersWithoutCauseReturn503WithoutLeakingThePrivateMessage(Exception failure) {
        var problem = new ProposalExceptionHandler().publicationFailed(failure);
        assertEquals(503, problem.getStatus());
        assertEquals("Não foi possível confirmar a publicação da proposta.", problem.getDetail());
    }

    static Stream<Exception> wrappersWithoutCause() {
        return Stream.of(new CompletionException("private-detail", null),
                new ExecutionException("private-detail", null));
    }
    static Stream<Exception> wrappedTimeouts() {
        return Stream.of(new CompletionException(new ExecutionException(new TimeoutException())),
                new org.apache.kafka.common.errors.TimeoutException("private-detail"),
                new AsyncRequestTimeoutException());
    }
}
