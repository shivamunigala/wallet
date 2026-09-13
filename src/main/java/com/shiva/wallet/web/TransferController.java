package com.shiva.wallet.web;

import com.shiva.wallet.domain.Transfer;
import com.shiva.wallet.domain.TransferStatus;
import com.shiva.wallet.domain.User;
import com.shiva.wallet.domain.exception.InvalidRequestException;
import com.shiva.wallet.domain.exception.UnauthorizedException;
import com.shiva.wallet.service.TransferService;
import com.shiva.wallet.web.dto.TransferRequest;
import com.shiva.wallet.web.dto.TransferResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.UUID;

/**
 * Transfer endpoints.
 *
 * <p>A declined transfer returns <strong>422</strong> carrying the full transfer body, not
 * a bare error: the transfer genuinely exists, is addressable by id, and replaying the same
 * idempotency key must return this same 422. Returning 200 with a DECLINED status was the
 * alternative and was rejected because "the request succeeded" is the wrong thing to tell a
 * client whose money did not move.
 */
@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> create(@Valid @RequestBody TransferRequest request) {
        User caller = requireCaller();
        Transfer transfer = transferService.transfer(
                caller.getId(),
                request.getFrom(),
                request.getTo(),
                request.getAmountPaise(),
                request.getIdempotencyKey());
        return ResponseEntity.status(statusFor(transfer)).body(new TransferResponse(transfer));
    }

    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable("id") String id) {
        requireCaller();
        return new TransferResponse(transferService.findByPublicId(parseId(id)));
    }

    private static HttpStatus statusFor(Transfer transfer) {
        return transfer.getStatus() == TransferStatus.COMPLETED
                ? HttpStatus.CREATED
                : HttpStatus.UNPROCESSABLE_ENTITY;
    }

    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Transfer id must be a UUID");
        }
    }

    private User requireCaller() {
        User caller = CallerContext.get();
        if (caller == null) {
            throw new UnauthorizedException("Provide a valid 'Authorization: Bearer <token>' header");
        }
        return caller;
    }
}
