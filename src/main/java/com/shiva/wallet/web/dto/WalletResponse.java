package com.shiva.wallet.web.dto;

/** Wallet id and balance. Balance is always integer paise. */
public class WalletResponse {

    private final long id;
    private final long balancePaise;

    public WalletResponse(long id, long balancePaise) {
        this.id = id;
        this.balancePaise = balancePaise;
    }

    public long getId() {
        return id;
    }

    public long getBalancePaise() {
        return balancePaise;
    }
}
