package io.github.wesleyosantos91.proposta.proposal.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import io.github.wesleyosantos91.proposta.proposal.application.CreateProposalUseCase;
import io.github.wesleyosantos91.proposta.proposal.application.CreatedProposal;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProposalControllerTests {
    private final CreateProposalUseCase useCase = mock(CreateProposalUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new ProposalController(useCase))
                .setControllerAdvice(new ProposalExceptionHandler()).setValidator(validator).build();
    }

    @Test
    void returnsAcceptedOnlyAfterPublicationConfirmation() throws Exception {
        var confirmation = new CompletableFuture<CreatedProposal>();
        when(useCase.create("key", "AUTO")).thenReturn(confirmation);
        var request = mvc.perform(post("/proposals").header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productKey\":\"AUTO\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        confirmation.complete(new CreatedProposal("proposal", "journey", "correlation"));
        mvc.perform(asyncDispatch(request)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.proposalId").value("proposal"))
                .andExpect(jsonPath("$.journeyId").value("journey"))
                .andExpect(jsonPath("$.correlationId").value("correlation"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidRequestsReturnProblemDetailsWithoutPublishing(String body) throws Exception {
        mvc.perform(post("/proposals").header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(useCase);
    }

    static Stream<String> invalidRequests() {
        return Stream.of("{}", "{\"productKey\":\" \"}", "{\"productKey\":null}",
                "{\"productKey\":\"" + "A".repeat(65) + "\"}", "{");
    }

    @ParameterizedTest
    @MethodSource("publicationFailures")
    void returnsSafeProblemDetailsOnFailure(Exception failure, int status) throws Exception {
        when(useCase.create("key", "AUTO")).thenReturn(CompletableFuture.failedFuture(failure));
        var request = mvc.perform(post("/proposals").header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productKey\":\"AUTO\"}")).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("internal-sensitive-detail"))));
    }

    static Stream<Arguments> publicationFailures() {
        return Stream.of(
                Arguments.of(new io.github.wesleyosantos91.proposta.proposal.application.IdempotencyConflictException(), 409),
                Arguments.of(new io.github.wesleyosantos91.proposta.proposal.application.InvalidIdempotencyKeyException(), 400),
                Arguments.of(new SerializationException("internal-sensitive-detail"), 503),
                Arguments.of(new IllegalStateException("internal-sensitive-detail"), 503),
                Arguments.of(new TimeoutException("internal-sensitive-detail"), 504));
    }
}
