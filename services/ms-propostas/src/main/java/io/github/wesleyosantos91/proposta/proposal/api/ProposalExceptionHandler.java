package io.github.wesleyosantos91.proposta.proposal.api;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

@RestControllerAdvice(assignableTypes = ProposalController.class)
class ProposalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ProposalExceptionHandler.class);

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ProblemDetail invalidRequest(Exception failure) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Informe um productKey não vazio com até 64 caracteres em um JSON válido.");
    }

    @ExceptionHandler(io.github.wesleyosantos91.proposta.proposal.application.InvalidIdempotencyKeyException.class)
    ProblemDetail invalidKey() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Informe Idempotency-Key com 1 a 128 caracteres ASCII visíveis.");
    }

    @ExceptionHandler(io.github.wesleyosantos91.proposta.proposal.application.IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Idempotency-Key já utilizada com conteúdo diferente.");
    }
    @ExceptionHandler(Exception.class)
    ProblemDetail publicationFailed(Exception failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        boolean timeout = cause instanceof TimeoutException
                || cause instanceof org.apache.kafka.common.errors.TimeoutException
                || cause instanceof AsyncRequestTimeoutException;
        LOG.atError()
                .addKeyValue("failureType", cause.getClass().getSimpleName())
                .log("Proposal publication failed; confirmation unavailable");
        return ProblemDetail.forStatusAndDetail(
                timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.SERVICE_UNAVAILABLE,
                timeout ? "Confirmação não recebida no prazo. A publicação pode ter ocorrido."
                        : "Não foi possível confirmar a publicação da proposta.");
    }
}
