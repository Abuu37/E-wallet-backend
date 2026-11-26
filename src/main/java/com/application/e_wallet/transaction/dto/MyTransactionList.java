package com.application.e_wallet.transaction.dto;

import java.util.List;

public record MyTransactionList(List<MyTransactionItem> items, int page, int size, long totalElements) {
}
