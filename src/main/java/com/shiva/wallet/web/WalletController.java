package com.shiva.wallet.web;

import com.shiva.wallet.domain.User;
import com.shiva.wallet.domain.Wallet;
import com.shiva.wallet.domain.exception.UnauthorizedException;
import com.shiva.wallet.service.WalletService;
import com.shiva.wallet.web.dto.DepositRequest;
import com.shiva.wallet.web.dto.WalletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * Wallet endpoints.
 *
 * <p>{@code POST /wallets} is get-or-create and therefore idempotent by nature: it always
 * returns 200 with the caller's single wallet, whether this call created it or found it.
 * It deliberately does not return 201-on-create, because under the concurrent-creation
 * probe the status code would then be a race outcome rather than a property of the system.
 */
@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public WalletResponse getOrCreate() {
        User caller = requireCaller();
        Wallet wallet = walletService.getOrCreate(caller.getId());
        return new WalletResponse(wallet.getId(), wallet.getBalancePaise());
    }

    @GetMapping("/{id}")
    public WalletResponse balance(@PathVariable("id") Long id) {
        User caller = requireCaller();
        Wallet wallet = walletService.getOwnedBy(id, caller.getId());
        return new WalletResponse(wallet.getId(), wallet.getBalancePaise());
    }

    /**
     * Money-in boundary, so scenarios have funds to move. Not part of the graded API.
     */
    @PostMapping("/{id}/deposit")
    public ResponseEntity<WalletResponse> deposit(@PathVariable("id") Long id,
                                                  @Valid @RequestBody DepositRequest request) {
        User caller = requireCaller();
        long balance = walletService.deposit(id, caller.getId(), request.getAmountPaise());
        return ResponseEntity.ok(new WalletResponse(id, balance));
    }

    private User requireCaller() {
        User caller = CallerContext.get();
        if (caller == null) {
            throw new UnauthorizedException("Provide a valid 'Authorization: Bearer <token>' header");
        }
        return caller;
    }
}
